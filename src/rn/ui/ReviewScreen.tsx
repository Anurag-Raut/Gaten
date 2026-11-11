/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review flow's page: AnkiDroid's deck list and card session, and WaniKani's connection and
 * review — one step at a time, all of them on this page's own ground.
 *
 * The page is pushed from Home's two service cards, and the step it draws is the review store's own
 * state, decided before the push. Back is the page's pop, which is the whole of leaving a step; there
 * is no landing page underneath it to come back to.
 *
 * Anki is AnkiDroid's own: the queue, the intervals on the rating buttons and the answer itself all
 * come from its content provider, and Gaten keeps no progress of its own. The RN port of
 * AnkiReviewScreen.kt; the session's state lives in review-store, outside any component, so leaving
 * the page mid-session keeps the card, its timer and its open WaniKani details.
 *
 * The flow is AnkiDroid's: pick a deck (the deck Gaten exports to first, if it is there), see the
 * question, reveal the answer, rate it once. Each rating is sent once and only once; the next card
 * always comes from a fresh query. WaniKani rides on the answer side only, from the note's own raw
 * fields — the card the reader is being graded on cannot leak its answer through a kanji page.
 *
 * The card is drawn as AnkiDroid rendered it, inside the workflow's own chrome: the design's compact
 * header, its thin progress line and its pinned actions. The prototype's centred question is a
 * mockup of a five-word card, and the card's own template decides where its text goes — what the
 * chrome fixes is where the deck's name, the reader's place and the one action they have live.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {
  Animated,
  BackHandler,
  Easing,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import {useIsFocused} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {Collapsible, DisclosureChevron} from "./Collapsible";
import * as Anki from "../native/GatenAnki";
import {downloadAnkiDroid} from "./anki/anki-actions";
import * as GatenDict from "../native/GatenDict";
import * as GatenWaniKani from "../native/GatenWaniKani";
import {GatenCardWebView} from "../native/GatenCardWebView";
import {distinctIdeographs, Icon, KanjiStrip} from "./components";
import {PressableScale} from "./PressableScale";
import {CONTENT_COLUMN, APP_FONT, contentInsetFor, spacing, typeScale} from "./tokens";
import {useTheme, type Theme} from "./theme";
import {OutlinedButton} from "./settings/settings-components";
import {DialogShell} from "./DialogShell";
import {ScreenHeader} from "./ScreenHeader";
import {
  ProviderSetupDialog,
  ProviderSetupHelpTrigger,
} from "./review/provider-setup-help";
import {WaniKaniPanel} from "./wanikani/WaniKaniPanel";
import {RtkKanjiPanel} from "./rtk/RtkKanjiPanel";
import {ReferenceSection, ReferenceValue} from "./reference/ReferenceText";
import {kanjiReferenceLabel, useKanjiReferenceSource} from "./kanjiReference";
import {
  shouldConfirmLeaving,
  stepDepth,
  type ConfirmableStep,
  type ReviewFailure,
  type ReviewStep,
} from "./review/review-store";
import {reviewActions, useReview} from "./review/useReview";
import {atTeachStart} from "./review/wanikani-lesson-steps";
import {DetailsReveal} from "./review/DetailsReveal";
import {AnkiDeckPicker} from "./review/AnkiDeckPicker";
import {WaniKaniConnect} from "./review/WaniKaniConnect";
import {WaniKaniHome} from "./review/WaniKaniHome";
import {WaniKaniLesson} from "./review/WaniKaniLesson";
import {WaniKaniReview} from "./review/WaniKaniReview";
import {
  ActionFooter,
  BusyRow,
  FilledAction,
  ProgressLine,
  RatingBar,
  StateBlock,
  StepEnter,
  SubHeader,
} from "./review/review-ui";
import type {WaniKaniFailure} from "../native/GatenWaniKani";

/** How long a rating's answer flash lasts: half fading in, half fading out. */
const FLASH_MS = 250;

/** How opaque that tint gets: enough to read as an answer, not enough to hide the word under it. */
const FLASH_ALPHA = 0.18;

/** The curve a press — and the flash's half-sweep — answers with. */
const FLASH_EASING = Easing.bezier(0.4, 0, 0.2, 1);

/** The line for an AnkiDroid failure, without any of the provider's internals. */
function failureMessage(failure: ReviewFailure, detail?: string): string {
  if (failure === "PROVIDER" && detail != null) {
    return `Anki could not do that: ${detail}`;
  }
  switch (failure) {
    case "NOT_INSTALLED":
      return "Install Anki and finish setup, then return to review.";
    case "PERMISSION":
      return "Allow Gaten to read and answer Anki cards.";
    case "NOT_SET_UP":
      return "Finish setup in Anki, then return to review.";
    case "NOT_FOUND":
      return "That card is no longer in Anki.";
    case "MALFORMED":
      return "Gaten couldn't read Anki's response.";
    default:
      return "Anki couldn't complete the request.";
  }
}

/** Why a WaniKani read failed, in the same words the rest of the app uses for it. */
const WANIKANI_FAILURE_LABELS: Record<WaniKaniFailure, string> = {
  OFFLINE: "Gaten could not reach WaniKani",
  TIMEOUT: "the request timed out",
  RATE_LIMITED: "WaniKani is limiting requests just now",
  SERVER: "WaniKani reported a server error",
  TOO_LARGE: "the answer was too large to keep",
  MALFORMED: "WaniKani sent something Gaten could not read",
  STORAGE: "this device could not save the answer",
};

/** Opens AnkiDroid itself, for the media or the session this screen cannot show. */
function openAnkiDroid() {
  void Anki.openAnkiDroid().catch(() => {});
}

/**
 * The body of one of the page's own states: centred in the display it owns outright. The page is
 * pushed, so the shell's floating bar is not under it and nothing has to be reserved for it.
 */
function stateBody(bottomInset: number, contentInset: number): StyleProp<ViewStyle> {
  return [
    styles.centred,
    {
      paddingHorizontal: contentInset,
      paddingBottom: bottomInset + spacing.l,
    },
  ];
}

/**
 * The one question leaving work in progress asks, in the reader's own words: what leaving does not
 * send or grade, and where the reader ends up. Only the steps `shouldConfirmLeaving` names have an
 * answer here; the reader's place is kept in every case, because both services' sessions stay in the
 * store and their hubs offer them back.
 */
function leaveQuestion(step: ConfirmableStep): {title: string; body: string} {
  switch (step.kind) {
    case "session":
      return {
        title: "Leave the review session?",
        body:
          "Nothing is graded by leaving. The card you are on stays unanswered, and you go back to " +
          "the deck list.",
      };
    case "wkReview":
      return {
        title: "Leave the review session?",
        body:
          "Nothing is sent to WaniKani by leaving. The subject you are on stays unanswered and " +
          "your place in the queue is kept, so you can pick the review up again.",
      };
    case "wkLesson":
      return {
        title: "Leave the lesson?",
        body:
          "Nothing is sent to WaniKani by leaving. The subjects you have not finished stay in " +
          "your lessons, and the ones already started stay started.",
      };
  }
}

/**
 * The review flow's page, as the shell mounts it. `onExit` is the pop that leaves it.
 *
 * The setup help's dialog is held here rather than inside the screen it stands over: the reader
 * leaves Gaten to install or set up AnkiDroid, and the screen behind the help is re-read and
 * replaced on the way back. Held here, the dialog and the block the reader was reading stay put.
 */
export function ReviewScreen({
  onExit,
  onOpenSettingsPage,
}: {
  onExit: () => void;
  /**
   * The shell's own way to a settings page on this stack: an open reference page hands the reader
   * to WaniKani's settings to connect a token, and Back returns to the card they were on.
   */
  onOpenSettingsPage?: (page: "wanikani") => void;
}) {
  const theme = useTheme();
  const {colors} = theme;
  // A settings page this page opened: WaniKani's, whose Get a token hands the reader to the browser.
  // While it is over this page the card must not move (see `useReview`); it is cleared the moment
  // the reader is back here.
  const [settingsDetour, setSettingsDetour] = useState(false);
  const review = useReview(settingsDetour);
  const isFocused = useIsFocused();
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [ankiHelpOpen, setAnkiHelpOpen] = useState(false);
  const step = review.step;
  const ankiHelp = (
    <ProviderSetupHelpTrigger provider="anki" onPress={() => setAnkiHelpOpen(true)} />
  );

  /**
   * The reader can connect WaniKani from the settings page this screen opens over itself. Coming
   * back re-reads any open reference page once, so the token it now has shows; the review store is
   * never touched, so the card being answered stays exactly as it was left — unanswered and
   * ungraded. A changed key is the panel's own cue to look again; the overlay caller, which has no
   * navigator, never sets one.
   */
  const [referenceReload, setReferenceReload] = useState(0);
  const wasFocused = useRef(isFocused);
  useEffect(() => {
    if (isFocused && !wasFocused.current) {
      // Back here: the detour is over, and both the detour and the open reference look again.
      setSettingsDetour(false);
      setReferenceReload((count) => count + 1);
    }
    wasFocused.current = isFocused;
  }, [isFocused]);

  /**
   * The page's own route into WaniKani's settings, marking the detour on the way: the settings
   * page's Get a token leaves Gaten for the browser, and the card must not move while the reader is
   * away — nor may the reference they tapped Connect from close under them.
   */
  const openWaniKaniSettings =
    onOpenSettingsPage == null
      ? undefined
      : () => {
          setSettingsDetour(true);
          onOpenSettingsPage("wanikani");
        };

  /**
   * Back from this screen. Work in progress asks first — the Anki card, the WaniKani review and the
   * lesson batch alike — and confirming is one step back: an Anki session goes to the deck list, as
   * does a card that failed, because leaving a session is not leaving the deck; a WaniKani review or
   * lesson is the page's own pop, which is where Back already went. Every other step is the page's own
   * pop — the deck list, WaniKani's connection screen and its queue were all opened from Home, and Back
   * returns there. Nothing unsent is lost by any of it: Anki grades only what was rated, a WaniKani
   * review is sent when a subject is finished, and either service's session is kept with its place.
   */
  const leave = useCallback(() => {
    if (review.busy) return;
    if (shouldConfirmLeaving(review.step)) {
      setConfirmLeave(true);
      return;
    }
    switch (review.step.kind) {
      case "done":
        reviewActions.changeDeck();
        return;
      case "failed":
        if (review.deck != null) {
          reviewActions.changeDeck();
          return;
        }
        onExit();
        return;
      default:
        onExit();
    }
  }, [review.busy, review.step.kind, review.deck, onExit]);

  /**
   * The hardware key's route out, shared with the teaching phase: a lesson steps back one step at a
   * time until the very first one, where there is nothing left to step into and Back leaves instead.
   * Every other step is `leave`'s own business — the question for work in progress included.
   */
  const hardwareBack = useCallback(() => {
    const current = review.step;
    if (
      current.kind === "wkLesson" &&
      current.session.phase === "teaching" &&
      !atTeachStart(current.session)
    ) {
      reviewActions.lessonBack();
      return;
    }
    leave();
  }, [review.step, leave]);

  // Back is guarded only where leaving is a decision: work in progress that asks first, or a failure
  // that belongs to a deck the reader is still in. Every other step is the page's own pop, which the
  // stack already performs — a handler there would only take the platform's Back away and hand it
  // straight back.
  useEffect(() => {
    if (!isFocused) return;
    const takesItsOwnBack =
      shouldConfirmLeaving(review.step) ||
      review.step.kind === "done" ||
      (review.step.kind === "failed" && review.deck != null);
    if (!takesItsOwnBack) return;
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      hardwareBack();
      return true;
    });
    return () => subscription.remove();
  }, [isFocused, review.step.kind, review.deck, hardwareBack]);

  // The question belongs to the step that raised it: a session that is sent, or a batch that finishes,
  // while the question is up takes it down with it rather than leaving a modal over the screen that
  // replaced it — and never lets it reappear over the next one the reader starts.
  useEffect(() => {
    setConfirmLeave(false);
  }, [step.kind]);

  const question = shouldConfirmLeaving(step) ? leaveQuestion(step) : null;
  /** Confirmed: one step back from the work the question was about. */
  const leaveConfirmed = useCallback(() => {
    setConfirmLeave(false);
    // Back to the deck list, not out of the review flow: the reader still has decks to review, and
    // the card they walked away from is not theirs to grade.
    if (review.step.kind === "session") reviewActions.changeDeck();
    else onExit();
  }, [review.step.kind, onExit]);

  const requestAnkiPermission = useCallback(() => {
    void Anki.requestPermission()
      .then((granted) => {
        if (granted) reviewActions.openAnki();
        else reviewActions.permissionDenied();
      })
      .catch(() => reviewActions.permissionDenied());
  }, []);

  // The side a screen arrives from: deeper than the step before it means it was opened from here,
  // and a move back enters from the leading edge.
  const depth = stepDepth(step);
  const lastDepth = useRef(depth);
  const forward = depth >= lastDepth.current;
  useEffect(() => {
    lastDepth.current = depth;
  }, [depth]);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background}]}>
      <StepEnter pageKey={step.kind} forward={forward}>
        {step.kind === "opening" ? (
          step.source === "anki" ? (
            <AnkiDeckPicker help={ankiHelp} decks={[]} busy onBack={onExit} onStart={() => {}} />
          ) : <OpeningView source={step.source} onBack={onExit} />
        ) : step.kind === "blocked" ? (
          <BlockedView
            theme={theme}
            failure={step.failure}
            busy={review.busy}
            help={ankiHelp}
            onBack={leave}
            onGrant={requestAnkiPermission}
          />
        ) : step.kind === "decks" ? (
          <AnkiDeckPicker
            help={ankiHelp}
            decks={step.decks}
            filteredEmpty={step.filteredEmpty}
            busy={review.busy}
            onBack={leave}
            onStart={(deck) => reviewActions.startDeck(deck)}
          />
        ) : step.kind === "session" ? (
          <AnkiSession
            theme={theme}
            session={step}
            busy={review.busy}
            expandedKanji={review.expandedKanji}
            expandedRadical={review.expandedRadical}
            onBack={leave}
            onReveal={() => reviewActions.reveal()}
            onGrade={(ease) => reviewActions.grade(step, ease)}
            onKanji={(character) => reviewActions.toggleKanji(character)}
            onRadical={(id) => reviewActions.toggleRadical(id)}
            onCloseDetails={() => reviewActions.closeDetails()}
            onOpenSettings={openWaniKaniSettings}
            referenceReload={referenceReload}
          />
        ) : step.kind === "wkConnect" ? (
          <WaniKaniConnect
            rejected={step.rejected}
            busy={review.busy}
            onBack={leave}
            onConnect={(token) => reviewActions.connectWaniKani(token)}
          />
        ) : step.kind === "wkHome" ? (
          <WaniKaniHome
            hub={step}
            busy={review.busy}
            onBack={leave}
            onStartReviews={() => reviewActions.startReviews()}
            onStartLessons={() => reviewActions.startLessons()}
            onRetrySync={() => reviewActions.retryWaniKaniSync()}
            onConnect={() => reviewActions.showWaniKaniConnect()}
          />
        ) : step.kind === "wkReview" ? (
          <WaniKaniReview
            session={step.session}
            busy={review.busy}
            onBack={leave}
            onEntry={(text) => reviewActions.wkEntry(text)}
            onCheck={() => reviewActions.wkCheck()}
            onContinue={() => reviewActions.wkContinue()}
            onRetrySubmit={() => reviewActions.wkRetrySubmit()}
          />
        ) : step.kind === "wkLesson" ? (
          <WaniKaniLesson
            session={step.session}
            busy={review.busy}
            onBack={leave}
            onStepBack={() => reviewActions.lessonBack()}
            onNext={() => reviewActions.lessonNext()}
            onEntry={(text) => reviewActions.lessonEntry(text)}
            onCheck={() => reviewActions.lessonCheck()}
            onRetrySubmit={() => reviewActions.lessonRetrySubmit()}
          />
        ) : step.kind === "wkFailed" ? (
          <WaniKaniFailedView
            theme={theme}
            step={step}
            busy={review.busy}
            onBack={leave}
            onRetry={() => reviewActions.retryWaniKani()}
          />
        ) : step.kind === "done" ? (
          <DoneView theme={theme} deckName={step.deckName} busy={review.busy} onFinish={leave} />
        ) : (
          <FailedView
            theme={theme}
            step={step}
            busy={review.busy}
            onBack={leave}
            onRetry={() => reviewActions.retry()}
            onChangeDeck={() => reviewActions.changeDeck()}
          />
        )}
      </StepEnter>

      {/* Above the step the page is drawing: the reader goes to AnkiDroid's own screens from here,
          and what comes back may be a different step of the flow. */}
      <ProviderSetupDialog
        provider="anki"
        open={ankiHelpOpen}
        onDismiss={() => setAnkiHelpOpen(false)}
      />

      {/* Work in progress is on screen, so leaving is worth one question — never a grade, never a
          discard, and never a question about a step that has already finished. */}
      {confirmLeave && question != null && (
        <DialogShell
          title={question.title}
          onDismiss={() => setConfirmLeave(false)}
          actions={
            <>
              <TextAction
                theme={theme}
                label="Stay"
                onPress={() => setConfirmLeave(false)}
              />
              <TextAction theme={theme} label="Leave" onPress={leaveConfirmed} />
            </>
          }
        >
          <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{question.body}</Text>
        </DialogShell>
      )}
    </View>
  );
}

