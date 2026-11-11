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
 * A card is not one screen either: a subject is taught as its meaning, its reading, what it is built
 * from, then how it is used - one step at a time, each only when its data exists (see
 * `lessonStepsOf`). The subject's badge and glyph stay on screen while only the step beneath them
 * changes, so the step is the unit its Next and its Back move in: the reader can re-read the meaning
 * of the word before them without losing their place in the batch.
 *
 * The quiz is judged exactly like a review - the reading through the same converter, the meaning
 * case- and spacing-insensitively - but a finished subject is sent with `PUT /assignments/<id>/start`
 * rather than a review, which is what moves it into the review queue. Each subject is started the
 * moment its quiz is passed, so leaving mid-batch keeps what was already finished.
 */
import React, {useEffect, useRef, useState} from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {KeyboardAvoidingView} from "react-native-keyboard-controller";
import Animated, {useReducedMotion} from "react-native-reanimated";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {CONTENT_COLUMN, contentInsetFor, spacing, typeScale} from "../tokens";
import {Button} from "../components";
import {useTheme} from "../theme";
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
import {lessonJudgement} from "./wanikani-cue";
import {useJudgedAnswer} from "./useJudgedAnswer";
import {atTeachStart, lessonStepsOf, teachForward, type LessonStep} from "./wanikani-lesson-steps";
import {revealEntering} from "../reference/reveal";
import {
  ReferenceBody,
  ReferenceExample,
  ReferenceSection,
  ReferenceTile,
  ReferenceValue,
  ReferenceWord,
} from "../reference/ReferenceText";
import type {WaniKaniLessonSession} from "./review-store";
import type {WaniKaniLessonItem} from "../../native/GatenWaniKani";

/**
 * One teaching step's content. Every step is a titled block in the shared reference language, so a
 * meaning, a reading and a word's components all read the same way on this screen as they do in the
 * lookup sheet and the Anki answer.
 */
function TeachingStep({item, step}: {item: WaniKaniLessonItem; step: LessonStep}) {
  switch (step) {
    case "reading":
      return (
        <ReferenceSection title="Reading">
          <ReferenceValue>{item.readings.join(", ")}</ReferenceValue>
          {item.readingMnemonic !== "" && <ReferenceBody>{item.readingMnemonic}</ReferenceBody>}
        </ReferenceSection>
      );
    case "composition":
      return (
        <ReferenceSection
          title={item.type === "kanji" ? "Radical composition" : "Kanji composition"}
        >
          <View style={styles.composition}>
            {item.components
              .filter((component) => component.character != null || component.name != null)
              .map((component) => (
                <ReferenceTile
                  key={component.id}
                  glyph={component.character}
                  name={component.name ?? ""}
                />
              ))}
          </View>
        </ReferenceSection>
      );
    case "examples":
      // WaniKani gives a kanji no sentences, only the words it appears in, so a kanji's examples are
      // those words and a vocabulary subject's are its sentences.
      return item.contextSentences.length > 0 ? (
        <ReferenceSection title="Example sentences">
          {item.contextSentences.slice(0, 3).map((sentence, index) => (
            <ReferenceExample
              key={index}
              japanese={sentence.japanese}
              english={sentence.english}
            />
          ))}
        </ReferenceSection>
      ) : (
        <ReferenceSection title="Used in">
          {item.relatedWords.map((word, index) => (
            <ReferenceWord
              key={index}
              characters={word.characters}
              reading={word.reading}
              meaning={word.meaning}
            />
          ))}
        </ReferenceSection>
      );
    case "meaning":
      return (
        <ReferenceSection title="Meaning">
          <ReferenceValue>{item.meanings.join(", ")}</ReferenceValue>
          {item.meaningMnemonic !== "" && <ReferenceBody>{item.meaningMnemonic}</ReferenceBody>}
        </ReferenceSection>
      );
  }
}

