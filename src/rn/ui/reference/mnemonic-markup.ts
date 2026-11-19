/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * WaniKani's mnemonic text as pieces. The native decoder keeps the words WaniKani highlights
 * (radical, kanji, vocabulary, reading) between three private-use markers and strips everything
 * else; this splits that string back so a screen can colour the highlighted words. Anything that
 * is not a well-formed marker stays plain text.
 */
export type MnemonicKind = "radical" | "kanji" | "vocabulary" | "reading" | "ja";

export interface MnemonicPiece {
  text: string;
  kind: MnemonicKind | null;
}

const MARKED = /(radical|kanji|vocabulary|reading|ja)([\s\S]*?)/g;
const STRAY = /[-]/g;

export function parseMnemonic(text: string): MnemonicPiece[] {
  const pieces: MnemonicPiece[] = [];
  let last = 0;
  for (const match of text.matchAll(MARKED)) {
    const start = match.index ?? 0;
    if (start > last) pieces.push({text: text.slice(last, start).replace(STRAY, ""), kind: null});
    pieces.push({text: match[2].replace(STRAY, ""), kind: match[1] as MnemonicKind});
    last = start + match[0].length;
  }
  if (last < text.length) pieces.push({text: text.slice(last).replace(STRAY, ""), kind: null});
  return pieces.filter((piece) => piece.text !== "");
}
