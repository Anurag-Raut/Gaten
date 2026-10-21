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
import React, {useEffect, useMemo, useState} from "react";
import {
  KeyboardAvoidingView,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import {GatenCardWebView} from "../../native/GatenCardWebView";
import {svgDocument} from "../wanikani/WaniKaniPanel";
import {spacing} from "../tokens";
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
import type {WaniKaniSession} from "./review-store";
import type {WaniKaniFailure, WaniKaniReviewItem} from "../../native/GatenWaniKani";

/** What each subject kind is called in the prompt's topic line. */
const TYPE_LABELS: Record<WaniKaniReviewItem["type"], string> = {
  radical: "Radical",
  kanji: "Kanji",
  vocabulary: "Vocabulary",
  kana_vocabulary: "Kana vocabulary",
};

/** Why a submission failed, in the same words the rest of the app uses for WaniKani. */
const FAILURE_LABELS: Record<WaniKaniFailure, string> = {
  OFFLINE: "no connection",
  TIMEOUT: "the request timed out",
  RATE_LIMITED: "WaniKani is limiting requests just now",
  SERVER: "WaniKani reported a server error",
  TOO_LARGE: "the answer was too large to keep",
  MALFORMED: "WaniKani sent something Gaten could not read",
  STORAGE: "this device could not save the answer",
};

/**
 * The drawing of a radical WaniKani has no character for. It comes from the same lookup the kanji
 * pages use, so it is cached after the first time; while it is on its way the prompt is simply empty
 * rather than a word the reader is being asked for.
 */
function RadicalDrawing({subjectId}: {subjectId: number}) {
  const {colors, mode} = useTheme();
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setSvg(null);
    void GatenWaniKani.lookupRadical(subjectId)
      .then((result) => {
        if (cancelled) return;
        setSvg(
          result.status === "available" && result.subject.kind === "radical"
            ? result.subject.svg ?? null
            : null
        );
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [subjectId]);

  const document = useMemo(
    () => (svg == null ? null : svgDocument(svg, mode === "Dark")),
    [svg, mode]
  );

  if (document == null) return <View style={styles.drawing} />;
  return (
    <View style={styles.drawing} accessibilityLabel="Radical">
      <GatenCardWebView
        document={document}
        backgroundColor={colors.background}
        mode="svg"
        style={styles.drawing}
      />
    </View>
  );
}

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
  // One character takes the design's own large step; a word takes the reading step, because a
  // three-kana vocabulary at 99 dp would not fit the width it has.
  const single = characters != null && [...characters].length === 1;
  const progress = session.doneParts / Math.max(1, session.totalParts);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <View style={{paddingHorizontal: contentInset}}>
          <SubHeader
            title="WaniKani"
            right={`${session.index + 1} of ${session.items.length}`}
            onBack={onBack}
          />
          <ProgressLine value={progress} />
        </View>
        <ScrollView
          style={styles.screen}
          contentContainerStyle={[styles.question, {paddingHorizontal: contentInset}]}
          keyboardShouldPersistTaps="handled"
        >
          <Text style={[styles.topic, {color: colors.onSurfaceVariant}]}>
            {`${TYPE_LABELS[item.type]} · ${reading ? "Reading" : "Meaning"}`}
          </Text>
          {characters == null ? (
            <RadicalDrawing subjectId={item.subjectId} />
          ) : (
            <Text
              style={[
                styles.prompt,
                single ? styles.promptSingle : styles.promptWord,
                {color: colors.onSurface},
              ]}
            >
              {characters}
            </Text>
          )}
          <AnswerInput
            key={`${session.index}:${session.part}`}
            value={session.entry}
            onChangeText={onEntry}
            placeholder={reading ? "Type the reading" : "Type the meaning"}
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
          <Text style={[styles.microNote, {color: colors.fine}]}>
            {reading ? "Answer in kana or romaji" : "Answer in English"}
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
                    session.failure == null ? "WaniKani could not be reached" : FAILURE_LABELS[session.failure]
                  }. Sending it again is safe — WaniKani records a review once.`}
            </Text>
          )}
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
    alignItems: "center",
    justifyContent: "center",
    paddingTop: 18,
    paddingBottom: 30,
  },
  topic: {fontSize: 12, fontWeight: "600"},
  prompt: {fontWeight: "700", letterSpacing: -1, textAlign: "center", marginTop: 27, marginBottom: 10},
  promptSingle: {fontSize: 99, lineHeight: 119, fontWeight: "500"},
  promptWord: {fontSize: 34, lineHeight: 51},
  drawing: {width: 99, height: 99, marginTop: 22, marginBottom: 8},
  microNote: {fontSize: 11, lineHeight: 16, marginTop: spacing.m, textAlign: "center"},
  failure: {fontSize: 12, lineHeight: 18, marginTop: spacing.m, textAlign: "center"},
  busy: {marginTop: spacing.s},
});
