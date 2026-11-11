/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review screen's inline reference surface: a kanji's details opening under the strip, and a
 * radical's page opening inside that one.
 *
 * Opening is a height reveal — the page grows to the content's own measured height — and the page's
 * content fades in when it appears. Nothing slides: the strip above is what the reader just tapped,
 * and a page flying sideways under it would move the thing they are looking at. A swap between two
 * kanji is the same reveal again, from nothing rather than from the page it replaced, so the height
 * tweens to the new page's measured height while the new page fades in — the skeleton first, then
 * the loaded page, each its own page.
 *
 * Closing has to animate *out*, not vanish, so the last page that was open is kept here: the
 * collapse runs over content that is still mounted, clipped by `Collapsible`'s height. Keeping it
 * also means reopening the same kanji shows it at once, with what it already fetched.
 */
import React, {useRef} from "react";
import Animated, {useReducedMotion} from "react-native-reanimated";
import {Collapsible} from "../Collapsible";
import {revealEntering} from "../reference/reveal";

interface RevealedPage {
  key: string;
  node: React.ReactNode;
}

/**
 * `open` drives the height; `children` is null while closed, and `pageKey` names the page so a swap
 * reads as a page change rather than as the same page resizing.
 */
export function DetailsReveal({
  open,
  pageKey,
  children,
}: {
  open: boolean;
  pageKey: string;
  children: React.ReactNode;
}) {
  const reduced = useReducedMotion();
  // The page on screen has to outlive its own close, so the collapse has something to close over.
  // Updated during render, not in an effect: the height and the fade have to start on the same
  // commit as the open or the swap, never one frame later.
  const last = useRef<RevealedPage | null>(null);
  if (children != null) last.current = {key: pageKey, node: children};
  const page = last.current;

  return (
    <Collapsible expanded={open}>
      {page == null ? null : (
        // Keyed by the page: a new kanji is a new page, and fades in as one.
        <Animated.View key={page.key} entering={revealEntering(reduced)}>
          {page.node}
        </Animated.View>
      )}
    </Collapsible>
  );
}