export function WaniKaniLesson({
  session,
  busy,
  onBack,
  onStepBack,
  onNext,
  onEntry,
  onCheck,
  onRetrySubmit,
}: {
  session: WaniKaniLessonSession;
  busy: boolean;
  onBack: () => void;
  onStepBack: () => void;
  onNext: () => void;
  onEntry: (text: string) => void;
  onCheck: () => void;
  onRetrySubmit: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const reducedMotion = useReducedMotion();
  const [footerHeight, setFooterHeight] = useState(0);
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  const scrollRef = useRef<ScrollView>(null);
  // The answer's own cue, fired on the judgement and not on the renders that follow it; the key it
  // returns is what the answer field's shake is pinned to.
  const shakeKey = useJudgedAnswer(lessonJudgement, session);
  const teaching = session.phase === "teaching";
  const item = teaching ? session.batch[session.teachIndex] : session.batch[session.quizIndex];

  // The step before this one may have been long, so every step swap starts reading at the top of
  // the card rather than wherever the last one was left scrolled to.
  useEffect(() => {
    scrollRef.current?.scrollTo({y: 0, animated: false});
  }, [session.teachIndex, session.teachStep]);

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
  const atStart = atTeachStart(session);
  const step = teaching ? lessonStepsOf(item)[session.teachStep] : null;
  const lastStep =
    teaching && teachForward(session.batch, session.teachIndex, session.teachStep) === "quiz";

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <SubHeader
          title="WaniKani"
          right={`${position + 1} of ${session.batch.length}`}
          onBack={atStart || !teaching ? onBack : onStepBack}
        />
        <View style={{paddingHorizontal: contentInset}}>
          <ProgressLine value={progress} height={3} />
        </View>
        <ScrollView
          ref={scrollRef}
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={[styles.body, {paddingBottom: footerHeight + spacing.xl}]}
          keyboardShouldPersistTaps="handled"
        >
          <View style={[styles.content, {paddingHorizontal: contentInset}]}>
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
                    single ? typeScale.glyphHero : typeScale.displayMedium,
                    {color: colors.onSurface},
                  ]}
                >
                  {characters}
                </Text>
              )}
              {!teaching && (
                <View style={styles.taskPillWrap}>
                  <TaskLabel task={reading ? "reading" : "meaning"} />
                </View>
              )}
            </View>
            {teaching ? (
              // Only the step changes; the badge and glyph above it stay put and must not fade.
              <Animated.View
                key={`${session.teachIndex}:${session.teachStep}`}
                entering={revealEntering(reducedMotion)}
              >
                {step != null && <TeachingStep item={item} step={step} />}
              </Animated.View>
            ) : (
              <>
                <View style={styles.answerField}>
                  <AnswerShake shakeKey={shakeKey}>
                    <AnswerInput
                      key={`${session.quizIndex}:${session.part}`}
                      value={session.entry}
                      onChangeText={(text) => onEntry(reading ? romajiToHiragana(text) : text)}
                      placeholder={reading ? "こたえ" : "Type the meaning"}
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
                  </AnswerShake>
                </View>
                {session.feedback != null && (
                  <Feedback tone={session.feedback.tone} text={session.feedback.text} />
                )}
                {failed && (
                  <Text
                    accessibilityLiveRegion="polite"
                    style={[typeScale.bodySmall, {color: colors.error, marginTop: spacing.m}]}
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
      </KeyboardAvoidingView>
      {/* Keep the action beneath the keyboard; its Done key submits the answer. */}
      <ActionFooter bottomInset={insets.bottom} onHeightChange={setFooterHeight}>
        {teaching && atStart ? (
          <FilledAction label={lastStep ? "Start quiz" : "Next"} disabled={busy} onPress={onNext} />
        ) : teaching ? (
          <View style={styles.stepActions}>
            <Button
              label="Back"
              variant="outlined"
              accessibilityLabel="Previous step"
              onPress={onStepBack}
              style={styles.stepBack}
            />
            <FilledAction
              label={lastStep ? "Start quiz" : "Next"}
              disabled={busy}
              onPress={onNext}
              style={styles.stepNext}
            />
          </View>
        ) : failed ? (
          <FilledAction
            label={session.authRejected ? "Reconnect" : "Send again"}
            disabled={busy}
            onPress={session.authRejected ? onBack : onRetrySubmit}
          />
        ) : (
          <FilledAction
            label="Check answer"
            disabled={busy || starting || session.entry.trim() === ""}
            onPress={onCheck}
          />
        )}
        {starting && (
          <View style={styles.busy}>
            <BusyRow message="Saving to WaniKani" />
          </View>
        )}
      </ActionFooter>
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
  content: CONTENT_COLUMN,
  hero: {alignItems: "center", marginTop: spacing.l, marginBottom: spacing.xl},
  prompt: {textAlign: "center", marginTop: spacing.m, width: "100%"},
  taskPillWrap: {marginTop: spacing.m},
  composition: {gap: spacing.s},
  stepActions: {flexDirection: "row", alignItems: "center", gap: spacing.m},
  stepBack: {flex: 1},
  stepNext: {flex: 2},
  answerField: {position: "relative", width: "100%"},
  busy: {marginTop: spacing.s},
});