/** One text-only action, for the moves beside a surface's main one. */
function TextAction({
  theme,
  label,
  onPress,
}: {
  theme: Theme;
  label: string;
  onPress: () => void;
}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={styles.textAction}
    >
      <Text style={[typeScale.labelLarge, {color: theme.colors.accent}]}>{label}</Text>
    </PressableScale>
  );
}

/**
 * The read that precedes a step: the page is pushed before AnkiDroid's status or WaniKani's token has
 * answered, so it says what is being opened rather than drawing whatever step the reader left behind.
 */
function OpeningView({
  source,
  onBack,
}: {
  source: "anki" | "waniKani";
  onBack: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <SubHeader title={source === "anki" ? "Anki" : "WaniKani"} onBack={onBack} />
      <View
        style={[
          styles.centred,
          {paddingHorizontal: contentInset, paddingBottom: insets.bottom + spacing.l},
        ]}
      >
        <BusyRow
          message={source === "anki" ? "Opening Anki…" : "Reading WaniKani…"}
        />
      </View>
    </View>
  );
}

/** Review cannot run at all: what is missing, and the one thing that fixes it. */
function BlockedView({
  theme,
  failure,
  busy,
  help,
  onBack,
  onGrant,
}: {
  theme: Theme;
  failure: ReviewFailure;
  busy: boolean;
  /** The setup help's trigger, owned by the page so its dialog outlives this screen. */
  help: React.ReactNode;
  onBack: () => void;
  onGrant: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  const title =
    failure === "NOT_INSTALLED"
      ? "Anki is not installed"
      : failure === "NOT_SET_UP"
        ? "Anki setup needed"
        : failure === "PERMISSION"
          ? "Anki access needed"
          : "Review unavailable";
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      {/* The setup help stands on the blocked screen too: the reader who has not set AnkiDroid up is
          the one who needs the steps, and it is reachable whatever the failure is. */}
      <ScreenHeader title="Anki" onBack={onBack} trailing={help} />
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={stateBody(insets.bottom, contentInset)}
      >
        <StateBlock
          mark={<Icon name={failure === "PERMISSION" ? "key" : "error"} size={30} color={colors.onSurfaceVariant} />}
          title={title}
          body={failureMessage(failure)}
        >
          {failure === "NOT_INSTALLED" && (
            <FilledAction label="Download Anki" onPress={downloadAnkiDroid} />
          )}
          {failure === "PERMISSION" && (
            <FilledAction label="Allow access" disabled={busy} onPress={onGrant} />
          )}
          {failure === "NOT_SET_UP" && (
            <OutlinedButton label="Open Anki" onPress={openAnkiDroid} />
          )}
        </StateBlock>
      </ScrollView>
    </View>
  );
}

