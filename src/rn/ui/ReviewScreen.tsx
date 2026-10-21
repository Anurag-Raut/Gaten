/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Reviews AnkiDroid's own decks inside Gaten, with AnkiDroid doing the scheduling: the queue, the
 * intervals on the rating buttons and the answer itself all come from AnkiDroid's content
 * provider, and Gaten keeps no progress of its own. The RN port of AnkiReviewScreen.kt; the
 * session's state lives in review-store, outside any component, so a tab switch keeps the card,
 * its timer and its open WaniKani details.
 *
 * The flow is AnkiDroid's: pick a deck (the configured Gaten deck preselected), see the question,
 * reveal the answer, rate it once. Each rating is sent once and only once; the next card always
 * comes from a fresh query. WaniKani rides on the answer side only, from the note's own raw
 * fields — the card the reader is being graded on cannot leak its answer through a kanji page.
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
} from "react-native";
import {
  NavigationProp,
  useIsFocused,
  useNavigation,
} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as Anki from "../native/GatenAnki";
import * as GatenHaptics from "../native/GatenHaptics";
import {GatenCardWebView} from "../native/GatenCardWebView";
import {distinctIdeographs, Icon, IconButton, KanjiStrip} from "./components";
import {PressableScale} from "./PressableScale";
import {BOTTOM_BAR_HEIGHT, spacing, typeScale} from "./tokens";
import {useTheme, type Theme} from "./theme";
import {DialogShell} from "./settings/settings-components";
import {WaniKaniPanel} from "./wanikani/WaniKaniPanel";
import type {RootTabParamList} from "./navigation";
import {type ReviewFailure, type ReviewStep} from "./review/review-store";
import {reviewActions, useAnkiReview} from "./review/useAnkiReview";
import {StepTransition} from "./review/StepTransition";
import {
  BusyRow,
  CardSideTag,
  CountTag,
  FilledAction,
  NoteCard,
  OutlinedAction,
  RatingBar,
  StateCard,
  TextAction,
} from "./review/review-ui";
import type {AnkiDeckResult} from "../native/GatenAnki";

/** How long a rating's answer flash lasts: half fading in, half fading out. */
const FLASH_MS = 250;

/** How opaque that tint gets: enough to read as an answer, not enough to hide the word under it. */
const FLASH_ALPHA = 0.18;

/** The curve a press — and the flash's half-sweep — answers with. */
const FLASH_EASING = Easing.bezier(0.4, 0, 0.2, 1);

/** The line for a failure, without any of the provider's internals. */
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

/** Opens AnkiDroid itself, for the media or the session this screen cannot show. */
function openAnkiDroid() {
  void Anki.openAnkiDroid().catch(() => {});
}

