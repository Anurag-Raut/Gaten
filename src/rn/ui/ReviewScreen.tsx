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
import ReanimatedAnimated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import * as Anki from "../native/GatenAnki";
import * as GatenDict from "../native/GatenDict";
import * as GatenHaptics from "../native/GatenHaptics";
import * as GatenWaniKani from "../native/GatenWaniKani";
import {GatenCardWebView} from "../native/GatenCardWebView";
import {distinctIdeographs, Icon, KanjiStrip} from "./components";
import {PressableScale} from "./PressableScale";
import {InkIcon} from "./InkIcon";
import {MAX_CONTENT_WIDTH, PAGE_EASING_REANIMATED, spacing, typeScale} from "./tokens";
import {useTheme, type Theme} from "./theme";
import {DialogShell, OutlinedButton} from "./settings/settings-components";
import {WaniKaniPanel} from "./wanikani/WaniKaniPanel";
import {stepDepth, type ReviewFailure, type ReviewStep} from "./review/review-store";
import {reviewActions, useReview} from "./review/useReview";
import {StepTransition} from "./review/StepTransition";
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

/** The example disclosure opening or closing: one emphasized beat for the body's height and the
 * chevron's quarter turn together. */
const EXAMPLES_TOGGLE_MS = 220;

/** The curve a press — and the flash's half-sweep — answers with. */
const FLASH_EASING = Easing.bezier(0.4, 0, 0.2, 1);