/** One card: its own rendered HTML, the kanji of the note's fields under it, and the actions
 *  pinned at the bottom. WaniKani opens in place: a kanji expands its details under the strip
 *  as a page of its own, and a radical inside opens one level deeper. */
function AnkiSession({
  theme,
  session,
  busy,
  expandedKanji,
  expandedRadical,
  onBack,
  onReveal,
  onGrade,
  onKanji,
  onRadical,
  onCloseDetails,
  onOpenSettings,
  referenceReload,
}: {
  theme: Theme;
  session: Extract<ReviewStep, {kind: "session"}>;
  busy: boolean;
  expandedKanji: string | null;
  expandedRadical: number | null;
  onBack: () => void;
  onReveal: () => void;
  onGrade: (ease: number) => void;
  onKanji: (character: string) => void;
  onRadical: (id: number) => void;
  onCloseDetails: () => void;
  /** The panels' connect action: this page's own route to WaniKani's settings, detour marked. */
  onOpenSettings?: () => void;
  /** Bumped when the reader returns to this page, so an open reference page looks again. */
  referenceReload: number;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const [footerHeight, setFooterHeight] = useState(0);
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  const card = session.card;
  // The same choice the reference pages read: a kanji tapped during review opens whichever
  // reference the reader selected, with no account needed for Remembering the Kanji.
  const kanjiReference = useKanjiReferenceSource();
  // The note's own sentence is the example source: it is already on the device, so the answer never
  // waits on a network and stays readable with no connection at all.
  // The corpus is the example source; the note's own sentence is the fallback for a word the
  // corpus does not carry. Both are on the device, so the answer never waits on a network.
  const storedSentence = session.revealed ? card.exampleSentence : null;
  const exampleTerm = session.revealed && card.answerFields != null
    ? card.answerFields.expression.trim()
    : null;
  const [expandedExamples, setExpandedExamples] = useState<{token: number; expanded: boolean} | null>(null);
  const examplesExpanded = expandedExamples?.token === session.token && expandedExamples.expanded;
  const [exampleState, setExampleState] = useState<{
    token: number;
    examples: GatenDict.SentenceExample[] | null;
  } | null>(null);

  useEffect(() => {
    if (exampleTerm == null || exampleTerm === "") return;
    let cancelled = false;
    setExampleState({token: session.token, examples: null});
    void GatenDict.lookupExamples(exampleTerm)
      .then((examples) => {
        if (!cancelled) setExampleState({token: session.token, examples});
      })
      .catch(() => {
        if (!cancelled) setExampleState({token: session.token, examples: []});
      });
    return () => {
      cancelled = true;
    };
  }, [exampleTerm, session.token]);
  const corpusExamples = exampleState?.token === session.token ? exampleState.examples : null;
  const showExamples = session.revealed && (exampleTerm != null || storedSentence != null);

  // The kanji the note's own fields hold, in first-occurrence order across all of them. The
  // strip is drawn after the reveal only, so a tap can never reveal part of the answer.
  const kanji = useMemo(() => {
    const seen = new Set<string>();
    return card.fields
      .flatMap((field) => distinctIdeographs(field))
      .filter((character) => {
        if (seen.has(character)) return false;
        seen.add(character);
        return true;
      });
  }, [card.fields]);

  // The rating just sent, from the card it was sent for on: a new card starts with nothing
  // flashing, and the sweep is half FLASH_MS fading in and half fading out.
  const [flashTint, setFlashTint] = useState<string | null>(null);
  const [flashColor, setFlashColor] = useState<string | null>(null);
  const flashAlpha = useRef(new Animated.Value(0)).current;

  // A new card starts with nothing flashing.
  useEffect(() => {
    setFlashTint(null);
    setFlashColor(null);
    flashAlpha.setValue(0);
  }, [session.token, flashAlpha]);

  useEffect(() => {
    if (flashTint == null) {
      Animated.timing(flashAlpha, {
        toValue: 0,
        duration: FLASH_MS / 2,
        easing: FLASH_EASING,
        useNativeDriver: true,
      }).start();
      return;
    }
    setFlashColor(flashTint);
    Animated.timing(flashAlpha, {
      toValue: FLASH_ALPHA,
      duration: FLASH_MS / 2,
      easing: FLASH_EASING,
      useNativeDriver: true,
    }).start(({finished}) => {
      if (finished) setFlashTint(null);
    });
  }, [flashTint, flashAlpha]);

  const scrollRef = useRef<ScrollView>(null);
  const scrollY = useRef(0);
  const stripY = useRef(0);
  const wasDetailsOpen = useRef<boolean | null>(null);

  // Opening brings the details into view; closing simply settles. Keyed on *whether* a kanji is
  // open, not which one, so switching between two kanji leaves the surface exactly where it is
  // while the skeleton holds the layout.
  const detailsOpen = expandedKanji != null;
  useEffect(() => {
    if (wasDetailsOpen.current === detailsOpen) return;
    wasDetailsOpen.current = detailsOpen;
    if (!detailsOpen) return;
    // The strip lands 8dp below the deck space's top, so the panel below it gets the room.
    // Already at or above that line scrolls nothing.
    const delta = stripY.current - scrollY.current - spacing.s;
    if (delta <= 0) return;
    // After the reveal (Collapsible's 220 ms), never during it: the page is still growing, and a
    // scroll clamped to the height it has right now would stop short of the details it was asked
    // to bring into view.
    const timer = setTimeout(() => {
      scrollRef.current?.scrollTo({y: scrollY.current + delta, animated: true});
    }, 240);
    return () => clearTimeout(timer);
  }, [detailsOpen]);

  const total = Math.max(1, session.total);
  const place = Math.min(session.answered + 1, total);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <SubHeader
        title={session.revealed ? "Answer" : card.deckName}
        right={`${place} of ${total}`}
        onBack={onBack}
      />
      <View style={{paddingHorizontal: contentInset}}>
        <ProgressLine value={place / total} />
      </View>
      {/* The deck space is one surface: the card, its kanji strip and the inline details scroll
          together. A one-word question sits in the middle of the screen; a tall card scrolls. */}
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        ref={scrollRef}
        style={styles.screen}
        onScroll={(e) => {
          scrollY.current = e.nativeEvent.contentOffset.y;
        }}
        scrollEventThrottle={16}
        contentContainerStyle={[styles.deckSpace, {paddingBottom: footerHeight + spacing.xl}]}
      >
        <View
          style={[
            styles.deckContent,
            {paddingHorizontal: session.revealed ? contentInset : spacing.m},
          ]}
        >
        {/* The card's own rendered HTML, straight on the screen's background: the note type's text
            is the content, and a surface of its own around it is a panel the reader has to read
            through. The box is only there for the flash to sit over. */}
        <View style={styles.cardBox}>
          {session.revealed && card.answerFields != null ? (
            <StructuredAnkiAnswer theme={theme} fields={card.answerFields} />
          ) : (
            <CardHtml theme={theme} card={card} revealed={session.revealed} />
          )}
          {/* The answer flash is drawn over the rendered card, never through it. */}
          {flashColor != null && (
            <Animated.View
              pointerEvents="none"
              style={[
                StyleSheet.absoluteFill,
                {backgroundColor: flashColor, opacity: flashAlpha},
              ]}
            />
          )}
        </View>

        {showExamples && (
          <ExampleSentences
            theme={theme}
            examples={corpusExamples}
            storedSentence={storedSentence}
            expanded={Boolean(examplesExpanded)}
            onToggle={() => setExpandedExamples({token: session.token, expanded: !examplesExpanded})}
          />
        )}

        {/* Answer side only: before the reveal there is no strip to tap. */}
        {session.revealed && (
          <View
            style={[styles.waniKaniSection, {borderTopColor: colors.outlineVariant}]}
            onLayout={(e) => {
              stripY.current = e.nativeEvent.layout.y;
            }}
          >
            <Text style={[typeScale.titleSmall, styles.waniKaniTitle, {color: colors.onSurface}]}>
              {kanjiReferenceLabel(kanjiReference)}
            </Text>
            <KanjiStrip
              characters={kanji}
              selected={expandedKanji}
              onKanji={onKanji}
              label={null}
            />
            {/* The details open by growing to their own height and closing by collapsing it, with
                the page inside fading in: the strip above is what was tapped, so nothing here
                moves sideways under it. */}
            <View style={styles.detailsTop}>
              <DetailsReveal open={expandedKanji != null} pageKey={expandedKanji ?? "none"}>
                {expandedKanji == null ? null : (
                  <View style={styles.detailsWidth}>
                    {kanjiReference === "rtk" ? (
                      // RTK has no radicals, so there is no level below its page to open.
                      <RtkKanjiPanel
                        character={expandedKanji}
                        showBack={false}
                        scrollable={false}
                        embedded
                      />
                    ) : (
                      <>
                        <WaniKaniPanel
                          target={{kind: "kanji", character: expandedKanji}}
                          showBack={false}
                          scrollable={false}
                          compact={false}
                          embedded
                          onRadical={onRadical}
                          onSettings={onOpenSettings}
                          reloadKey={referenceReload}
                        />
                        {/* One level deeper: the radical is a page opened inside the kanji's
                            page, so the kanji panel above stands still while it reveals. */}
                        <View style={styles.radicalSlot}>
                          <DetailsReveal
                            open={expandedRadical != null}
                            pageKey={String(expandedRadical)}
                          >
                            {expandedRadical == null ? null : (
                              <View style={styles.radicalIndent}>
                                <WaniKaniPanel
                                  target={{kind: "radical", id: expandedRadical}}
                                  showBack={false}
                                  scrollable={false}
                                  compact={false}
                                  embedded
                                  onRadical={onRadical}
                                  onSettings={onOpenSettings}
                                  reloadKey={referenceReload}
                                />
                              </View>
                            )}
                          </DetailsReveal>
                        </View>
                      </>
                    )}
                  </View>
                )}
              </DetailsReveal>
            </View>
          </View>
        )}
        </View>
      </ScrollView>

      {/* Pinned: the honest notes, then the one action the reader has right now. It carries the
          navigation-bar inset itself, so the row sits clear of the gesture band. */}
      <ActionFooter bottomInset={insets.bottom} onHeightChange={setFooterHeight}>
        {session.revealed && card.typedAnswer && (
          <>
            <Text style={[typeScale.bodySmall, styles.note, {color: colors.onSurfaceVariant}]}>
              This card expects typed input. Gaten shows the answer as Anki renders it, but the
              interactive comparison is not supported here, so rate it yourself.
            </Text>
            <View style={styles.gapS} />
          </>
        )}
        {/* A card this app cannot draw — one whose content is media — is complete in AnkiDroid, so
            the way there is offered exactly when there is something Gaten is not showing. */}
        {session.mediaFiles.length > 0 && (
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel="Open in Anki"
            onPress={openAnkiDroid}
            style={styles.mediaAction}
          >
            <Text style={[typeScale.labelMedium, {color: colors.accent}]}>
              This card has media. Open in Anki
            </Text>
          </PressableScale>
        )}
        {!session.revealed ? (
          // The reveal is an ordinary control: the flash answers the tap, and the tick every button
          // plays rides with it.
          <FilledAction label="Show answer" disabled={busy} onPress={onReveal} />
        ) : (
          <RatingBar
            labels={session.buttonLabels}
            enabled={!busy}
            onGrade={(ease) => {
              // The rating that goes back is also what the card flashes with: "Again" is
              // the one answer that means the card was not known.
              setFlashTint(ease === 1 ? colors.error : colors.accent);
              onGrade(ease);
            }}
          />
        )}
        {busy && (
          <View style={styles.gapS}>
            <BusyRow message="Saving to Anki" />
          </View>
        )}
      </ActionFooter>
    </View>
  );
}