/** The Review tab's screen, as the shell mounts it. */
export function ReviewTab() {
  const theme = useTheme();
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const review = useAnkiReview();
  const navigation = useNavigation<NavigationProp<RootTabParamList>>();
  const isFocused = useIsFocused();
  const [confirmLeave, setConfirmLeave] = useState(false);

  const onClose = useCallback(() => {
    navigation.navigate("Home");
  }, [navigation]);

  /**
   * Back from this screen. Inside a deck it stays inside the review flow: with a card on screen
   * it asks first, and confirming goes back to the picker, as does a card that failed. While a
   * card is being read or an answer is being written, Back waits for it instead — the same
   * window the hidden bottom bar agrees with. Everything else leaves for Home at once.
   */
  const leave = useCallback(() => {
    if (review.busy && review.sessionActive) return;
    if (review.step.kind === "session") {
      setConfirmLeave(true);
      return;
    }
    if (review.step.kind === "failed" && review.sessionActive) {
      review.actions.changeDeck();
      return;
    }
    onClose();
  }, [review.busy, review.sessionActive, review.step, review.actions, onClose]);

  // Registered only while a card session is open and this screen is the one on top — an
  // outgoing scene can remain mounted through a transition; only the current page guards Back.
  useEffect(() => {
    if (!review.sessionActive || !isFocused) return;
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      leave();
      return true;
    });
    return () => subscription.remove();
  }, [review.sessionActive, isFocused, leave]);

  const requestAnkiPermission = useCallback(() => {
    void Anki.requestPermission()
      .then((granted) => {
        if (granted) reviewActions.openDecks();
        else reviewActions.permissionDenied();
      })
      .catch(() => reviewActions.permissionDenied());
  }, []);

  const step = review.step;

  return (
    <View style={[styles.screen, {backgroundColor: colors.background}]}>
      {/* The screen's own bar: deck name or the bare title, Back, and Change deck. */}
      <View
        style={[
          styles.topBar,
          {
            backgroundColor: colors.surface,
            // The Compose top bar is 64dp tall *below* the status bar. Padding alone would leave
            // the title centred in what is left of a fixed 64 — higher than the Kotlin's, and a
            // bar 16dp short of it.
            minHeight: 64 + insets.top,
            paddingTop: insets.top,
          },
        ]}
      >
        <BackButton theme={theme} onPress={leave} />
        <Text
          numberOfLines={1}
          ellipsizeMode="tail"
          style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1}]}
        >
          {review.deck?.name ?? "Review cards"}
        </Text>
        {review.deck != null && (
          <View style={styles.topBarAction}>
            <TextAction
              theme={theme}
              label="Change deck"
              disabled={review.busy}
              onPress={() => reviewActions.changeDeck()}
            />
          </View>
        )}
        {/* With a card on screen, one quiet way out to AnkiDroid itself: a note type this app
            cannot draw, or a card that is only media, is complete there and blank here. It is an
            action, not a warning — nothing is claimed about what the card holds. */}
        {review.step.kind === "session" && (
          <IconButton
            name="open_in_new"
            accessibilityLabel="Open in AnkiDroid"
            onPress={openAnkiDroid}
          />
        )}
      </View>

      {/* The step is drawn directly: no page slide and no animated size between the deck picker,
          the session and its states, so starting a deck is instant, revealing the answer changes
          nothing but the card, and a step is never remounted by a change of its own content. */}
      <View style={styles.flex}>
        {step.kind === "starting" ? (
          <View style={[styles.pagePadding, {paddingHorizontal: contentInset}]}>
            <StateCard theme={theme} spin icon="refresh" title="Reading AnkiDroid…" />
          </View>
        ) : step.kind === "blocked" ? (
          <BlockedView theme={theme} failure={step.failure} onGrant={requestAnkiPermission} />
        ) : step.kind === "decks" ? (
          <DeckPicker
            theme={theme}
            decks={step.decks}
            configured={step.configuredDeckName}
            busy={review.busy}
            onStart={(deck) => reviewActions.startDeck(deck)}
          />
        ) : step.kind === "session" ? (
          <SessionView
            theme={theme}
            session={step}
            busy={review.busy}
            expandedKanji={review.expandedKanji}
            expandedRadical={review.expandedRadical}
            onReveal={() => reviewActions.reveal()}
            onGrade={(ease) => {
              // Submitting a rating changes the state of the card, so it ticks — the same tick
              // a chip's selection gets, not a firmer one.
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
        ) : step.kind === "done" ? (
          <DoneView
            theme={theme}
            deckName={step.deckName}
            busy={review.busy}
            onFinish={onClose}
            onChangeDeck={() => reviewActions.changeDeck()}
          />
        ) : (
          <FailedView
            theme={theme}
            step={step}
            busy={review.busy}
            onRetry={() => reviewActions.retry()}
            onChangeDeck={() => reviewActions.changeDeck()}
          />
        )}
      </View>

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

/** The top bar's back arrow. */
function BackButton({theme, onPress}: {theme: Theme; onPress: () => void}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel="Back"
      onPress={onPress}
      style={styles.backButton}
    >
      <Icon name="arrow_back" color={theme.colors.onSurfaceVariant} />
    </PressableScale>
  );
}

/** Review cannot run at all: what is missing, and the one thing that fixes it. */
function BlockedView({
  theme,
  failure,
  onGrant,
}: {
  theme: Theme;
  failure: ReviewFailure;
  onGrant: () => void;
}) {
  const {width} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <ScrollView
      style={styles.flex}
      contentContainerStyle={[
        styles.pagePadding,
        {
          paddingHorizontal: contentInset,
          paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
        },
      ]}
    >
      <StateCard
        theme={theme}
        icon={failure === "PERMISSION" ? "key" : "error"}
        title="Review unavailable"
        body={failureMessage(failure)}
      >
        {failure === "PERMISSION" && (
          <FilledAction
            theme={theme}
            label="Allow AnkiDroid access"
            onPress={onGrant}
          />
        )}
        {failure === "NOT_SET_UP" && (
          <OutlinedAction
            theme={theme}
            label="Open AnkiDroid"
            onPress={openAnkiDroid}
          />
        )}
      </StateCard>
    </ScrollView>
  );
}

