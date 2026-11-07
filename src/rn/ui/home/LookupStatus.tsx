/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Lookup mode on Home: the header's status button, and the reminder a reader who has never turned
 * the mode on can dismiss.
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
import {ActivityIndicator, AppState, StyleSheet, Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import {Button, Icon, IconButton} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {shape, spacing, typeScale} from "../tokens";
import type {IconName} from "../icons";

/** What the button reports: the grant's own state, never an intention. */
export type LookupState = "checking" | "on" | "off" | "unavailable";

/** The 48 dp target, the 24 dp glyph inside it, and the status indicator on the shield's corner. */
const TARGET = 48;
const GLYPH = 24;
const INDICATOR = 16;
const INDICATOR_GLYPH = 12;

/**
 * What each state shows: the label a screen reader hears — the mode, and what a tap does — the
 * shield's ink, and the indicator that separates the states by shape rather than by colour alone.
 * Checking draws the spinner instead, so it carries no indicator.
 */
const APPEARANCE: Record<
  LookupState,
  {label: string; accent: boolean; indicator: IconName | null}
> = {
  checking: {label: "Checking lookup mode", accent: false, indicator: null},
  on: {label: "Lookup mode on. Open screen lookup settings.", accent: true, indicator: "check"},
  off: {label: "Lookup mode off. Turn on lookup mode.", accent: false, indicator: "remove"},
  unavailable: {
    label: "Lookup mode status unavailable. Try again.",
    accent: false,
    indicator: "help",
  },
};

/**
 * Lookup mode's state and history for Home. [retry] is the same read the effects run, for the
 * button's unavailable state; [dismissReminder] writes the preference the reminder reads.
 */
export function useLookupStatus(focused: boolean) {
  const [state, setState] = useState<LookupState>("checking");
  const [everEnabled, setEverEnabled] = useState(false);
  const [reminderDismissed, setReminderDismissed] = useState(false);

  // A read that started before a newer one must not land on it: a read begun at launch cannot
  // overwrite the answer that arrived after the reader came back from Android's settings.
  const readSeq = useRef(0);
  const read = useCallback(() => {
    readSeq.current += 1;
    const seq = readSeq.current;
    void GatenAppInfo.getLookupStatus()
      .then((status) => {
        if (seq !== readSeq.current) {
          return;
        }
        setState(status.enabled ? "on" : "off");
        setEverEnabled(status.everEnabled);
        setReminderDismissed(status.reminderDismissed);
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

  /**
   * Dismissing is permanent: the flag is written where the reminder reads it, so it stays away
   * across restarts. A write that fails puts the reminder back and says so, rather than hiding a
   * preference that was never stored.
   */
  const dismissReminder = useCallback(() => {
    setReminderDismissed(true);
    void GatenAppInfo.dismissLookupReminder().catch(() => {
      setReminderDismissed(false);
      void GatenPlatform.toast("Could not dismiss the lookup mode reminder.");
    });
  }, []);

  return {state, everEnabled, reminderDismissed, retry: read, dismissReminder};
}

/**
 * The Home header's lookup-mode status: a 48 dp target at the row's trailing edge, whose shield and
 * indicator say what the mode is doing. A tap does what the label says — the disclosure and
 * Android's settings when the mode is off, the Screen lookup page when it is on, and the read
 * again when the status could not be read.
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
  const {label, accent, indicator} = APPEARANCE[state];
  const ink = accent ? colors.accent : colors.iconSecondary;
  const pending = state === "checking";
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: pending}}
      disabled={pending}
      onPress={state === "on" ? onOpenSettings : state === "off" ? onTurnOn : onRetry}
      style={styles.target}
    >
      {pending ? (
        <ActivityIndicator size="small" color={colors.iconSecondary} />
      ) : (
        <>
          <Icon name="shield" size={GLYPH} color={ink} />
          {indicator != null && (
            // The ground the indicator is cut out of is the page the header sits on, so the shield's
            // own outline is broken by the badge rather than showing through it.
            <View style={[styles.indicator, {backgroundColor: colors.background}]}>
              <Icon name={indicator} size={INDICATOR_GLYPH} color={ink} />
            </View>
          )}
        </>
      )}
    </PressableScale>
  );
}

/**
 * Home's reminder that lookup mode exists: an in-flow notice under the header row, shown only to a
 * reader who has never turned the mode on and has not dismissed it. It is not an overlay — it takes
 * its own place in the header column while it is visible, so it never covers the search field — and
 * it has no timeout: it stays until it is dismissed, or until lookup mode is turned on.
 */
export function LookupModeReminder({
  onTurnOn,
  onDismiss,
}: {
  onTurnOn: () => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.reminder,
        {backgroundColor: colors.surface, borderColor: colors.outlineVariant},
      ]}
    >
      <View style={styles.reminderHead}>
        <View style={styles.reminderText}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
            Look up text in other apps
          </Text>
          <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
            Turn on lookup mode to look up Japanese text on your screen.
          </Text>
        </View>
        <IconButton
          name="close"
          accessibilityLabel="Dismiss lookup mode reminder"
          onPress={onDismiss}
        />
      </View>
      <Button variant="text" label="Turn on lookup mode" onPress={onTurnOn} />
    </View>
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
  indicator: {
    position: "absolute",
    // Inset from the target's own corner so the badge sits on the shield's lower-right edge
    // rather than floating in the empty corner of the 48 dp target.
    right: 4,
    bottom: 4,
    width: INDICATOR,
    height: INDICATOR,
    borderRadius: INDICATOR / 2,
    alignItems: "center",
    justifyContent: "center",
  },
  reminder: {
    borderRadius: shape.small,
    borderWidth: 1,
    padding: spacing.l,
    gap: spacing.s,
    marginBottom: spacing.l,
  },
  reminderHead: {
    flexDirection: "row",
    alignItems: "flex-start",
    gap: spacing.m,
  },
  reminderText: {flex: 1, gap: spacing.xs},
});