/** The card's own HTML, drawn by AnkiDroid's renderer on Gaten's surface rather than AnkiDroid's
 *  white one. The view can do nothing but draw; the document is built once per side and cached
 *  by the native view, so recomposing never reloads the card. */
function CardHtml({
  theme,
  card,
  revealed,
}: {
  theme: Theme;
  card: {css: string; question: string; answer: string; ord: number};
  revealed: boolean;
}) {
  const {colors} = theme;
  const [height, setHeight] = useState<number | null>(null);
  const document = useMemo(
    () =>
      cardDocument(
        card.css,
        revealed ? card.answer : card.question,
        card.ord,
        colors.onSurface,
        colors.background,
        cardRule(!revealed, colors.outlineVariant),
        !revealed
      ),
    [
      card.css,
      card.answer,
      card.question,
      card.ord,
      colors.onSurface,
      colors.background,
      colors.outlineVariant,
      revealed,
    ]
  );
  return (
    <View accessible accessibilityLabel="Card">
      <GatenCardWebView
        document={document}
        backgroundColor={colors.background}
        mode="card"
        onCardHeight={setHeight}
        style={{width: "100%", height: height ?? 1}}
      />
    </View>
  );
}

/** The reserved host the card's fonts are served over; `GatenCardWebView.kt` answers it and nothing
 * else does — the WebView's network loads are off, so the name can never be resolved for real. */