/**
 * The deck picker: every deck AnkiDroid reports, with its own counts, and the deck Gaten exports
 * to preselected. The counts are AnkiDroid's, not a promise about this session.
 */
function DeckPicker({
  theme,
  decks,
  configured,
  busy,
  onStart,
}: {
  theme: Theme;
  decks: AnkiDeckResult[];
  configured: string;
  busy: boolean;
  onStart: (deck: AnkiDeckResult) => void;
}) {
  const {colors} = theme;
  const {width} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  // The reader's own pick, once they have made one. Until then the selection is what the read
  // arrived with — the deck Gaten exports to, or the first — so the preselection is in place on the
  // picker's first frame rather than moving under the reader a tick later.
  const [chosenId, setChosenId] = useState<number | null>(null);
  const selected =
    decks.find((deck) => deck.id === chosenId) ??
    decks.find((deck) => deck.name === configured) ??
    decks[0] ??
    null;

  return (
    <View
      style={[
        styles.picker,
        {
          paddingHorizontal: width < 360 ? spacing.l : spacing.contentInset,
          paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
        },
      ]}
    >
      <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
        Choose a deck
      </Text>
      <Text
        style={[
          typeScale.bodyMedium,
          {color: colors.onSurfaceVariant, marginTop: spacing.s},
        ]}
      >
        AnkiDroid keeps the schedule. Choose a deck to review what is due now.
      </Text>
      {decks.length === 0 ? (
        <Text
          style={[
            typeScale.bodyMedium,
            {color: colors.onSurfaceVariant, marginTop: spacing.l},
          ]}
        >
          AnkiDroid has no decks yet.
        </Text>
      ) : (
        <>
          <ScrollView
            accessibilityRole="radiogroup"
            style={styles.flex}
            contentContainerStyle={{paddingTop: spacing.xl}}
          >
            {decks.map((deck) => (
              <DeckRow
                key={deck.id}
                theme={theme}
                deck={deck}
                selected={deck.id === selected?.id}
                configured={deck.name === configured}
                onSelect={() => {
                  // A selection *change*: tapping the deck already selected changes nothing, so
                  // it gives nothing back.
                  if (deck.id === selected?.id) return;
                  setChosenId(deck.id);
                  void GatenHaptics.play("tick").catch(() => {});
                }}
              />
            ))}
          </ScrollView>
          {/* The list and the action below it are 12dp apart, as the picker has always been. */}
          <View style={styles.gapM} />
          {/* The tap's own answer, while the chosen deck's first card is on its way: the button
              below is already disabled by then, so this is what says the screen is working rather
              than stuck. It sits above the button, which stays where the finger left it. */}
          {busy ? (
            <>
              <BusyRow theme={theme} message="Reading AnkiDroid…" />
              <View style={styles.gapS} />
            </>
          ) : null}
          <FilledAction
            theme={theme}
            label="Start reviewing"
            disabled={selected == null || busy}
            minHeight={52}
            onPress={() => {
              if (selected == null) return;
              onStart(selected);
            }}
          />
          <View style={styles.bottomGap} />
        </>
      )}
    </View>
  );
}

