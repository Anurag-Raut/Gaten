/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * How a typed meaning answer is judged against the meanings WaniKani accepts. The review and the
 * lesson quiz both judge answers, and a difference between the two is a bug, so the rule lives here
 * rather than twice in the store. It is pure and imports nothing, so a `.cjs` test can exercise it
 * without a renderer.
 *
 * The rule, in order:
 *
 *   1. normalise both sides — lowercased, unpunctuated, whitespace-collapsed, trimmed;
 *   2. accept an exact match of one accepted meaning;
 *   3. accept the accepted meanings read as one joined answer, so a pair like `Cool`/`Cold` takes
 *      `cool cold` (this is the "gap" between the two words WaniKani shows as one answer);
 *   4. accept a near miss of one accepted meaning, but only when both sides are at least four
 *      characters, start with the same character, and are one edit apart — two edits when the longer
 *      side is twelve characters or more;
 *   5. otherwise refuse.
 *
 * The length floor and the first-character guard are what keep `cat` from accepting `cats` and
 * `light` from accepting `night`. Wrongly accepting a wrong answer in an SRS app teaches the wrong
 * thing, so the near-miss rule errs strict.
 */

/** The punctuation an answer may carry that the accepted meaning does not: dropped, never spaced. */
const PUNCTUATION = /[,\.!?;:'"\-()]/g;

/** Lowercased, unpunctuated, whitespace-collapsed and trimmed, so `Late  at night.` is `late at night`. */
export function normalizeMeaning(text: string): string {
  return text.replace(PUNCTUATION, "").toLowerCase().replace(/\s+/g, " ").trim();
}

/** The number of single-character edits between two strings — insert, delete or substitute. */
function levenshtein(a: string, b: string): number {
  let previous = Array.from({length: b.length + 1}, (_, index) => index);
  for (let i = 1; i <= a.length; i += 1) {
    const current = [i];
    for (let j = 1; j <= b.length; j += 1) {
      current[j] = Math.min(
        previous[j] + 1,
        current[j - 1] + 1,
        previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)
      );
    }
    previous = current;
  }
  return previous[b.length];
}

/** Whether `answer` is a near miss of `meaning`: both long enough, same opening, and close enough. */
function isCloseMiss(answer: string, meaning: string): boolean {
  if (answer.length < 4 || meaning.length < 4) return false;
  if (answer[0] !== meaning[0]) return false;
  const budget = Math.max(answer.length, meaning.length) >= 12 ? 2 : 1;
  return levenshtein(answer, meaning) <= budget;
}

/**
 * Whether a typed meaning answer should be accepted for a subject: any one of its accepted meanings
 * exactly, all of them read as one joined answer, or a near miss of one of them.
 */
export function meaningMatches(typed: string, accepted: readonly string[]): boolean {
  const answer = normalizeMeaning(typed);
  if (answer === "") return false;
  const meanings = accepted.map(normalizeMeaning);
  if (meanings.some((meaning) => meaning === answer)) return true;
  if (meanings.join(" ") === answer) return true;
  return meanings.some((meaning) => isCloseMiss(answer, meaning));
}