const CARD_FONT_HOST = "https://fonts.gaten.invalid";

/** The bundled face per weight, matching `GatenCardWebView.FONT_RESOURCES` path for path. */
const CARD_FONT_FILES: Array<[weight: number, file: string]> = [
  [400, "plex-400.ttf"],
  [500, "plex-500.ttf"],
  [600, "plex-600.ttf"],
  [700, "plex-700.ttf"],
];

/**
 * The card is drawn in the app's own face, the same one every other string in the app uses. The
 * WebView cannot see the family React Native registered, so each weight is declared here over
 * [CARD_FONT_HOST] and served from the bundled resources; the family is then stated on the card's
 * elements with `!important`, beside the ink, so a note type's own `font-family` cannot win. Sizes,
 * line heights, layout, ruby and the note's own markup are still untouched.
 */
let cardFontCssCache: string | null = null;
function cardFontCss(): string {
  if (cardFontCssCache != null) return cardFontCssCache;
  const faces = CARD_FONT_FILES.map(
    ([weight, file]) =>
      `@font-face{font-family:"${APP_FONT}";font-style:normal;font-weight:${weight};` +
      `src:url("${CARD_FONT_HOST}/${file}") format("truetype")}`
  ).join("");
  cardFontCssCache = faces + `#content,#content *{font-family:"${APP_FONT}" !important}`;
  return cardFontCssCache;
}

