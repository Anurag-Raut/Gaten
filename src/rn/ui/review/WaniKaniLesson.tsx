/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * One WaniKani lesson batch: the subjects are taught one card at a time, then quizzed the same way a
 * review is. The two phases share one screen because they are one sitting - the reader is introduced
 * to a handful of subjects, then asked about each of them.
 *
 * The quiz is judged exactly like a review - the reading through the same converter, the meaning
 * case- and spacing-insensitively - but a finished subject is sent with `PUT /assignments/<id>/start`
 * rather than a review, which is what moves it into the review queue. Each subject is started the
 * moment its quiz is passed, so leaving mid-batch keeps what was already finished.
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
import {SubjectBadge, TaskLabel} from "./wanikani-card-ui";
import {RadicalDrawing} from "./WaniKaniRadical";
import {WANIKANI_FAILURE_LABELS} from "./wanikani-labels";
import type {WaniKaniLessonSession} from "./review-store";
import type {WaniKaniLessonItem} from "../../native/GatenWaniKani";

/** One titled block of the teach card: a caption and whatever it introduces. */
function LessonSection({title, children}: {title: string; children: React.ReactNode}) {
  const {colors} = useTheme();
  return (
    <View style={styles.section}>
      <Text
        accessibilityRole="header"
        style={[typeScale.labelMedium, styles.sectionTitle, {color: colors.onSurfaceVariant}]}
      >
        {title}
      </Text>
      {children}
    </View>
  );
}

/**
 * The teaching card: the character (or its image), its meanings and readings with their mnemonics, and
 * a couple of example sentences when WaniKani gives them. Nothing here is a question; the reader is
 * only being introduced.
 */
function TeachingCard({item}: {item: WaniKaniLessonItem}) {
  const {colors} = useTheme();
  const characters = item.characters;
  const single = characters != null && [...characters].length === 1;
  return (
    <>
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
      </View>
      <LessonSection title="Meaning">
        <Text style={[styles.answerLine, {color: colors.reviewMeaning}]}>
          {item.meanings.join(", ")}
        </Text>
        {item.meaningMnemonic !== "" && (
          <Text style={[typeScale.bodyMedium, styles.mnemonic, {color: colors.onSurfaceVariant}]}>
            {item.meaningMnemonic}
          </Text>
        )}
      </LessonSection>
      {item.readings.length > 0 && (
        <LessonSection title="Reading">
          <Text style={[styles.answerLine, {color: colors.reviewReading}]}>
            {item.readings.join(", ")}
          </Text>
          {item.readingMnemonic !== "" && (
            <Text style={[typeScale.bodyMedium, styles.mnemonic, {color: colors.onSurfaceVariant}]}>
              {item.readingMnemonic}
            </Text>
          )}
        </LessonSection>
      )}
      {item.contextSentences.length > 0 && (
        <LessonSection title="Examples">
          {item.contextSentences.slice(0, 2).map((sentence, index) => (
            <View key={index} style={styles.example}>
              <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>
                {sentence.japanese}
              </Text>
              <Text
                style={[typeScale.bodySmall, styles.exampleEnglish, {color: colors.onSurfaceVariant}]}
              >
                {sentence.english}
              </Text>
            </View>
          ))}
        </LessonSection>
      )}
    </>
  );
}