/**
 * One deck row: a radio, its name, AnkiDroid's own counts as tags, and — when it is the deck Gaten
 * exports to — what that means. The whole row is the target and states the radio role.
 */
function DeckRow({
  theme,
  deck,
  selected,
  configured,
  onSelect,
}: {
  theme: Theme;
  deck: AnkiDeckResult;
  selected: boolean;
  configured: boolean;
  onSelect: () => void;
}) {
  const {colors} = theme;
  return (
    <PressableScale
      accessibilityRole="radio"
      accessibilityLabel={deck.name}
      accessibilityState={{checked: selected, disabled: false}}
      onPress={onSelect}
      style={[
        styles.deckCard,
        {borderBottomColor: colors.outlineVariant},
      ]}
    >
      <View style={styles.deckHead}>
        <RadioDot theme={theme} selected={selected} />
        <Text
          numberOfLines={2}
          ellipsizeMode="tail"
          style={[
            typeScale.titleSmall,
            {
              color: colors.onSurface,
              flex: 1,
              marginStart: spacing.s,
            },
          ]}
        >
          {deck.name}
        </Text>
      </View>
      <View style={styles.tagRow}>
        <CountTag theme={theme} text={`${deck.newCount} new`} />
        <CountTag theme={theme} text={`${deck.learnCount} learning`} />
        <CountTag theme={theme} text={`${deck.reviewCount} to review`} />
        {deck.filtered && <CountTag theme={theme} text="filtered" />}
      </View>
      {configured && (
        <Text
          style={[
            typeScale.labelMedium,
            {color: colors.onSurfaceVariant, marginTop: spacing.m},
          ]}
        >
          Gaten exports here
        </Text>
      )}
    </PressableScale>
  );
}

/** The drawn radio, decoration over the row that is the real target. */
function RadioDot({theme, selected}: {theme: Theme; selected: boolean}) {
  const {colors} = theme;
  return (
    <View
      style={[
        styles.radioOuter,
        {borderColor: selected ? colors.accent : colors.onSurfaceVariant},
      ]}
    >
      {selected && (
        <View style={[styles.radioInner, {backgroundColor: colors.accent}]} />
      )}
    </View>
  );
}

/** One card: its own rendered HTML, the kanji of the note's fields under it, and the actions
 *  pinned at the bottom. WaniKani opens in place: a kanji expands its details under the strip
 *  as a page of its own, and a radical inside opens one level deeper. */
