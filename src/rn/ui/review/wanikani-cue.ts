/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * What an answered WaniKani prompt owes the reader: the touch cue its judgement earns — a right
 * answer and a wrong one feel different — and the key that pins the answer field's shake to the wrong
 * one. Both are read from the judgement itself, the session moving into a judged state from an open
 * one, so nothing plays again on a re-render, on a keystroke, or on the reader's Continue.
 */
import type {WaniKaniLessonSession, WaniKaniSession} from "./review-store";

/** The two answers, named as the haptics bridge names their patterns. */
export type AnswerTone = "right" | "wrong";

export interface AnswerJudgement {
  tone: AnswerTone;
  /**
   * Identifies this one judgement. Two wrong answers on the same prompt are told apart by how many
   * wrong answers the subject has had by then, so a shake fires on each and not on the renders
   * between them.
   */
  key: string;
}

/** The key one judged answer is pinned to: the prompt, its outcome, and the wrong answers so far. */
function judgementKey(
  index: number,
  part: "meaning" | "reading",
  tone: AnswerTone,
  wrongs: number
): string {
  return `${index}:${part}:${tone}:${wrongs}`;
}

/**
 * The judgement a review prompt just reached, or null when this state is not one. `wkCheck` sends a
 * prompt from `answering` to `correct` or `reviewed`, and `wkContinue` puts a wrong one back to
 * `answering` for the retry WaniKani requires, so both phases are only ever entered from an open
 * prompt — and a screen that mounts straight onto one stays silent, because it never saw the move.
 */
export function reviewJudgement(
  previous: WaniKaniSession,
  next: WaniKaniSession
): AnswerJudgement | null {
  if (previous.phase !== "answering") return null;
  if (previous.index !== next.index || previous.part !== next.part) return null;
  if (next.phase !== "correct" && next.phase !== "reviewed") return null;
  const tone: AnswerTone = next.phase === "correct" ? "right" : "wrong";
  return {
    tone,
    key: judgementKey(next.index, next.part, tone, next.incorrectMeaning + next.incorrectReading),
  };
}

/**
 * The judgement a lesson quiz prompt just reached. A lesson has no `reviewed` phase — a wrong answer
 * leaves the reader on the same prompt to retype it — so the wrong one is read from its own counter
 * rising, which is the only thing a wrong answer does to the prompt. A right one takes two shapes: a
 * subject's meaning giving way to its reading (WaniKani shows no line for that, so the move itself is
 * the cue), or the finished subject being sent. A retry of that send is not a second judgement: the
 * right answer is already on the line, so its tone does not change again.
 */
export function lessonJudgement(
  previous: WaniKaniLessonSession,
  next: WaniKaniLessonSession
): AnswerJudgement | null {
  const wrongs = next.incorrectMeaning + next.incorrectReading;
  if (wrongs > previous.incorrectMeaning + previous.incorrectReading) {
    return {tone: "wrong", key: judgementKey(next.quizIndex, next.part, "wrong", wrongs)};
  }
  const sentRight =
    previous.phase === "quizzing" &&
    next.phase === "starting" &&
    next.feedback?.tone === "right" &&
    previous.feedback?.tone !== "right";
  const meaningAnswered =
    previous.quizIndex === next.quizIndex &&
    previous.part === "meaning" &&
    next.part === "reading";
  if (sentRight || meaningAnswered) {
    return {tone: "right", key: judgementKey(next.quizIndex, next.part, "right", wrongs)};
  }
  return null;
}

/**
 * Whether the answer field shakes for [key], the key it last answered being [previous]: yes for a
 * key that has just arrived, and no for the very key it already shook for — which is what keeps the
 * shake to one per wrong answer — nor for the null a right answer clears the key to.
 */
export function answerShake(previous: string | null, key: string | null): boolean {
  return key != null && key !== previous;
}