/**
 * The card as a document: AnkiDroid's own shell (`<body class="card cardN">` around `#content`)
 * without its scripts and `file://` stylesheets. The app's own defaults come first, the note type's
 * CSS after them, and one strong ink-and-family rule after that.
 *
 * The card's canvas **and the ink of every element in it** are the theme's, never the note type's.
 * Every Anki template is written for a light page — the shipped default CSS is literally
 * `.card { … color: black; background-color: white; }` — and templates routinely colour their own
 * fields: this deck paints its expression nearly black and its sentence grey. A colour stated on a
 * descendant beats anything inherited, so stating the ink on the page's roots alone leaves the card
 * unreadable the moment the canvas follows the theme. The canvas is therefore [canvas] (the screen's
 * own `background`, so the card reads as text on the page it is already on rather than as a panel
 * floating over it) and the ink is stated on `#content` **and every element inside it**, after the
 * note type's CSS and with `!important`.
 *
 * Replacing a note type's own foreground colours is deliberate: readable text on the app's
 * background is worth more than a template's arbitrary palette. The ink, the face (see
 * [cardFontCss]) — and, on the question side only, the weight — are what is stated; sizes, line
 * heights, layout, ruby and the note's own markup are untouched, so a card's *structure* is
 * preserved exactly.
 *
 * The question's weight is stated for the same reason the answer's is drawn at
 * `typeScale.displayMedium`: the question and the revealed answer must read as the same word, and a
 * template is free to set its own weight (this deck's is lighter than the answer's headword). A
 * weight stated on the root alone would lose to a template's own rule on a descendant, so it is
 * stated on `#content` and every element inside it, after the note type's CSS and with `!important`.
 * The answer side states none: there the note type's own lettering stays its own, as it always has.
 * Backgrounds keep the narrower boundary
 * they had: the content root and its first wrapper are forced transparent, because that is where a
 * note type paints the page-like background meant to cover the card, while anything deeper keeps its
 * colour — a box drawn inside a card is the note type's own formatting, and the app cannot tell a
 * highlight from a page background it should have overridden.
 *
 * This is the move AnkiDroid's own night mode makes (`body.night_mode { color: white;
 * background-color: black }`), with the app's own ink and paper instead of black and applied to the
 * whole card rather than to its body element.
 */
