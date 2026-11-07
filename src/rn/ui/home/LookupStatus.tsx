/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Lookup mode on Home: the header's status button.
 *
 * Lookup mode is Android's own accessibility grant, so it can only change in the system's settings
 * — nothing in the app can announce it. The status is therefore re-read whenever Home is focused
 * and whenever the app comes back to the foreground, and a read that started before a newer one
 * never lands (the same sequence guard the Screen lookup page uses).
 *
 * Nothing here switches the mode off: "on" is the grant being present, and the only way out of it
 * is Android's own accessibility list. Tapping when it is off opens the app's disclosure and then
 * that list, exactly as the Screen lookup page's own switch does.
 */
import React, {useCallback, useEffect, useRef, useState} from "react";
import {ActivityIndicator, AppState, StyleSheet} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {LookupModeMark} from "./LookupModeArtwork";

/** What the button reports: the grant's own state, never an intention. */
export type LookupState = "checking" | "on" | "off" | "unavailable";

/** The 48 dp target the 26 dp artwork is centred in. */
const TARGET = 48;
const SPINNER = 24;

/** The label a screen reader hears — the mode, and what a tap does. */
const LABEL: Record<LookupState, string> = {
  checking: "Checking lookup mode.",
  on: "Lookup mode on. Open screen lookup settings.",
  off: "Lookup mode off. Turn on lookup mode.",
  unavailable: "Lookup mode status unavailable. Try again.",
};

/**
 * Lookup mode's state for Home, re-read when Home is focused and when the app returns to the
 * foreground. [retry] is the same read the effects run, for the button's unavailable state.
 */
export function useLookupStatus(focused: boolean) {
  const [state, setState] = useState<LookupState>("checking");

  // A read that started before a newer one must not land on it: a read begun at launch cannot
  // overwrite the answer that arrived after the reader came back from Android's settings.
  const readSeq = useRef(0);
  const read = useCallback(() => {
    readSeq.current += 1;
    const seq = readSeq.current;
    void GatenAppInfo.isLookupModeActive()
      .then((enabled) => {
        if (seq !== readSeq.current) {
          return;
        }
        setState(enabled ? "on" : "off");
      })
      .catch(() => {
        if (seq !== readSeq.current) {
          return;
        }
        // A status that could not be read is reported as unknown, never as "off".
        setState("unavailable");
      });
  }, []);

  useEffect(() => {
    if (focused) {
      read();
    }
  }, [focused, read]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        read();
      }
    });
    return () => {
      // A read still in flight when Home goes away must not land on it.
      readSeq.current += 1;
      subscription.remove();
    };
  }, [read]);

  return {state, retry: read};
}

/**
 * The Home header's lookup-mode status: a 48 dp target at the row's trailing edge whose mark says
 * what the mode is doing. A tap does what the label says — the disclosure and Android's settings
 * when the mode is off, the Screen lookup page when it is on, and the read again when the status
 * could not be read. While the status is being read the target is disabled and shows a spinner.
 */
export function LookupStatusButton({
  state,
  onOpenSettings,
  onTurnOn,
  onRetry,
}: {
  state: LookupState;
  onOpenSettings: () => void;
  onTurnOn: () => void;
  onRetry: () => void;
}) {
  const {colors} = useTheme();
  const pending = state === "checking";
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={LABEL[state]}
      accessibilityState={{busy: pending, disabled: pending}}
      disabled={pending}
      onPress={state === "on" ? onOpenSettings : state === "off" ? onTurnOn : onRetry}
      style={styles.target}
    >
      {pending ? (
        <ActivityIndicator size={SPINNER} color={colors.iconSecondary} />
      ) : (
        <LookupModeMark state={state} />
      )}
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  target: {
    width: TARGET,
    height: TARGET,
    borderRadius: TARGET / 2,
    alignItems: "center",
    justifyContent: "center",
  },
});