function SessionView({
  theme,
  session,
  busy,
  expandedKanji,
  expandedRadical,
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
  onReveal: () => void;
  onGrade: (ease: number) => void;
  onKanji: (character: string) => void;
  onRadical: (id: number) => void;
  onCloseDetails: () => void;
}) {
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const card = session.card;

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

  return (
    <View style={styles.flex}>
      {/* The deck space is one surface: the card, its kanji strip and the inline details scroll
          together. A one-word question sits in the middle of the screen; a tall card scrolls. */}
      <ScrollView
        ref={scrollRef}
        style={styles.flex}
        onScroll={(e) => {
          scrollY.current = e.nativeEvent.contentOffset.y;
        }}
        scrollEventThrottle={16}
        contentContainerStyle={styles.deckSpace}
      >
        <CardSideTag theme={theme} revealed={session.revealed} />
        {/* The card's own rendered HTML, straight on the screen's background: the note type's text
            is the content, and a surface of its own around it is a panel the reader has to read
            through. The box is only there for the flash to sit over. */}
        <View style={styles.cardBox}>
          <CardHtml theme={theme} card={card} revealed={session.revealed} />
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

        {/* Answer side only: before the reveal there is no strip to tap. */}
        {session.revealed && (
          <View
            onLayout={(e) => {
              stripY.current = e.nativeEvent.layout.y;
            }}
          >
            <KanjiStrip
              characters={kanji}
              selected={expandedKanji}
              onKanji={onKanji}
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
      </ScrollView>

      {/* Pinned: the honest notes, then the one action the reader has right now. It carries the
          navigation-bar inset itself, so the row sits clear of the gesture band. */}
      <View
        style={[
          styles.pinned,
          {
            backgroundColor: colors.surface,
            borderTopColor: colors.outlineVariant,
            paddingBottom: spacing.m + insets.bottom,
          },
        ]}
      >
        {session.revealed && card.typedAnswer && (
          <>
            <NoteCard
              theme={theme}
              text="This card expects typed input. Gaten shows the answer as AnkiDroid renders it, but the interactive comparison is not supported here, so rate it yourself."
            />
            <View style={styles.gapS} />
          </>
        )}
        {!session.revealed ? (
          // The reveal is an ordinary tap: the page slide and the flash are what it gives
          // back, so it gets no tick of its own.
          <FilledAction
            theme={theme}
            label="Show answer"
            disabled={busy}
            minHeight={52}
            onPress={onReveal}
          />
        ) : (
          <>
            <Text style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}>
              How did that go?
            </Text>
            <View style={styles.gapS} />
            <RatingBar
              theme={theme}
              labels={session.buttonLabels}
              enabled={!busy}
              onGrade={(ease) => {
                // The rating that goes back is also what the card flashes with: "Again" is
                // the one answer that means the card was not known.
                setFlashTint(ease === 1 ? colors.error : colors.accent);
                onGrade(ease);
              }}
            />
          </>
        )}
        {busy && (
          <>
            <View style={styles.gapS} />
            <BusyRow theme={theme} message="Saving to AnkiDroid…" />
          </>
        )}
      </View>
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
        frontRule(!revealed)
      ),
    [
      card.css,
      card.answer,
      card.question,
      card.ord,
      colors.onSurface,
      colors.background,
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
 * background-color: black }`), with the reader's palette instead of black and applied to the whole
 * card rather than to its body element.
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
 * The question side carries the word the reader is being asked about, so it takes the theme's
 * display step, written in the units this viewport is laid out in (dp) with the theme's own
 * sp-derived number: the WebView already applies the reader's font scale, so no second factor,
 * nothing caps it. Said on `#content` so a note type's `.card{font-size}` cannot outrank it;
 * the line height is the theme's own ratio, unitless, so it follows text scaling.
 */
function frontRule(frontWord: boolean): string {
  if (!frontWord) return "";
  const face = typeScale.displayMedium;
  return `#content{font-size:${face.fontSize}px;line-height:${face.lineHeight / face.fontSize}}`;
}

/** Nothing is due: the deck is finished for now, which is not the same as a failure. */
function DoneView({
  theme,
  deckName,
  busy,
  onFinish,
  onChangeDeck,
}: {
  theme: Theme;
  deckName: string;
  busy: boolean;
  onFinish: () => void;
  onChangeDeck: () => void;
}) {
  const {width} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  return (
    <ScrollView
      style={styles.flex}
      contentContainerStyle={[
        styles.pagePadding,
        {
          paddingHorizontal: contentInset,
          paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
        },
      ]}
    >
      <StateCard
        theme={theme}
        icon="check_circle"
        title="All caught up"
        body={`Nothing is due in ${deckName}.`}
      >
        <FilledAction theme={theme} label="Finish" onPress={onFinish} />
        <OutlinedAction
          theme={theme}
          label="Change deck"
          disabled={busy}
          onPress={onChangeDeck}
        />
      </StateCard>
    </ScrollView>
  );
}

/** Something went wrong: what it was, and the one action that can follow it. */
function FailedView({
  theme,
  step,
  busy,
  onRetry,
  onChangeDeck,
}: {
  theme: Theme;
  step: Extract<ReviewStep, {kind: "failed"}>;
  busy: boolean;
  onRetry: () => void;
  onChangeDeck: () => void;
}) {
  const {width} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const message = failureMessage(step.failure, step.detail);
  return (
    <ScrollView
      style={styles.flex}
      contentContainerStyle={[
        styles.pagePadding,
        {
          paddingHorizontal: contentInset,
          paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
        },
      ]}
    >
      <StateCard
        theme={theme}
        icon="error"
        tone="error"
        title="Something went wrong"
        body={message}
        note={
          step.afterGrade
            ? "AnkiDroid may or may not have saved that answer, so Gaten will not send it again."
            : undefined
        }
      >
        <FilledAction
          theme={theme}
          label={step.afterGrade ? "Next card" : "Retry"}
          disabled={busy}
          onPress={onRetry}
        />
        {step.failure === "PROVIDER" && (
          <OutlinedAction
            theme={theme}
            label="Open AnkiDroid"
            onPress={openAnkiDroid}
          />
        )}
        <OutlinedAction
          theme={theme}
          label="Change deck"
          disabled={busy}
          onPress={onChangeDeck}
        />
      </StateCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  flex: {flex: 1},
  topBar: {
    flexDirection: "row",
    alignItems: "center",
    // No height here: it is the status-bar inset plus the Compose bar's own 64dp, applied with
    // the inset in the screen (see the top bar's own comment).
    paddingStart: 4,
    paddingEnd: spacing.s,
  },
  topBarAction: {marginStart: spacing.s},
  backButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: "center",
    justifyContent: "center",
  },
  pagePadding: {paddingVertical: spacing.l, paddingHorizontal: spacing.contentInset},
  picker: {
    flex: 1,
    paddingHorizontal: spacing.contentInset,
    paddingTop: spacing.l,
  },
  deckCard: {
    borderBottomWidth: 1,
    paddingVertical: spacing.m,
    width: "100%",
  },
  deckHead: {flexDirection: "row", alignItems: "center"},
  tagRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: spacing.s,
    marginTop: spacing.m,
  },
  radioOuter: {
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
  },
  radioInner: {width: 10, height: 10, borderRadius: 5},
  bottomGap: {height: spacing.l},
  // The reading surface starts at the top of the step area and grows with the card. Nothing centres
  // it: revealing the answer then adds to what is already on screen, below it, instead of moving
  // the question the reader is looking at — which is what "in place" means here. A taller card
  // simply scrolls.
  deckSpace: {
    // The card's text is 12 dp from the screen edge, and the card document adds no gutter of its
    // own (`#content` is unpadded), so this is the whole gutter rather than half of a doubled one.
    paddingHorizontal: spacing.m,
    paddingTop: spacing.m,
  },
  // The card's own box, so the answer flash has something to cover. No surface, no radius and no
  // padding of its own: the note type's text sits directly on the screen's background, and the
  // WebView measures itself, so the box is exactly as tall as the card.
  cardBox: {marginTop: spacing.m, width: "100%"},
  detailsTop: {marginTop: spacing.s},
  detailsWidth: {width: "100%"},
  radicalSlot: {marginTop: spacing.s},
  radicalIndent: {marginStart: spacing.xl},
  pinned: {
    // The same 12 dp gutter the card's own text uses, so the actions line up with it.
    paddingHorizontal: spacing.m,
    paddingTop: spacing.m,
    width: "100%",
    // A hairline is all that separates the actions from the card above them.
    borderTopWidth: 1,
  },
  gapS: {height: spacing.s},
  gapM: {height: spacing.m},
});