function cardDocument(
  css: string,
  html: string,
  ord: number,
  onSurface: string,
  canvas: string,
  frontRule: string,
  frontWord: boolean
): string {
  return (
    '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    `<style>html,body,.card{background-color:${canvas} !important;margin:0;padding:0}` +
    "#content,#content>*{background-color:transparent !important}" +
    "body{overflow-wrap:break-word}" +
    // The card's own gutter is the screen's (`deckSpace`): a padding here as well would be a second
    // one inside it, so the document's content root carries none.
    `#content{padding:0}${frontRule}` +
    "img,video{max-width:100%}</style>" +
    `<style>${css}</style>` +
    // Appended *after* the note type's own CSS and `!important`, so it wins on every element of the
    // card, not just its root. A template written for a light page colours its fields itself — this
    // deck paints its expression nearly black and its sentence grey — and a colour stated on a
    // descendant beats anything inherited, which is why stating the ink on the roots alone left the
    // card unreadable once the canvas followed the theme. The ink, the family, and — on the
    // question side only — the weight are stated; sizes, line heights, layout, ruby, backgrounds
    // and the note's own markup are left alone.
    `<style>#content,#content *{color:${onSurface} !important}` +
    (frontWord
      ? `#content,#content *{font-weight:${typeScale.displayMedium.fontWeight} !important}`
      : "") +
    `${cardFontCss()}</style></head>` +
    `<body class="card card${ord + 1}"><div id="content" dir="auto">${stripMediaTags(html)}</div></body></html>`
  );
}

/**
 * AnkiDroid renders `[sound:…]` and the modern `[anki:play:…]` tags as players; without its
 * media handling they would otherwise show up as their own source text in the middle of the card.
 * The app cannot play the files either (AnkiDroid shares no media), so the tag goes away and the
 * note under the card already says why.
 */
function stripMediaTags(html: string): string {
  return html.replace(/\[(?:sound|anki:play):[^\]]*\]/g, "");
}

/**
 * The example sentences the note itself carries, shown as quiet rows behind one accessible
 * disclosure. It reads straight from the note, so it never needs a connection.
 */
function ExampleSentences({
  theme,
  examples,
  storedSentence,
  expanded,
  onToggle,
}: {
  theme: Theme;
  examples: GatenDict.SentenceExample[] | null;
  storedSentence: string | null;
  expanded: boolean;
  onToggle: () => void;
}) {
  const {colors} = theme;
  const corpus = examples ?? [];
  const loading = examples === null && storedSentence == null;
  // The note's own sentence is the fallback for a word the corpus does not carry.
  const noteLines = corpus.length === 0 && storedSentence != null
    ? displayAnkiField(storedSentence).split("\n").map((line) => line.trim()).filter((line) => line !== "")
    : [];

  return (
    <View style={styles.examples}>
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel="Example sentences"
        accessibilityState={{expanded}}
        onPress={onToggle}
        style={[styles.examplesDisclosure, {borderColor: colors.outlineVariant}]}
      >
        <Text style={[typeScale.titleSmall, styles.examplesTitle, {color: colors.onSurface}]}>Example sentences</Text>
        <DisclosureChevron expanded={expanded} color={colors.onSurfaceVariant} />
      </PressableScale>
      <Collapsible expanded={expanded}>
        <View>
          {loading && (
            <Text style={[typeScale.bodySmall, styles.exampleState, {color: colors.onSurfaceVariant}]}>
              Finding examples…
            </Text>
          )}
          {corpus.map((example, index) => (
            <View key={index} style={[styles.exampleRow, {borderBottomColor: colors.outlineVariant}]}>
              <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>{example.japanese}</Text>
              <Text style={[typeScale.bodySmall, styles.exampleEnglish, {color: colors.onSurfaceVariant}]}>
                {example.english}
              </Text>
            </View>
          ))}
          {noteLines.map((line, index) => (
            <View key={index} style={[styles.exampleRow, {borderBottomColor: colors.outlineVariant}]}>
              <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>{line}</Text>
            </View>
          ))}
          {!loading && corpus.length === 0 && noteLines.length === 0 && (
            <Text style={[typeScale.bodySmall, styles.exampleState, {color: colors.onSurfaceVariant}]}>
              No example sentences found for this word.
            </Text>
          )}
        </View>
      </Collapsible>
    </View>
  );
}

function StructuredAnkiAnswer({
  theme,
  fields,
}: {
  theme: Theme;
  fields: {expression: string; reading: string; meaning: string};
}) {
  const {colors} = theme;
  const [glossHtml, posHtml] = fields.meaning.split(/<br\s*\/?><small>/i, 2);
  const partOfSpeech = posHtml?.replace(/<\/small>.*$/i, "") ?? "";
  return (
    <View accessible accessibilityLabel={`${displayAnkiField(fields.expression)}, ${displayAnkiField(fields.reading)}, ${displayAnkiField(glossHtml ?? "")}`}>
      <Text style={[typeScale.displayMedium, styles.answerHeadword, {color: colors.onSurface}]}>
        {displayAnkiField(fields.expression)}
      </Text>
      <Text style={[typeScale.readingLine, styles.answerHeadword, {color: colors.onSurfaceVariant}]}>
        {displayAnkiField(fields.reading)}
      </Text>
      <ReferenceSection title="Meaning" topGap={spacing.l}>
        <ReferenceValue>{displayAnkiField(glossHtml ?? "")}</ReferenceValue>
        {partOfSpeech.trim() !== "" && (
          <Text
            style={[
              typeScale.bodySmall,
              {color: colors.onSurfaceVariant, marginTop: spacing.xs},
            ]}
          >
            {displayAnkiField(partOfSpeech)}
          </Text>
        )}
      </ReferenceSection>
    </View>
  );
}

