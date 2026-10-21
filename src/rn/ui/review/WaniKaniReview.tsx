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
 * wrong one is counted against that part, shows the answer, and asks for it to be typed before the
 * subject moves on, which is WaniKani's own rule. The review is submitted when every part of the
 * subject is done, never when a field is merely typed into.
 *
 * A radical that has no character is drawn from WaniKani's own image of it, fetched through the same
 * reference lookup the kanji pages use and cached on the device. That is the only way such a subject
 * can be asked about at all — its name is the answer, so the name cannot be the prompt.
 */
import React from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {KeyboardAvoidingView} from "react-native-keyboard-controller";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {
  ActionFooter,
  AnswerInput,
  BusyRow,
  Feedback,
  FilledAction,
  ProgressLine,
  SubHeader,
} from "./review-ui";
import {RadicalDrawing} from "./WaniKaniRadical";
import {WANIKANI_FAILURE_LABELS, WANIKANI_TYPE_LABELS} from "./wanikani-labels";
import type {WaniKaniSession} from "./review-store";

export function WaniKaniReview({
  session,
  busy,
  onBack,
  onEntry,
  onCheck,
  onRetrySubmit,
}: {
  session: WaniKaniSession;
  busy: boolean;
  onBack: () => void;
  onEntry: (text: string) => void;
  onCheck: () => void;
  onRetrySubmit: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const item = session.items[session.index];
  const reading = session.part === "reading";
  const submitting = session.phase === "submitting";
  const failed = session.phase === "submitFailed";

  if (item == null) return null;

  const characters = item.characters;
  // A single kanji gets the larger hero step; words use a wrapped display size that leaves room
  // for longer vocabulary on narrow screens.
  const single = characters != null && [...characters].length === 1;
  const progress = session.doneParts / Math.max(1, session.totalParts);
  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView
        style={styles.screen}
        behavior="padding"
      >
        <View style={{paddingHorizontal: contentInset}}>
          <SubHeader
            title="WaniKani"
            right={`${session.index + 1} of ${session.items.length}`}
            onBack={onBack}
          />
          <ProgressLine value={progress} />
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={styles.question}
          keyboardShouldPersistTaps="handled"
        >
          <View style={[styles.reviewContent, {paddingHorizontal: contentInset}]}>
            <View style={styles.hero}>
              <Text
                accessibilityRole="header"
                accessibilityLabel={`${WANIKANI_TYPE_LABELS[item.type]} · ${reading ? "Reading" : "Meaning"}`}
                style={typeScale.labelMedium}
              >
                <Text style={{color: colors.onSurfaceVariant}}>{WANIKANI_TYPE_LABELS[item.type]} · </Text>
                <Text style={{color: reading ? colors.reviewReading : colors.reviewMeaning}}>
                  {reading ? "Reading" : "Meaning"}
                </Text>
              </Text>
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
            </View>
            <View style={styles.answerField}>
              <AnswerInput
                key={`${session.index}:${session.part}`}
                value={session.entry}
                onChangeText={onEntry}
                placeholder=""
                selectionColor={colors.accent}
                editable={!busy && !submitting && !failed}
                autoFocus
                autoCapitalize="none"
                autoCorrect={false}
                spellCheck={false}
                returnKeyType="done"
                submitBehavior="submit"
                accessibilityLabel={reading ? "Reading" : "Meaning"}
                onSubmitEditing={() => {
                  if (session.entry.trim() !== "") onCheck();
                }}
              />
              {session.entry === "" && (
                <View
                  pointerEvents="none"
                  accessible={false}
                  accessibilityElementsHidden
                  importantForAccessibility="no"
                  style={styles.answerPlaceholderOverlay}
                >
                  <Text style={[styles.answerPlaceholder, {color: colors.onSurfaceVariant}]}>
                    {reading ? "Type the reading" : "Type the meaning"}
                  </Text>
                </View>
              )}
            </View>
            <Text style={[styles.microNote, {color: colors.onSurfaceVariant}]}>
              {reading ? "Japanese reading · kana or romaji" : "Answer in English"}
            </Text>
            {session.feedback != null && (
              <Feedback tone={session.feedback.tone} text={session.feedback.text} />
            )}
            {failed && (
              <Text
                accessibilityLiveRegion="polite"
                style={[styles.failure, {color: colors.error}]}
              >
                {session.authRejected
                  ? "WaniKani refused the saved token, so this review was not saved. Reconnect and start the queue again."
                  : `This review was not saved: ${
                      session.failure == null ? "WaniKani could not be reached" : WANIKANI_FAILURE_LABELS[session.failure]
                    }. Sending it again is safe — WaniKani records a review once.`}
              </Text>
            )}
          </View>
        </ScrollView>
        <ActionFooter bottomInset={insets.bottom}>
          {failed ? (
            <FilledAction
              label={session.authRejected ? "Reconnect" : "Send again"}
              disabled={busy}
              onPress={session.authRejected ? onBack : onRetrySubmit}
            />
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
      </KeyboardAvoidingView>
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
  reviewContent: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  hero: {alignItems: "center", marginTop: spacing.xl, marginBottom: spacing.xl},
  prompt: {textAlign: "center", marginTop: spacing.m, width: "100%"},
  promptSingle: {fontSize: 64, lineHeight: 80, fontWeight: "500"},
  promptWord: {fontSize: 40, lineHeight: 56, fontWeight: "500"},
  answerField: {position: "relative", width: "100%"},
  answerPlaceholderOverlay: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  answerPlaceholder: {fontSize: 17, lineHeight: 20, textAlign: "center"},
  microNote: {fontSize: 12, lineHeight: 17, marginTop: spacing.s, textAlign: "center"},
  failure: {fontSize: 12, lineHeight: 18, marginTop: spacing.m},
  busy: {marginTop: spacing.s},
});
