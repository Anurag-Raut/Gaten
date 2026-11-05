/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The touch cue one answered WaniKani prompt earns, fired where the judgement is: the session moving
 * into a judged state from an open one. The state the screen first sees is the one it compares
 * against, so mounting onto an already-judged prompt stays silent, and a re-render, a keystroke or
 * the reader's Continue never plays the cue twice. The returned key is what the answer line's shake
 * is pinned to — set for a wrong answer, cleared for a right one.
 */
import {useEffect, useRef, useState} from "react";
import * as Haptics from "../../native/GatenHaptics";
import type {AnswerJudgement} from "./wanikani-cue";

export function useJudgedAnswer<S>(
  judgement: (previous: S, next: S) => AnswerJudgement | null,
  session: S
): string | null {
  const previous = useRef(session);
  const [shakeKey, setShakeKey] = useState<string | null>(null);

  useEffect(() => {
    const judged = judgement(previous.current, session);
    previous.current = session;
    if (judged == null) return;
    // The answer's own pattern: one short pulse for a right answer, a double buzz for a wrong one.
    void Haptics.play(judged.tone).catch(() => {});
    setShakeKey(judged.tone === "wrong" ? judged.key : null);
  }, [session, judgement]);

  return shakeKey;
}