function displayAnkiField(value: string): string {
  return value
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<\/?small\s*>/gi, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .trim();
}

/**
 * The question keeps its existing display treatment. The answer uses the app's body size and
 * natural document height so imported templates that set a page-sized card do not push the
 * WaniKani supplement away from the answer. Descendant sizing and media layout remain template-owned.
 *
 * The question's *weight* is not stated here: a rule this early loses to the note type's own CSS, so
 * it is stated later with `!important` (see [cardDocument]), where the ink and the face already win.
 */
function cardRule(frontWord: boolean, divider: string): string {
  if (!frontWord) {
    return `html,body,.card{height:auto !important;min-height:0 !important}` +
      `#content{height:auto !important;min-height:0 !important;font-size:${typeScale.bodyMedium.fontSize}px;` +
      `line-height:${typeScale.bodyMedium.lineHeight / typeScale.bodyMedium.fontSize};font-weight:400}` +
      `#content hr#answer{border:0;border-top:1px solid ${divider};margin:${spacing.s}px 0}`;
  }
  const face = typeScale.displayMedium;
  return `#content{text-align:center;font-size:${face.fontSize}px;line-height:${face.lineHeight / face.fontSize}}`;
}

/** Nothing is due: the deck is finished for now, which is not the same as a failure. */
function DoneView({
  theme,
  deckName,
  busy,
  onFinish,
}: {
  theme: Theme;
  deckName: string;
  busy: boolean;
  onFinish: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <SubHeader title={deckName} onBack={onFinish} />
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={stateBody(insets.bottom, contentInset)}
      >
        <StateBlock
          mark={<Icon name="check_circle" size={34} color={colors.success} />}
          title="All caught up"
          body={`Nothing is due in ${deckName}.`}
        >
          <FilledAction label="Back to Review" disabled={busy} onPress={onFinish} />
        </StateBlock>
      </ScrollView>
    </View>
  );
}

/** WaniKani could not be read: what happened, and the one action that can follow it. */
function WaniKaniFailedView({
  theme,
  step,
  busy,
  onBack,
  onRetry,
}: {
  theme: Theme;
  step: Extract<ReviewStep, {kind: "wkFailed"}>;
  busy: boolean;
  onBack: () => void;
  onRetry: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <SubHeader title="WaniKani" onBack={onBack} />
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={stateBody(insets.bottom, contentInset)}
      >
        <StateBlock
          mark={<Icon name="cloud_off" size={30} color={colors.onSurfaceVariant} />}
          title="Reviews unavailable"
          body={WANIKANI_FAILURE_LABELS[step.failure]}
          note={
            step.retryAfterSeconds == null
              ? undefined
              : `WaniKani asked Gaten to wait ${step.retryAfterSeconds} seconds before asking again.`
          }
        >
          <FilledAction label="Try again" disabled={busy} onPress={onRetry} />
        </StateBlock>
      </ScrollView>
    </View>
  );
}

/** Something went wrong inside Anki: what it was, and the one action that can follow it. */
function FailedView({
  theme,
  step,
  busy,
  onBack,
  onRetry,
  onChangeDeck,
}: {
  theme: Theme;
  step: Extract<ReviewStep, {kind: "failed"}>;
  busy: boolean;
  onBack: () => void;
  onRetry: () => void;
  onChangeDeck: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <SubHeader title="Anki" onBack={onBack} />
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={stateBody(insets.bottom, contentInset)}
      >
        <StateBlock
          mark={<Icon name="error" size={30} color={colors.error} />}
          title="Something went wrong"
          body={failureMessage(step.failure, step.detail)}
          note={
            step.afterGrade
              ? "Anki may or may not have saved that answer, so Gaten will not send it again."
              : undefined
          }
        >
          <FilledAction
            label={step.afterGrade ? "Next card" : "Try again"}
            disabled={busy}
            onPress={onRetry}
          />
          {step.failure === "PROVIDER" && (
            <OutlinedButton label="Open Anki" onPress={openAnkiDroid} />
          )}
          <OutlinedButton label="Change deck" disabled={busy} onPress={onChangeDeck} />
        </StateBlock>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  centred: {...CONTENT_COLUMN, flexGrow: 1, alignItems: "center", justifyContent: "center"},
  textAction: {
    minHeight: 48,
    paddingHorizontal: spacing.m,
    alignItems: "center",
    justifyContent: "center",
  },
  // The reading surface starts at the top of the step area and grows with the card. Nothing centres
  // it: revealing the answer then adds to what is already on screen, below it, instead of moving
  // the question the reader is looking at — which is what "in place" means here. A taller card
  // simply scrolls.
  deckSpace: {
    paddingTop: spacing.m,
  },
  deckContent: CONTENT_COLUMN,
  // The card's own box, so the answer flash has something to cover. No surface, no radius and no
  // padding of its own: the note type's text sits directly on the screen's background, and the
  // WebView measures itself, so the box is exactly as tall as the card.
  cardBox: {marginTop: spacing.m, width: "100%"},
  detailsTop: {marginTop: spacing.s},
  detailsWidth: {width: "100%"},
  radicalSlot: {marginTop: spacing.s},
  radicalIndent: {marginStart: spacing.xl},
  note: {marginBottom: spacing.s},
  mediaAction: {minHeight: 40, justifyContent: "center", marginBottom: spacing.s},
  examples: {marginTop: spacing.l},
  examplesDisclosure: {
    minHeight: 56,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderBottomWidth: 1,
  },
  examplesTitle: {flex: 1},
  // The body is clipped and driven by the animated height; the inner view keeps its natural height
  // so `onLayout` reports what to open to, even while the outer frame is shut.
  exampleState: {marginTop: spacing.s},
  exampleRow: {paddingVertical: spacing.s, borderBottomWidth: 1},
  exampleEnglish: {marginTop: spacing.xs},
  waniKaniSection: {marginTop: spacing.xl},
  waniKaniTitle: {marginBottom: spacing.s},
  // The answer's headword block mirrors the question side, which centres the front word: the
  // expression and its reading are centred together, and the meaning below stays left-aligned.
  answerHeadword: {textAlign: "center"},
  gapS: {height: spacing.s},
});