export function WaniKaniLesson({
  session,
  busy,
  onBack,
  onNext,
  onEntry,
  onCheck,
  onRetrySubmit,
}: {
  session: WaniKaniLessonSession;
  busy: boolean;
  onBack: () => void;
  onNext: () => void;
  onEntry: (text: string) => void;
  onCheck: () => void;
  onRetrySubmit: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  const teaching = session.phase === "teaching";
  const item = teaching ? session.batch[session.teachIndex] : session.batch[session.quizIndex];
  if (item == null) return null;

  const reading = session.part === "reading";
  const starting = session.phase === "starting";
  const failed = session.phase === "startFailed";
  // A correct quiz answer is shown in green while it is sent, matching the review's own result.
  const answerColor = session.feedback?.tone === "right" ? colors.success : colors.onSurface;
  const characters = item.characters;
  const single = characters != null && [...characters].length === 1;
  const progress = session.doneParts / Math.max(1, session.totalParts);
  const position = teaching ? session.teachIndex : session.quizIndex;
  const lastCard = teaching && session.teachIndex + 1 >= session.batch.length;

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <View style={{paddingHorizontal: contentInset}}>
          <SubHeader
            title="WaniKani"
            right={`${position + 1} of ${session.batch.length}`}
            onBack={onBack}
          />
          <ProgressLine value={progress} height={3} />
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={styles.body}
          keyboardShouldPersistTaps="handled"
        >
          <View style={[styles.content, {paddingHorizontal: contentInset}]}>
            {teaching ? (
              <TeachingCard item={item} />
            ) : (
              <>
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
                    key={`${session.quizIndex}:${session.part}`}
                    value={session.entry}
                    onChangeText={onEntry}
                    placeholder={reading ? "Type the reading" : "Type the meaning"}
                    selectionColor={colors.accent}
                    editable={!busy && !starting && !failed}
                    autoFocus
                    autoCapitalize="none"
                    autoCorrect={false}
                    spellCheck={false}
                    returnKeyType="done"
                    submitBehavior="submit"
                    accessibilityLabel={reading ? "Reading answer" : "Meaning answer"}
                    style={{color: answerColor}}
                    onSubmitEditing={() => {
                      if (session.entry.trim() !== "") onCheck();
                    }}
                  />
                </View>
                {session.feedback != null && (
                  <Feedback tone={session.feedback.tone} text={session.feedback.text} />
                )}
                {failed && (
                  <Text
                    accessibilityLiveRegion="polite"
                    style={[styles.failure, {color: colors.error}]}
                  >
                    {session.authRejected
                      ? "WaniKani refused the saved token, so this lesson was not saved. Reconnect and start the batch again."
                      : `This lesson was not saved: ${
                          session.failure == null
                            ? "WaniKani could not be reached"
                            : WANIKANI_FAILURE_LABELS[session.failure]
                        }. Sending it again is safe — starting an assignment twice is the same as starting it once.`}
                  </Text>
                )}
              </>
            )}
          </View>
        </ScrollView>
        <ActionFooter bottomInset={insets.bottom} style={[styles.footer, {paddingBottom: 15 + insets.bottom}]}>
          {teaching ? (
            <FilledAction
              label={lastCard ? "Start quiz" : "Next"}
              disabled={busy}
              onPress={onNext}
              style={styles.primaryAction}
            />
          ) : failed ? (
            <FilledAction
              label={session.authRejected ? "Reconnect" : "Send again"}
              disabled={busy}
              onPress={session.authRejected ? onBack : onRetrySubmit}
              style={styles.primaryAction}
            />
          ) : (
            <FilledAction
              label="Check answer"
              disabled={busy || starting || session.entry.trim() === ""}
              onPress={onCheck}
              style={styles.primaryAction}
            />
          )}
          {starting && (
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
  body: {
    flexGrow: 1,
    paddingTop: spacing.l,
    paddingBottom: spacing.xl,
  },
  content: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  hero: {alignItems: "center", marginTop: spacing.l, marginBottom: spacing.xl},
  prompt: {textAlign: "center", marginTop: spacing.m, width: "100%"},
  promptSingle: {fontSize: 64, lineHeight: 80, fontWeight: "500"},
  promptWord: {fontSize: 40, lineHeight: 56, fontWeight: "500"},
  taskPillWrap: {marginTop: spacing.m},
  section: {marginTop: spacing.xl},
  sectionTitle: {marginBottom: spacing.s},
  answerLine: {fontSize: 20, lineHeight: 28, fontWeight: "600"},
  mnemonic: {marginTop: spacing.m},
  example: {marginTop: spacing.m},
  exampleEnglish: {marginTop: 2},
  answerField: {position: "relative", width: "100%"},
  failure: {fontSize: 12, lineHeight: 18, marginTop: spacing.m},
  busy: {marginTop: spacing.s},
  // The approved WaniKani footer geometry; Anki keeps the shared 52/13 defaults.
  footer: {paddingHorizontal: spacing.l, paddingTop: spacing.m},
  primaryAction: {minHeight: 54, borderRadius: 15},
});
