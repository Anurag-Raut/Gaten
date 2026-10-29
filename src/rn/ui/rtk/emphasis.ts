/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The emphasis the Remembering the Kanji dataset writes in: `**bold**`, `__bold__` and `_italic_`,
 * with markdown links (`[label](target)`) rendered as their label.
 *
 * This is deliberately not a markdown engine. The dataset's stories use exactly these markers, and
 * a full parse would add weight for syntax that never appears. Unpaired markers are kept literally,
 * so malformed prose still renders its text — a story must never be lost to its punctuation.
 *
 * Emphasis nests (`**bold with _italic_ inside**`), because the source does: the parser recurses into
 * each paired run and carries the styles outward.
 *
 * Kept apart from the React component so the parse can be exercised without a renderer.
 */

export interface InlineSpan {
  text: string;
  bold?: boolean;
  italic?: boolean;
}

const LINK = /^\[([^\]]*)\]\(([^)]*)\)/;
const WORD = /[A-Za-z0-9]/;

/** Appends [text] to the last span when it has the same styles, so spans stay few and readable. */
function pushSpan(spans: InlineSpan[], text: string, bold: boolean, italic: boolean): void {
  if (text === "") return;
  const last = spans[spans.length - 1];
  if (last != null && last.bold === bold && last.italic === italic) {
    last.text += text;
    return;
  }
  spans.push({
    text,
    ...(bold ? {bold: true} : null),
    ...(italic ? {italic: true} : null),
  });
}

/**
 * Splits [text] into styled runs. A `_` only opens italic when it is not inside a word, so a handle
 * like `snake_case` is not half-italicised, and only a paired marker becomes emphasis.
 */
export function parseEmphasis(text: string, bold = false, italic = false): InlineSpan[] {
  const spans: InlineSpan[] = [];
  let plain = "";
  let i = 0;

  const flush = () => {
    pushSpan(spans, plain, bold, italic);
    plain = "";
  };

  while (i < text.length) {
    if (text[i] === "[") {
      const link = LINK.exec(text.slice(i));
      if (link != null) {
        flush();
        // The target is dropped: the dataset's are relative book links, and a story is not a place
        // to send the reader. The label is what the prose says.
        spans.push(...parseEmphasis(link[1], bold, italic));
        i += link[0].length;
        continue;
      }
    }

    const boldMarker = text.startsWith("**", i) ? "**" : text.startsWith("__", i) ? "__" : null;
    if (boldMarker != null) {
      const close = text.indexOf(boldMarker, i + boldMarker.length);
      if (close > i + boldMarker.length) {
        flush();
        spans.push(...parseEmphasis(text.slice(i + boldMarker.length, close), true, italic));
        i = close + boldMarker.length;
        continue;
      }
    }

    if (text[i] === "_" && !text.startsWith("__", i)) {
      const previous = i === 0 ? "" : text[i - 1];
      const opensInWord = WORD.test(previous);
      if (!opensInWord) {
        let close = -1;
        for (let j = i + 1; j < text.length; j += 1) {
          if (text[j] !== "_" || text.startsWith("__", j)) continue;
          const next = text[j + 1] ?? "";
          if (!WORD.test(next)) {
            close = j;
            break;
          }
        }
        if (close > i + 1) {
          flush();
          spans.push(...parseEmphasis(text.slice(i + 1, close), bold, true));
          i = close + 1;
          continue;
        }
      }
    }

    plain += text[i];
    i += 1;
  }

  flush();
  return spans;
}
