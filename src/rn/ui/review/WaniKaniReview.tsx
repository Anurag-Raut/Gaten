/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * One WaniKani review in progress: the subject, the part of it being asked, and the field the answer
 * goes into.
 *
 * The screen is the queue's own shape. A subject asks for its meaning, and for its reading too when
 * WaniKani quizzes one — radicals and kana vocabulary are asked for their meaning alone, because
 * that is all WaniKani records for them. A part is finished when the right answer has been typed: a
 * wrong one is counted against that part, shows the answer in red, and asks for it to be typed before
 * the subject moves on, which is WaniKani's own rule; a right one shows in green and moves straight
 * on, as WaniKani's own flow does. That is why a wrong answer stops on its own card behind a Continue:
 * the button only clears the field for the retry, and the next check — not the button — finishes the
 * part. The review is submitted when every part of the subject is done, never when a field is typed.
 *
 * A radical that has no character is drawn from WaniKani's own image of it, fetched through the same
 * reference lookup the kanji pages use and cached on the device. That is the only way such a subject
 * can be asked about at all — its name is the answer, so the name cannot be the prompt.
 */
import React, {useEffect, useMemo, useRef, useState} from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from "react-native";
import {KeyboardAvoidingView} from "react-native-keyboard-controller";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {APP_FONT, CONTENT_COLUMN, contentInsetFor, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {Collapsible, DisclosureChevron} from "../Collapsible";
import {PressableScale} from "../PressableScale";
import {
  ActionFooter,
  AnswerInput,
  AnswerShake,
  BusyRow,
  Feedback,
  FilledAction,
  ProgressLine,
  SubHeader,
} from "./review-ui";
import {SubjectBadge, TaskLabel} from "./wanikani-card-ui";
import {RadicalDrawing} from "./WaniKaniRadical";
import {WANIKANI_FAILURE_LABELS} from "./wanikani-labels";
import {romajiToHiragana} from "./wanikani-romaji";
import {reviewCue} from "./wanikani-cue";
import {useJudgedAnswer} from "./useJudgedAnswer";
import type {WaniKaniReviewItem} from "../../native/GatenWaniKani";
import type {WaniKaniSession} from "./review-store";
import {DETAIL_TAB_LABELS, defaultDetailTab, type DetailTab} from "./wanikani-detail-tabs";

/**
 * The expanded answer behind "Show answer": the meaning or reading the subject carries, its mnemonic,
 * or its example sentences. Only what the subject actually has is offered, so a radical never grows a
 * fabricated reading and a subject without examples never grows a context tab.
 */
function ReviewDetails({item, tab}: {item: WaniKaniReviewItem; tab: DetailTab}) {
  const {colors} = useTheme();
  if (tab === "reading") {
    return (
      <View style={styles.detailBlock}>
        <Text style={[styles.detailLabel, {color: colors.onSurfaceVariant}]}>Primary reading</Text>
        <Text style={[styles.detailJapanese, {color: colors.onSurface}]}>{item.primaryReading ?? ""}</Text>
        {item.readingMnemonic !== "" && (
          <>
            <Text style={[styles.detailLabel, styles.detailLabelSpaced, {color: colors.onSurfaceVariant}]}>
              Mnemonic
            </Text>
            <Text style={[styles.detailText, {color: colors.onSurface}]}>{item.readingMnemonic}</Text>
          </>
        )}
      </View>
    );
  }
  if (tab === "context") {
    return (
      <View style={styles.detailBlock}>
        {item.contextSentences.map((sentence, index) => (
          <View key={index} style={index > 0 ? styles.contextSpaced : undefined}>
            <Text style={[styles.detailText, {color: colors.onSurface}]}>{sentence.japanese}</Text>
            <Text style={[styles.contextEnglish, {color: colors.onSurfaceVariant}]}>{sentence.english}</Text>
          </View>
        ))}
      </View>
    );
  }
  // What WaniKani marks primary is the one big line of this tab, wearing the same hero lettering the
  // primary reading does; every other accepted meaning follows in the quiet ink, so a word with a
  // dozen of them still reads as one answer.
  const alternates = item.meanings.filter((meaning) => meaning !== item.primaryMeaning);
  return (
    <View style={styles.detailBlock}>
      <Text style={[styles.detailLabel, {color: colors.onSurfaceVariant}]}>Primary meaning</Text>
      <Text style={[styles.detailJapanese, {color: colors.onSurface}]}>{item.primaryMeaning}</Text>
      {alternates.length > 0 && (
        <>
          <Text style={[styles.detailLabel, styles.detailLabelSpaced, {color: colors.onSurfaceVariant}]}>
            Other meanings
          </Text>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
            {alternates.join(" · ")}
          </Text>
        </>
      )}
      {item.meaningMnemonic !== "" && (
        <>
          <Text style={[styles.detailLabel, styles.detailLabelSpaced, {color: colors.onSurfaceVariant}]}>
            Mnemonic
          </Text>
          <Text style={[styles.detailText, {color: colors.onSurface}]}>{item.meaningMnemonic}</Text>
        </>
      )}
    </View>
  );
}

export function WaniKaniReview({
  session,
  busy,
  onBack,
  onEntry,
  onCheck,
  onContinue,
  onRetrySubmit,
}: {
  session: WaniKaniSession;
  busy: boolean;
  onBack: () => void;
  onEntry: (text: string) => void;
  onCheck: () => void;
  onContinue: () => void;
  onRetrySubmit: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const [footerHeight, setFooterHeight] = useState(0);
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  const item = session.items[session.index];
  const reading = session.part === "reading";
  const submitting = session.phase === "submitting";
  const reviewed = session.phase === "reviewed";
  const correct = session.phase === "correct";
  const failed = session.phase === "submitFailed";

  const [revealed, setRevealed] = useState(false);
  const [activeTab, setActiveTab] = useState<DetailTab>("meaning");
  const inputRef = useRef<TextInput>(null);

  // A new part or a new subject starts with the answer hidden; the reader opens `Show answer` when
  // they want it, and a wrong answer never opens it for them. When they do open it, the details open
  // on the part being asked — Reading for a reading prompt — and a manual tab choice then holds
  // until the next subject or part resets the panel.
  useEffect(() => {
    setRevealed(false);
    setActiveTab(defaultDetailTab(session.part, item));
  }, [session.index, session.part]);
  // Continue re-arms the same card; the field it cleared is the one the reader now needs.
  useEffect(() => {
    if (session.phase === "answering" && session.retrying) inputRef.current?.focus();
  }, [session.phase, session.retrying]);
  // The answer's own cue, fired on the judgement and not on the renders that follow it; the key it
  // returns is what the answer field's shake is pinned to. A refused reading is not a judgement, but
  // it earns the same shake and the same buzz: the tap did not go through.
  const shakeKey = useJudgedAnswer(reviewCue, session);

  const tabs = useMemo<DetailTab[]>(() => {
    const list: DetailTab[] = ["meaning"];
    if (item != null && item.readings.length > 0) list.push("reading");
    if (item != null && item.contextSentences.length > 0) list.push("context");
    return list;
  }, [item]);

  if (item == null) return null;

  const characters = item.characters;
  // A single kanji gets the larger hero step; words use a wrapped display size that leaves room
  // for longer vocabulary on narrow screens.
  const single = characters != null && [...characters].length === 1;
  const progress = session.doneParts / Math.max(1, session.totalParts);
  // The typed answer wears the outcome: red on the wrong answer held for its retry, green on a
  // correct one while it is sent. The underline beneath it stays the action accent either way.
  const answerColor = reviewed
    ? colors.error
    : session.feedback?.tone === "right"
      ? colors.success
      : colors.onSurface;
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <SubHeader
          title="WaniKani"
          right={`${session.cursor + 1} of ${session.totalParts}`}
          onBack={onBack}
        />
        <View style={{paddingHorizontal: contentInset}}>
          <ProgressLine value={progress} height={3} />
        </View>
        {/* The footer sits under the keyboard while the reader types, so a save in flight is stated
            above the scrolling card instead: it stays on screen at any scroll position and the
            keyboard cannot cover it. The next prompt replaces it. */}
        {submitting && (
          <View style={[styles.pending, {paddingHorizontal: contentInset}]}>
            <BusyRow message="Saving to WaniKani" />
          </View>
        )}
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={[styles.question, {paddingBottom: footerHeight + spacing.xl}]}
          keyboardShouldPersistTaps="handled"
        >
          <View style={[styles.reviewContent, {paddingHorizontal: contentInset}]}>
            <SubjectBadge type={item.type} level={item.level} />
            <View style={styles.hero}>
              {characters == null ? (
                <RadicalDrawing subjectId={item.subjectId} />
              ) : (
                <Text
                  numberOfLines={1}
                  adjustsFontSizeToFit
                  style={[
                    styles.prompt,
                    single ? styles.promptSingle : styles.promptWord,
                    {color: colors.onSurface},
                  ]}
                >
                  {characters}
                </Text>
              )}
              <View style={styles.taskPillWrap}>
                <TaskLabel task={reading ? "reading" : "meaning"} />
              </View>
            </View>
            <View style={styles.answerField}>
              {/* A judged answer holds the card with the answer in the field: the input stays editable
                  while it is held, while the retry a wrong one asks for is typed, and while the answer
                  is sent, and Enter continues from either stop instead of checking again. The submit
                  handler consumes the key's action without blurring, so focus — and the keyboard the
                  IME chooses to leave up — survive the judgement. */}
              <AnswerShake shakeKey={shakeKey}>
                <AnswerInput
                  ref={inputRef}
                  key={`${session.index}:${session.part}`}
                  value={session.entry}
                  onChangeText={(text) => onEntry(reading ? romajiToHiragana(text) : text)}
                  placeholder={reading ? "こたえ" : "Type the meaning"}
                  selectionColor={colors.accent}
                  editable={session.phase !== "submitFailed"}
                  autoFocus
                  autoCapitalize="none"
                  autoCorrect={false}
                  spellCheck={false}
                  returnKeyType="done"
                  submitBehavior="submit"
                  accessibilityLabel={reading ? "Reading answer" : "Meaning answer"}
                  style={{color: answerColor}}
                  onSubmitEditing={() => {
                    if (correct || reviewed) onContinue();
                    else if (session.phase === "answering" && session.entry.trim() !== "") onCheck();
                  }}
                />
              </AnswerShake>
            </View>
            {session.entryRefused && (
              <Text accessibilityLiveRegion="polite" style={[styles.refused, {color: colors.onSurfaceVariant}]}>
                Remove invalid characters
              </Text>
            )}
            {session.feedback != null && (
              <Feedback tone={session.feedback.tone} text={session.feedback.text} />
            )}
            {session.feedback != null && (
              <>
                <PressableScale
                  accessibilityRole="button"
                  accessibilityState={{expanded: revealed}}
                  accessibilityLabel={revealed ? "Hide answer" : "Show answer"}
                  onPress={() => setRevealed((open) => !open)}
                  style={[styles.revealRow, {borderTopColor: colors.outlineVariant}]}
                >
                  <Text style={[styles.revealLabel, {color: colors.onSurface}]}>
                    {revealed ? "Hide answer" : "Show answer"}
                  </Text>
                  <DisclosureChevron expanded={revealed} size={20} color={colors.onSurfaceVariant} />
                </PressableScale>
                <Collapsible expanded={revealed}>
                  {tabs.length > 1 && (
                    <View style={[styles.tabRow, {borderBottomColor: colors.outlineVariant}]}>
                      {tabs.map((tab) => {
                        const selected = tab === activeTab;
                        return (
                          <PressableScale
                            key={tab}
                            accessibilityRole="tab"
                            accessibilityState={{selected}}
                            onPress={() => setActiveTab(tab)}
                            style={styles.tabCell}
                          >
                            <Text
                              style={[
                                styles.tabLabel,
                                {
                                  color: selected ? colors.onSurface : colors.onSurfaceVariant,
                                  fontWeight: selected ? "700" : "500",
                                },
                              ]}
                            >
                              {DETAIL_TAB_LABELS[tab]}
                            </Text>
                            {selected && <View style={[styles.tabUnderline, {backgroundColor: colors.accent}]} />}
                          </PressableScale>
                        );
                      })}
                    </View>
                  )}
                  <ReviewDetails item={item} tab={activeTab} />
                </Collapsible>
              </>
            )}
            {failed && (
              <Text accessibilityLiveRegion="polite" style={[styles.failure, {color: colors.error}]}>
                {session.authRejected
                  ? "WaniKani refused the saved token, so this review was not saved. Reconnect and start the queue again."
                  : `This review was not saved: ${
                      session.failure == null ? "WaniKani could not be reached" : WANIKANI_FAILURE_LABELS[session.failure]
                    }. Sending it again is safe — WaniKani records a review once.`}
              </Text>
            )}
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
      {/* Keep the action beneath the keyboard; its Done key submits the answer. */}
      <ActionFooter bottomInset={insets.bottom} onHeightChange={setFooterHeight}>
        {failed ? (
          <FilledAction
            label={session.authRejected ? "Reconnect" : "Send again"}
            disabled={busy}
            onPress={session.authRejected ? onBack : onRetrySubmit}
          />
        ) : reviewed || correct ? (
          <FilledAction label="Continue" onPress={onContinue} />
        ) : (
          <FilledAction
            label="Check answer"
            disabled={busy || submitting || session.entry.trim() === ""}
            onPress={onCheck}
          />
        )}
      </ActionFooter>
    </View>
  );
}

/** Lettering of the card's big hero character; the primary reading echoes it so they can't drift. */
const HERO_LETTERING = {fontWeight: "500", fontFamily: APP_FONT} as const;

const styles = StyleSheet.create({
  screen: {flex: 1},
  question: {
    flexGrow: 1,
    paddingTop: spacing.xl,
    paddingBottom: spacing.xl,
  },
  reviewContent: CONTENT_COLUMN,
  hero: {alignItems: "center", marginTop: spacing.m, marginBottom: spacing.m},
  prompt: {textAlign: "center", marginTop: spacing.m, width: "100%"},
  promptSingle: {fontSize: 64, lineHeight: 80, ...HERO_LETTERING},
  promptWord: {fontSize: 40, lineHeight: 56, ...HERO_LETTERING},
  taskPillWrap: {marginTop: spacing.m},
  answerField: {position: "relative", width: "100%", marginTop: spacing.s},
  revealRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    minHeight: 52,
    borderTopWidth: 1,
  },
  revealLabel: {fontSize: 13, lineHeight: 18, fontWeight: "700", fontFamily: APP_FONT},
  refused: {fontSize: 12, lineHeight: 18, marginTop: spacing.s, fontFamily: APP_FONT},
  tabRow: {flexDirection: "row", alignItems: "stretch", minHeight: 50, borderBottomWidth: 1},
  tabCell: {flex: 1, minWidth: 0, alignItems: "center", justifyContent: "center"},
  tabLabel: {fontSize: 12, lineHeight: 16, fontFamily: APP_FONT},
  tabUnderline: {position: "absolute", bottom: 0, width: "70%", height: 3, borderRadius: 2},
  detailBlock: {paddingVertical: spacing.l},
  detailLabel: {fontSize: 12, lineHeight: 16, fontWeight: "700", fontFamily: APP_FONT},
  detailLabelSpaced: {marginTop: spacing.l},
  detailText: {fontSize: 14, lineHeight: 23, marginTop: spacing.xs, fontFamily: APP_FONT},
  detailJapanese: {fontSize: 27, lineHeight: 34, marginTop: spacing.xs, ...HERO_LETTERING},
  contextSpaced: {marginTop: spacing.l},
  contextEnglish: {fontSize: 12, lineHeight: 17, marginTop: spacing.xs, fontFamily: APP_FONT},
  failure: {fontSize: 12, lineHeight: 18, marginTop: spacing.m, fontFamily: APP_FONT},
  pending: {marginTop: spacing.m},
});