/** The line for an AnkiDroid failure, without any of the provider's internals. */
function failureMessage(failure: ReviewFailure, detail?: string): string {
  if (failure === "PROVIDER" && detail != null) {
    return `AnkiDroid could not do that: ${detail}`;
  }
  switch (failure) {
    case "NOT_INSTALLED":
      return "AnkiDroid is not installed, so there is nothing here to review.";
    case "PERMISSION":
      return "Gaten needs AnkiDroid's database access to read and answer cards.";
    case "NOT_SET_UP":
      return "AnkiDroid has not set up its collection yet. Open AnkiDroid once and come back.";
    case "NOT_FOUND":
      return "That card is no longer in AnkiDroid.";
    case "MALFORMED":
      return "AnkiDroid sent something Gaten could not read.";
    default:
      return "AnkiDroid could not complete that request.";
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

/** The review flow's page, as the shell mounts it. `onExit` is the pop that leaves it. */
export function ReviewScreen({onExit}: {onExit: () => void}) {
  const theme = useTheme();
  const {colors} = theme;
  const review = useReview();
  const isFocused = useIsFocused();
  const [confirmLeave, setConfirmLeave] = useState(false);
  const step = review.step;

  /**
   * Back from this screen. With a card on screen it asks first, and confirming goes back to the
   * picker, as does a card that failed: leaving a session is not leaving the deck. Every other step
   * is the page's own pop — the deck list, WaniKani's connection screen and its queue were all opened
   * from Home, and Back returns there. Nothing unsent is lost by any of it: Anki grades only what was
   * rated, and a WaniKani review is sent when a subject is finished.
   */
  const leave = useCallback(() => {
    if (review.busy) return;
    switch (review.step.kind) {
      case "done":
        reviewActions.changeDeck();
        return;
      case "session":
        setConfirmLeave(true);
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

  // Back is guarded only where leaving is a decision: a card on screen, or a failure that belongs to
  // a deck the reader is still in. Every other step is the page's own pop, which the stack already
  // performs — a handler there would only take the platform's Back away and hand it straight back.
  useEffect(() => {
    if (!isFocused) return;
    const insideADeck =
      review.step.kind === "session" || review.step.kind === "done" ||
      (review.step.kind === "failed" && review.deck != null);
    if (!insideADeck) return;
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      leave();
      return true;
    });
    return () => subscription.remove();
  }, [isFocused, review.step.kind, review.deck, leave]);

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
            <AnkiDeckPicker decks={[]} busy onBack={onExit} onStart={() => {}} />
          ) : <OpeningView source={step.source} onBack={onExit} />
        ) : step.kind === "blocked" ? (
          <BlockedView
            theme={theme}
            failure={step.failure}
            busy={review.busy}
            onBack={leave}
            onGrant={requestAnkiPermission}
          />
        ) : step.kind === "decks" ? (
          <AnkiDeckPicker
            decks={step.decks}
            filteredEmpty={step.filteredEmpty}
            completedDeckIds={step.completedDeckIds}
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
            onGrade={(ease) => {
              // Submitting a rating changes the state of the card, so it ticks — the same tick a
              // chip's selection gets, not a firmer one.
              void GatenHaptics.play("tick").catch(() => {});
              reviewActions.grade(step, ease);
            }}
            onKanji={(character) => reviewActions.toggleKanji(character)}
            onRadical={(id) => {
              // One tick per *selection*: opening a radical the reader was not already on.
              // Tapping the open one closes it, and a close is not a selection.
              if (id !== review.expandedRadical) {
                void GatenHaptics.play("tick").catch(() => {});
              }
              reviewActions.toggleRadical(id);
            }}
            onCloseDetails={() => reviewActions.closeDetails()}
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
            onToggleNote={() => reviewActions.wkToggleNote()}
            onNoteEntry={(field, text) => reviewActions.wkNoteEntry(field, text)}
            onSaveNote={() => reviewActions.wkSaveNote()}
            onRetrySubmit={() => reviewActions.wkRetrySubmit()}
          />
        ) : step.kind === "wkLesson" ? (
          <WaniKaniLesson
            session={step.session}
            busy={review.busy}
            onBack={leave}
            onNext={() => reviewActions.lessonNext()}
            onEntry={(text) => reviewActions.lessonEntry(text)}
            onCheck={() => reviewActions.lessonCheck()}
            onToggleNote={() => reviewActions.lessonToggleNote()}
            onNoteEntry={(field, text) => reviewActions.lessonNoteEntry(field, text)}
            onSaveNote={() => reviewActions.lessonSaveNote()}
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

      {/* A card is on screen, so leaving is worth one question — never a grade, never a discard. */}
      {confirmLeave && (
        <DialogShell
          title="Leave the review session?"
          onDismiss={() => setConfirmLeave(false)}
          actions={
            <>
              <TextAction
                theme={theme}
                label="Stay"
                onPress={() => setConfirmLeave(false)}
              />
              <TextAction
                theme={theme}
                label="Leave"
                onPress={() => {
                  setConfirmLeave(false);
                  // Back to the deck list, not out of the review flow: the reader still has
                  // decks to review, and the card they walked away from is not theirs to grade.
                  reviewActions.changeDeck();
                }}
              />
            </>
          }
        >
          <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>
            Nothing is graded by leaving. The card you are on stays unanswered, and you go back to
            the deck list.
          </Text>
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
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title={source === "anki" ? "Anki" : "WaniKani"} onBack={onBack} />
      </View>
      <View
        style={[
          styles.centred,
          {paddingHorizontal: contentInset, paddingBottom: insets.bottom + spacing.l},
        ]}
      >
        <BusyRow
          message={source === "anki" ? "Opening AnkiDroid…" : "Reading WaniKani…"}
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
  onBack,
  onGrant,
}: {
  theme: Theme;
  failure: ReviewFailure;
  busy: boolean;
  onBack: () => void;
  onGrant: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const title =
    failure === "NOT_INSTALLED"
      ? "AnkiDroid is not installed"
      : failure === "NOT_SET_UP"
        ? "AnkiDroid is not set up"
        : failure === "PERMISSION"
          ? "AnkiDroid access is needed"
          : "Review unavailable";
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="Anki" onBack={onBack} />
      </View>
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
          {failure === "PERMISSION" && (
            <FilledAction label="Allow AnkiDroid access" disabled={busy} onPress={onGrant} />
          )}
          {failure === "NOT_SET_UP" && (
            <OutlinedButton label="Open AnkiDroid" onPress={openAnkiDroid} />
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
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const card = session.card;
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
    if (delta > 0) {
      scrollRef.current?.scrollTo({y: scrollY.current + delta, animated: true});
    }
  }, [detailsOpen]);

  const total = Math.max(1, session.total);
  const place = Math.min(session.answered + 1, total);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader
          title={session.revealed ? "Answer" : card.deckName}
          right={`${place} of ${total}`}
          onBack={onBack}
        />
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
        contentContainerStyle={styles.deckSpace}
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
              WaniKani kanji
            </Text>
            <KanjiStrip
              characters={kanji}
              selected={expandedKanji}
              onKanji={onKanji}
              label={null}
              haptic="onOpen"
            />
            {/* The details move as pages, with the slide every other page change uses; the one
                thing animated besides the slide is the size, so the strip above never jumps. */}
            <View style={styles.detailsTop}>
              <StepTransition
                pageKey={expandedKanji ?? "none"}
                forward={expandedKanji != null}
                sizing="wrap"
              >
                {expandedKanji == null ? null : (
                  <View style={styles.detailsWidth}>
                    <WaniKaniPanel
                      target={{kind: "kanji", character: expandedKanji}}
                      showBack={false}
                      scrollable={false}
                      compact={false}
                      embedded
                      onRadical={onRadical}
                    />
                    {/* One level deeper: the radical is a page opened inside the kanji's
                        page, so the kanji panel above stands still while it slides in. */}
                    <View style={styles.radicalSlot}>
                      <StepTransition
                        pageKey={String(expandedRadical ?? "none")}
                        forward={expandedRadical != null}
                        sizing="wrap"
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
                            />
                          </View>
                        )}
                      </StepTransition>
                    </View>
                  </View>
                )}
              </StepTransition>
            </View>
          </View>
        )}
        </View>
      </ScrollView>

      {/* Pinned: the honest notes, then the one action the reader has right now. It carries the
          navigation-bar inset itself, so the row sits clear of the gesture band. */}
      <ActionFooter bottomInset={insets.bottom}>
        {session.revealed && card.typedAnswer && (
          <>
            <Text style={[typeScale.bodySmall, styles.note, {color: colors.onSurfaceVariant}]}>
              This card expects typed input. Gaten shows the answer as AnkiDroid renders it, but the
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
            accessibilityLabel="Open in AnkiDroid"
            onPress={openAnkiDroid}
            style={styles.mediaAction}
          >
            <Text style={[typeScale.labelMedium, {color: colors.accent}]}>
              This card has media. Open in AnkiDroid
            </Text>
          </PressableScale>
        )}
        {!session.revealed ? (
          // The reveal is an ordinary tap: the flash is what it gives back, so it gets no tick.
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
            <BusyRow message="Saving to AnkiDroid…" />
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
        cardRule(!revealed, colors.outlineVariant)
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

/**
 * The card as a document: AnkiDroid's own shell (`<body class="card cardN">` around `#content`)
 * without its scripts and `file://` stylesheets. The app's own defaults come first, the note type's
 * CSS after them, and one strong ink rule after that.
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
 * background is worth more than a template's arbitrary palette, and only `color` is stated — fonts,
 * weights, sizes, line heights, layout, ruby and the note's own markup are untouched, so a card's
 * *structure* is preserved exactly. Backgrounds keep the narrower boundary they had: the content
 * root and its first wrapper are forced transparent, because that is where a note type paints the
 * page-like background meant to cover the card, while anything deeper keeps its colour — a box drawn
 * inside a card is the note type's own formatting, and the app cannot tell a highlight from a page
 * background it should have overridden.
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
  frontRule: string
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
    // card unreadable once the canvas followed the theme. Only `color` is stated: fonts, weights,
    // sizes, layout, ruby and the note's own markup are untouched, and backgrounds are left alone.
    `<style>#content,#content *{color:${onSurface} !important}</style></head>` +
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
  const reducedMotion = useReducedMotion();
  // One driver for the whole disclosure: the body's height and fade, and the chevron's quarter turn.
  const progress = useSharedValue(expanded ? 1 : 0);
  // The body is measured while it is clipped shut, so opening animates to its true height even
  // before the examples have arrived.
  const contentHeight = useSharedValue(0);
  const corpus = examples ?? [];
  const loading = examples === null && storedSentence == null;
  // The note's own sentence is the fallback for a word the corpus does not carry.
  const noteLines = corpus.length === 0 && storedSentence != null
    ? displayAnkiField(storedSentence).split("\n").map((line) => line.trim()).filter((line) => line !== "")
    : [];

  useEffect(() => {
    if (reducedMotion) {
      progress.value = expanded ? 1 : 0;
      return;
    }
    progress.value = withTiming(expanded ? 1 : 0, {
      duration: EXAMPLES_TOGGLE_MS,
      easing: PAGE_EASING_REANIMATED,
    });
  }, [expanded, reducedMotion, progress]);

  const bodyStyle = useAnimatedStyle(() => ({
    height: contentHeight.value * progress.value,
    opacity: progress.value,
  }));

  const chevronStyle = useAnimatedStyle(() => ({
    transform: [{rotate: `${progress.value * 90}deg`}],
  }));

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
        <ReanimatedAnimated.View style={chevronStyle}>
          <InkIcon name="chevron" size={16} color={colors.onSurfaceVariant} />
        </ReanimatedAnimated.View>
      </PressableScale>
      <ReanimatedAnimated.View
        style={[styles.examplesBody, bodyStyle]}
        pointerEvents={expanded ? "auto" : "none"}
        accessibilityElementsHidden={!expanded}
        importantForAccessibility={expanded ? "auto" : "no-hide-descendants"}
      >
        <View
          style={styles.examplesBodyInner}
          onLayout={(e) => {
            contentHeight.value = e.nativeEvent.layout.height;
          }}
        >
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
      </ReanimatedAnimated.View>
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
      <Text style={[typeScale.displayMedium, {color: colors.onSurface}]}>
        {displayAnkiField(fields.expression)}
      </Text>
      <Text style={[styles.answerReading, {color: colors.onSurfaceVariant}]}>
        {displayAnkiField(fields.reading)}
      </Text>
      <Text style={[styles.answerMeaning, {color: colors.onSurface}]}>
        {displayAnkiField(glossHtml ?? "")}
      </Text>
      {partOfSpeech.trim() !== "" && (
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
          {displayAnkiField(partOfSpeech)}
        </Text>
      )}
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
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title={deckName} onBack={onFinish} />
      </View>
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
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="WaniKani" onBack={onBack} />
      </View>
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
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="Anki" onBack={onBack} />
      </View>
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
              ? "AnkiDroid may or may not have saved that answer, so Gaten will not send it again."
              : undefined
          }
        >
          <FilledAction
            label={step.afterGrade ? "Next card" : "Try again"}
            disabled={busy}
            onPress={onRetry}
          />
          {step.failure === "PROVIDER" && (
            <OutlinedButton label="Open AnkiDroid" onPress={openAnkiDroid} />
          )}
          <OutlinedButton label="Change deck" disabled={busy} onPress={onChangeDeck} />
        </StateBlock>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  centred: {
    flexGrow: 1,
    alignItems: "center",
    justifyContent: "center",
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
  },
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
  deckContent: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
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
  examplesBody: {overflow: "hidden"},
  examplesBodyInner: {position: "absolute", top: 0, left: 0, right: 0},
  exampleState: {marginTop: spacing.s},
  exampleRow: {paddingVertical: spacing.s, borderBottomWidth: 1},
  exampleEnglish: {marginTop: spacing.xs},
  waniKaniSection: {marginTop: spacing.xl},
  waniKaniTitle: {marginBottom: spacing.s},
  answerReading: {marginTop: spacing.xs, fontSize: 17, lineHeight: 24, fontWeight: "400"},
  answerMeaning: {marginTop: spacing.m, fontSize: 17, lineHeight: 24, fontWeight: "400", fontFamily: "Inter"},
  gapS: {height: spacing.s},
});
