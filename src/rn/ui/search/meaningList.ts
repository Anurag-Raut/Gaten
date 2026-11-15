/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * How a dictionary sense becomes the lines a reader counts.
 *
 * The native bridge hands over one `glosses` element per sense (the database's " | " separator is
 * already split on the Kotlin side), and inside that element the sense's synonyms are joined with
 * ", " by `tools/build_jmdict_db.py`. A comma is also punctuation the gloss itself uses inside
 * brackets — "(e.g. bus, train, etc.)" — so a split that ignored nesting would tear a parenthetical
 * in half. This module therefore splits only at a comma that sits at the top level of the sense.
 *
 * The same nesting also marks the notes: a top-level "(...)" run inside a gloss is a remark about
 * the meaning rather than the meaning, and the section draws it dimmed in place. [splitParentheticals]
 * cuts a line into those runs without changing a character of it.
 *
 * It imports nothing, so the rules can be exercised on their own (see tests/meaning-list.test.cjs).
 */

/** Senses shown before the rest are put behind the toggle. */
export const MEANING_PREVIEW_SENSES = 3;

/**
 * Above this many senses the list collapses to [MEANING_PREVIEW_SENSES]; at or below it every sense
 * shows. A word with four senses is short enough to read whole, so it is not made to open twice.
 */
export const MEANING_COLLAPSE_ABOVE = 4;

const OPENERS = "([";
const CLOSERS = ")]";

/**
 * The synonyms of one sense, one per line: split at ", " only where no bracket is open, trimmed,
 * with empty parts dropped. An empty or whitespace-only sense has no lines at all; anything else
 * keeps at least one, so an unbalanced "(" degrades to a single line instead of losing the sense.
 */
export function splitSense(sense: string): string[] {
  const trimmed = sense.trim();
  if (trimmed.length === 0) return [];

  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < trimmed.length; index += 1) {
    const character = trimmed[index];
    if (OPENERS.includes(character)) {
      depth += 1;
    } else if (CLOSERS.includes(character)) {
      // A stray closer must not push the depth negative, or the next top-level comma would be eaten.
      depth = Math.max(0, depth - 1);
    } else if (depth === 0 && character === "," && trimmed[index + 1] === " ") {
      parts.push(trimmed.slice(start, index));
      start = index + 2;
    }
  }
  parts.push(trimmed.slice(start));

  const lines = parts.map((part) => part.trim()).filter((part) => part.length > 0);
  return lines.length > 0 ? lines : [trimmed];
}

/** One run of a gloss line: its own text, and whether it is a parenthetical note. */
export interface MeaningRun {
  text: string;
  /** True for a top-level "(...)" run, brackets included — the part drawn dimmed. */
  note: boolean;
}

/**
 * A gloss line cut into plain text and parenthetical notes, in order and contiguous: joining the
 * runs' `text` always reproduces [text] exactly, so the line never loses a character to the split.
 *
 * A note is a top-level "(...)" run, brackets and all; parentheses inside it stay part of it, so
 * "(a (b) c)" is one note. A "(" the gloss never closes dims the rest of the line rather than
 * being dropped — the dictionary does contain such glosses, and a reader should still see them.
 * An empty line has no runs at all.
 */
export function splitParentheticals(text: string): MeaningRun[] {
  if (text.length === 0) return [];

  const runs: MeaningRun[] = [];
  let depth = 0;
  let plainStart = 0;
  let noteStart = -1;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (character === "(") {
      if (depth === 0) {
        // The text before the note is plain, and is emitted only if there is any of it.
        if (index > plainStart) {
          runs.push({text: text.slice(plainStart, index), note: false});
        }
        noteStart = index;
      }
      depth += 1;
    } else if (character === ")" && depth > 0) {
      depth -= 1;
      if (depth === 0) {
        runs.push({text: text.slice(noteStart, index + 1), note: true});
        plainStart = index + 1;
        noteStart = -1;
      }
    }
  }

  if (depth > 0) {
    // An unclosed "(": the remainder is the note, brackets and all.
    runs.push({text: text.slice(noteStart), note: true});
  } else if (plainStart < text.length) {
    runs.push({text: text.slice(plainStart), note: false});
  }
  return runs;
}
