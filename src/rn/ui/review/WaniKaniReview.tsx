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
import {CONTENT_COLUMN, contentInsetFor, spacing} from "../tokens";
import {useTheme} from "../theme";
import {Collapsible, DisclosureChevron} from "../Collapsible";
import {PressableScale} from "../PressableScale";
import {
  ActionFooter,
  AnswerInput,
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
import type {WaniKaniReviewItem} from "../../native/GatenWaniKani";
import type {WaniKaniSession} from "./review-store";

/** The answer details a review can show, in the order WaniKani itself teaches them. */
type DetailTab = "meaning" | "reading" | "context";

const DETAIL_TAB_LABELS: Record<DetailTab, string> = {
  meaning: "Meaning",
  reading: "Reading",
  context: "Context",
};

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
  return (
    <View style={styles.detailBlock}>
      <Text style={[styles.detailLabel, {color: colors.onSurfaceVariant}]}>Meaning</Text>
      <Text style={[styles.detailText, {color: colors.onSurface}]}>{item.meanings.join(", ")}</Text>
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
  const failed = session.phase === "submitFailed";

  const [revealed, setRevealed] = useState(false);
  const [activeTab, setActiveTab] = useState<DetailTab>("meaning");
  const inputRef = useRef<TextInput>(null);

  // A new part or a new subject starts with the answer hidden; the reader opens `Show answer` when
  // they want it, and a wrong answer never opens it for them.
  useEffect(() => {
    setRevealed(false);
    setActiveTab("meaning");
  }, [session.index, session.part]);
  // Continue re-arms the same card; the field it cleared is the one the reader now needs.
  useEffect(() => {
    if (session.phase === "answering" && session.retrying) inputRef.current?.focus();
  }, [session.phase, session.retrying]);

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
          right={`${session.index + 1} of ${session.items.length}`}
          onBack={onBack}
        />
        <View style={{paddingHorizontal: contentInset}}>
          <ProgressLine value={progress} height={3} />
        </View>
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
              <AnswerInput
                ref={inputRef}
                key={`${session.index}:${session.part}`}
                value={session.entry}
                onChangeText={(text) => onEntry(reading ? romajiToHiragana(text) : text)}
                placeholder={reading ? "こたえ" : "Type the meaning"}
                selectionColor={colors.accent}
                editable={!busy && session.phase === "answering"}
                autoFocus
                autoCapitalize="none"
                autoCorrect={false}
                spellCheck={false}
                returnKeyType="done"
                submitBehavior="submit"
                accessibilityLabel={reading ? "Reading answer" : "Meaning answer"}
                style={{color: answerColor}}
                onSubmitEditing={() => {
                  if (session.phase === "answering" && session.entry.trim() !== "") onCheck();
                }}
              />
            </View>
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
        ) : reviewed ? (
          <FilledAction label="Continue" onPress={onContinue} />
        ) : (
          <FilledAction
            label="Check answer"
            disabled={busy || submitting || session.entry.trim() === ""}
            onPress={onCheck}
          />
        )}
        {submitting && (
          <View style={styles.busy}>
            <BusyRow message="Saving to WaniKani…" />
          </View>
        )}
      </ActionFooter>
    </View>
  );
}

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
  promptSingle: {fontSize: 64, lineHeight: 80, fontWeight: "500"},
  promptWord: {fontSize: 40, lineHeight: 56, fontWeight: "500"},
  taskPillWrap: {marginTop: spacing.m},
  answerField: {position: "relative", width: "100%", marginTop: spacing.s},
  revealRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    minHeight: 52,
    borderTopWidth: 1,
  },
  revealLabel: {fontSize: 13, lineHeight: 18, fontWeight: "700"},
  tabRow: {flexDirection: "row", alignItems: "stretch", minHeight: 50, borderBottomWidth: 1},
  tabCell: {flex: 1, minWidth: 0, alignItems: "center", justifyContent: "center"},
  tabLabel: {fontSize: 12, lineHeight: 16},
  tabUnderline: {position: "absolute", bottom: 0, width: "70%", height: 3, borderRadius: 2},
  detailBlock: {paddingVertical: spacing.l},
  detailLabel: {fontSize: 12, lineHeight: 16, fontWeight: "700"},
  detailLabelSpaced: {marginTop: spacing.l},
  detailText: {fontSize: 14, lineHeight: 23, marginTop: spacing.xs},
  detailJapanese: {fontSize: 27, lineHeight: 34, marginTop: spacing.xs},
  contextSpaced: {marginTop: spacing.l},
  contextEnglish: {fontSize: 12, lineHeight: 17, marginTop: spacing.xs},
  failure: {fontSize: 12, lineHeight: 18, marginTop: spacing.m},
  busy: {marginTop: spacing.s},
});
