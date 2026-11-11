/**
 * The steps a WaniKani lesson teaches one subject in, and the arithmetic that walks them.
 *
 * A teaching card is not one screen: a subject is taught as its meaning, then its reading when it has
 * one, then what it is built from, then examples — each only when its data exists, so a radical is a
 * single step and a vocabulary word is usually four. The lesson screen draws one step at a time, and
 * both its Next and its Back move between steps rather than between subjects; the progress line
 * counts steps too, so it moves on every step and back when the reader steps back.
 *
 * Kept free of runtime imports — the lesson subject is imported for its type alone — so the rules
 * can be exercised on their own, without the bridges the store around them names.
 */
import type {WaniKaniLessonItem} from "../../native/GatenWaniKani";

/** One teaching step, in the fixed order a subject is taught in. */
export type LessonStep = "meaning" | "reading" | "composition" | "examples";

/** The fields that decide a subject's steps: what any lesson subject carries, and nothing more. */
type LessonSubject = Pick<
  WaniKaniLessonItem,
  "type" | "readings" | "components" | "contextSentences" | "relatedWords"
>;

/**
 * The steps [item] is taught in, in order, each only when its data exists. A subject is never
 * taught in no steps at all: its meaning is always there to teach. Composition is for what is built
 * from something — a radical has no components — and needs at least one component the screen can
 * actually draw, a character or a name; examples are the sentences a vocabulary word carries, or the
 * words a kanji appears in, which is all WaniKani gives a kanji instead.
 */
export function lessonStepsOf(item: LessonSubject): LessonStep[] {
  const steps: LessonStep[] = ["meaning"];
  if (item.readings.length > 0) steps.push("reading");
  const composed =
    item.type !== "radical" &&
    item.components.some(
      (component) => component.character != null || component.name != null
    );
  if (composed) steps.push("composition");
  if (item.contextSentences.length > 0 || item.relatedWords.length > 0) steps.push("examples");
  return steps;
}

/** How many teaching steps the whole batch asks for: the teaching half of its progress line. */
export function teachPartsOf(batch: readonly LessonSubject[]): number {
  return batch.reduce((total, item) => total + lessonStepsOf(item).length, 0);
}

/** How many teaching steps are finished before `teachIndex`'s step `teachStep`. */
export function teachDone(
  batch: readonly LessonSubject[],
  teachIndex: number,
  teachStep: number
): number {
  let done = 0;
  for (let index = 0; index < teachIndex && index < batch.length; index += 1) {
    done += lessonStepsOf(batch[index]).length;
  }
  return done + teachStep;
}

/**
 * The step after this one: the next step of the same subject, the first step of the next subject,
 * or `"quiz"` past the last step of the last subject.
 */
export function teachForward(
  batch: readonly LessonSubject[],
  teachIndex: number,
  teachStep: number
): {teachIndex: number; teachStep: number} | "quiz" {
  const item = batch[teachIndex];
  if (item != null && teachStep + 1 < lessonStepsOf(item).length) {
    return {teachIndex, teachStep: teachStep + 1};
  }
  if (teachIndex + 1 < batch.length) return {teachIndex: teachIndex + 1, teachStep: 0};
  return "quiz";
}

/**
 * The step before this one, which from a subject's first step is the last step of the subject
 * before it; null at the very first step, where there is nothing to step back to.
 */
export function teachBack(
  batch: readonly LessonSubject[],
  teachIndex: number,
  teachStep: number
): {teachIndex: number; teachStep: number} | null {
  if (teachStep > 0) return {teachIndex, teachStep: teachStep - 1};
  const previous = batch[teachIndex - 1];
  if (teachIndex === 0 || previous == null) return null;
  return {teachIndex: teachIndex - 1, teachStep: lessonStepsOf(previous).length - 1};
}

/**
 * Whether the reader is at the very first step of the first subject, where Back has nothing to step
 * back to and leaves the batch instead. A quiz is never at a teaching start: mid-quiz Back is the
 * leave question it always was.
 */
export function atTeachStart(session: {
  phase: string;
  teachIndex: number;
  teachStep: number;
}): boolean {
  return session.phase === "teaching" && session.teachIndex === 0 && session.teachStep === 0;
}
