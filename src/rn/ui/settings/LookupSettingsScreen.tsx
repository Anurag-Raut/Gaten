/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Look up: whether lookup mode is on, the three ways a lookup can start, and the optional access
 * that lets Gaten pause and resume media through active sessions.
 *
 * Lookup mode is Gaten's accessibility service being enabled, and that is a system setting the
 * reader grants outside the app, so it is read again on every arrival and every foregrounding — as
 * is the notification-listener permission for session-based media control. Lookup mode's disclosure
 * comes first, and only then the system screen. The Quick Settings tile is read the same way:
 * whether it is in the shade at all, and whether the reader has it switched on.
 */
import {Collapsible} from "../Collapsible";
import React, {useEffect, useRef, useState} from "react";
import {AppState, Platform, Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenTrigger from "../../native/GatenTrigger";
import * as GatenHaptics from "../../native/GatenHaptics";
import {IconButton} from "../components";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {
  SettingsPageScaffold,
  SettingsRow,
  SettingsTile,
  SwitchRow,
  useLookupModeRequest,
} from "./settings-components";

/**
 * The broadcast a key remapper sends to start a lookup (LookupTriggerReceiver). The older
 * `com.gaten.app.action.TRIGGER_LOOKUP` is still accepted, so an existing Key Mapper map keeps
 * working, but this canonical name matches the installed `com.gaten` package.
 */
const TRIGGER_LOOKUP_ACTION = "com.gaten.action.TRIGGER_LOOKUP";

/** The installed package a key remapper scopes the trigger broadcast to. */
const TRIGGER_LOOKUP_PACKAGE = "com.gaten";

/** The manifest receiver class for a broadcast addressed directly to the lookup trigger. */
const TRIGGER_LOOKUP_CLASS = "com.gaten.app.LookupTriggerReceiver";

/** The API that can be asked to put the tile in Quick Settings in place (requestAddTileService). */
const ADD_TILE_API = 33;

const HELP_PARAGRAPHS = [
  "Circle text or tap a Japanese line. A missed tap keeps lookup open.",
  "Drag the floating button to move it, or drop it on the X at the bottom of the screen to remove it. Switch Floating button back on here to bring it back. The button appears while lookup mode is on.",
  "The Quick Settings tile can be switched off here and removed in Android's Quick Settings editor.",
  "Map a key to the broadcast action shown in Key Mapper.",
  "Notification access lets Gaten control media sessions. Without it, some players may ignore media keys. Gaten resumes playback after a normal lookup dismissal; media stays paused after an interruption.",
  "Japanese recognition and dictionary lookup run on this device. Every lookup uses Manga OCR to recognize text. Google ML Kit locates text for selection; its readings are not used as lookup answers. The Manga OCR model is included with Gaten, prepared on first lookup, and works offline.",
  "WaniKani is an optional online reference. Its content is not included in Anki exports.",
  "With AnkiDroid access, Add to Anki writes a card. Grant access in Anki cards, where card layout and deck settings live.",
];

export function LookupSettingsScreen({onClose}: {onClose: () => void}) {
  const {request: requestLookupMode, dialog: disclosureDialog} = useLookupModeRequest();

  const [lookupMode, setLookupMode] = useState(false);
  const [notificationAccess, setNotificationAccess] = useState(false);
  const [floatingEnabled, setFloatingEnabled] = useState(false);
  const [tileAdded, setTileAdded] = useState(false);
  const [tileEnabled, setTileEnabled] = useState(true);
  const [tileRequestPending, setTileRequestPending] = useState(false);

  const addTileAvailable = Number(Platform.Version) >= ADD_TILE_API;
  // On only while the tile is both in Quick Settings and switched on.
  const tileSwitchOn = tileAdded && tileEnabled;

  // Presence and the enabled flag are read together, and applied only while they are still the
  // newest read: a read that started before a toggle must not put the old switch back on screen.
  const tileReadSeq = useRef(0);
  const readTileState = () => {
    tileReadSeq.current += 1;
    const seq = tileReadSeq.current;
    void Promise.all([GatenTrigger.tileAdded(), GatenTrigger.tileEnabled()])
      .then(([added, enabled]) => {
        if (seq !== tileReadSeq.current) {
          return;
        }
        setTileAdded(added);
        setTileEnabled(enabled);
      })
      .catch(() => {
        if (seq !== tileReadSeq.current) {
          return;
        }
        void GatenPlatform.toast("Could not read the tile setting.");
      });
  };

  // Lookup mode is guarded the same way: the switch follows the service's own state, so a read that
  // started before the reader touched it must not put the old state back.
  const lookupReadSeq = useRef(0);
  const readLookupMode = () => {
    lookupReadSeq.current += 1;
    const seq = lookupReadSeq.current;
    void GatenAppInfo.isLookupModeActive()
      .then((v) => {
        if (seq === lookupReadSeq.current) {
          setLookupMode(v);
        }
      })
      .catch(() => {});
  };

  // The floating switch is guarded too: the remove button on the overlay writes the preference and
  // announces it, so a read that started before a close or a toggle must not put the old value
  // back. Both the mount/foreground read and the notification go through here.
  const floatingReadSeq = useRef(0);
  const readFloatingEnabled = () => {
    floatingReadSeq.current += 1;
    const seq = floatingReadSeq.current;
    void GatenTrigger.getFloatingButtonEnabled()
      .then((v) => {
        if (seq === floatingReadSeq.current) {
          setFloatingEnabled(v);
        }
      })
      .catch(() => {});
  };

  // Tile callbacks can arrive while this page sits under the Quick Settings shade, and both
  // lookup mode and the notification-listener permission are granted in system Settings: re-read
  // on every arrival and foregrounding.
  useEffect(() => {
    let cancelled = false;
    const readAll = () => {
      readLookupMode();
      void GatenPlatform.isNotificationAccessEnabled().then((v) => {
        if (!cancelled) setNotificationAccess(v);
      });
      readFloatingEnabled();
      readTileState();
    };
    readAll();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") readAll();
    });
    // The overlay's remove button writes the preference in native code; this is how the switch
    // shows OFF while this page is already in the foreground.
    const floatingSubscription = GatenTrigger.onFloatingButtonChanged(readFloatingEnabled);
    return () => {
      cancelled = true;
      // A read still in flight when the page goes away must not land on it.
      tileReadSeq.current += 1;
      lookupReadSeq.current += 1;
      floatingReadSeq.current += 1;
      floatingSubscription.remove();
      subscription.remove();
    };
  }, []);

  /**
   * Lookup mode is Android's own accessibility setting, so both directions go outside the app: on is
   * the disclosure and then the system screen Gaten is listed on, off is the service turning itself
   * off. The switch is only ever what the service reports — re-read after the action and on every
   * foregrounding — so a reader who does not enable Gaten is not shown as if they had.
   */
  const toggleLookupMode = (next: boolean) => {
    void GatenHaptics.play("tick").catch(() => {});
    if (next) {
      requestLookupMode();
      return;
    }
    void GatenPlatform.disableAccessibilityService()
      .then((disabled) => {
        if (disabled) {
          return;
        }
        // No connected service to turn off: the reader does it where Android lists it, and the
        // switch then shows what really happened rather than what was asked for.
        void GatenPlatform.toast("Turn Gaten off in Android's Accessibility settings");
        return GatenPlatform.openAccessibilitySettings().then((opened) => {
          if (!opened) {
            void GatenPlatform.toast("Could not open Accessibility Settings");
          }
        });
      })
      .catch(() => {
        void GatenPlatform.toast("Could not turn lookup mode off");
      })
      .finally(readLookupMode);
  };

  const toggleFloating = (next: boolean) => {
    void GatenHaptics.play("tick").catch(() => {});
    setFloatingEnabled(next);
    void GatenTrigger.setFloatingButtonEnabled(next).catch(() => {});
    // The bubble only exists while lookup mode is on, so switching it on without lookup mode is
    // the moment to offer to turn lookup mode on.
    if (next && !lookupMode) requestLookupMode();
  };

  /**
   * The tile's switch. With the tile already present, either direction only writes the flag. Without
   * it, on asks the system to add it (API 33+) and turns on only once the system answers that it went
   * in, while a cancelled request stays off. Off never removes a tile — Android's editor owns that.
   */
  const toggleTile = (next: boolean) => {
    void GatenHaptics.play("tick").catch(() => {});
    if (!next || tileAdded) {
      void GatenTrigger.setTileEnabled(next)
        .then(readTileState)
        .catch(() => {
          void GatenPlatform.toast("Could not save the tile setting.");
        });
      return;
    }
    if (!addTileAvailable) {
      // Nothing can add a tile in place below API 33, and the row does not pretend otherwise.
      void GatenPlatform.toast("Add Gaten in the Quick Settings editor.");
      return;
    }
    if (tileRequestPending) {
      return;
    }
    setTileRequestPending(true);
    void GatenTrigger.requestAddTile()
      .then((outcome) => {
        if (outcome !== "added") {
          if (outcome === "failed") {
            void GatenPlatform.toast("Could not add the tile. Try the Quick Settings editor.");
          }
          // "notAdded" is the reader cancelling: the switch stays off, which is where it was.
          return;
        }
        // onTileAdded records presence too; this is the same answer arriving first, so the switch
        // turns on without waiting for the shade to be drawn again.
        return GatenTrigger.setTileAdded(true)
          .then(() => GatenTrigger.setTileEnabled(true))
          .then(readTileState)
          .catch(() => {
            void GatenPlatform.toast("Could not save the tile setting.");
          });
      })
      .catch(() => {
        void GatenPlatform.toast("Could not add the tile. Try the Quick Settings editor.");
      })
      .finally(() => {
        setTileRequestPending(false);
      });
  };

  return (
    <SettingsPageScaffold title="Screen lookup" onClose={onClose} help={HELP_PARAGRAPHS}>
      <View style={{marginTop: spacing.s}}>
        <SettingsTile>
          <SwitchRow
            icon="shield"
            title="Lookup mode"
            summary={lookupMode ? "On" : "Off. Needs Android's accessibility permission."}
            value={lookupMode}
            onValueChange={toggleLookupMode}
          />
        </SettingsTile>
        <SettingsTile>
          <SwitchRow
            icon="touch_app"
            title="Floating button"
            summary={lookupMode ? "Tap to look up" : "Needs lookup mode"}
            value={floatingEnabled}
            onValueChange={toggleFloating}
          />
        </SettingsTile>
        <SettingsTile>
          <SwitchRow
            icon="widgets"
            title="Quick Settings tile"
            summary={tileSummary(tileAdded, tileEnabled, addTileAvailable)}
            value={tileSwitchOn}
            onValueChange={toggleTile}
            disabled={tileRequestPending}
          />
        </SettingsTile>
        <SettingsTile>
          <KeyMapperRow />
        </SettingsTile>
        <SettingsTile>
          <SettingsRow
            icon="pause_circle"
            title="Improve media pause"
            summary={notificationAccess
              ? "Uses media sessions to pause and resume playback."
              : "Without notification access, some players may keep playing."}
            value={notificationAccess ? "Allowed" : "Not allowed"}
            onPress={() => {
              void GatenPlatform.openNotificationListenerSettings().then((opened) => {
                if (!opened) {
                  void GatenPlatform.toast("Could not open Notification Access settings");
                }
              });
            }}
          />
        </SettingsTile>
      </View>
      {disclosureDialog}
    </SettingsPageScaffold>
  );
}

/** What the tile row says under its name, by whether the tile is there and switched on. */
function tileSummary(added: boolean, enabled: boolean, addTileAvailable: boolean): string {
  if (added) {
    return enabled
      ? "Open lookup from Quick Settings."
      : "Disabled. Remove in Quick Settings editor.";
  }
  return addTileAvailable
    ? "Turn on to add Gaten to Quick Settings."
    : "Add Gaten in the Quick Settings editor.";
}

/**
 * Optional hardware-key instructions: the row itself expands — its value says what a tap does — into
 * the broadcast action, package and receiver class, each in monospace with a copy button, plus
 * the target mode Key Mapper expects.
 */
function KeyMapperRow() {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <View>
      <SettingsRow
        icon="key"
        title="Key Mapper"
        value={expanded ? "Hide" : "Set up"}
        expanded={expanded}
        onPress={() => setExpanded(!expanded)}
      />
      <Collapsible expanded={expanded}>
        <View style={{paddingBottom: spacing.m * 2}}>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            Broadcast action
          </Text>
          <View style={{flexDirection: "row", alignItems: "center", marginTop: spacing.xs}}>
            <Text
              style={[typeScale.bodySmall, {color: colors.onSurface, flex: 1, fontFamily: "monospace"}]}
            >
              {TRIGGER_LOOKUP_ACTION}
            </Text>
            <IconButton
              name="content_copy"
              accessibilityLabel="Copy the trigger action"
              compact
              onPress={() => {
                void GatenPlatform.copyToClipboard(TRIGGER_LOOKUP_ACTION);
              }}
            />
          </View>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}>
            Package name
          </Text>
          <View style={{flexDirection: "row", alignItems: "center", marginTop: spacing.xs}}>
            <Text
              style={[typeScale.bodySmall, {color: colors.onSurface, flex: 1, fontFamily: "monospace"}]}
            >
              {TRIGGER_LOOKUP_PACKAGE}
            </Text>
            <IconButton
              name="content_copy"
              accessibilityLabel="Copy the app package name"
              compact
              onPress={() => {
                void GatenPlatform.copyToClipboard(TRIGGER_LOOKUP_PACKAGE);
              }}
            />
          </View>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}>
            Receiver class
          </Text>
          <View style={{flexDirection: "row", alignItems: "center", marginTop: spacing.xs}}>
            <Text
              style={[typeScale.bodySmall, {color: colors.onSurface, flex: 1, fontFamily: "monospace"}]}
            >
              {TRIGGER_LOOKUP_CLASS}
            </Text>
            <IconButton
              name="content_copy"
              accessibilityLabel="Copy the receiver class"
              compact
              onPress={() => {
                void GatenPlatform.copyToClipboard(TRIGGER_LOOKUP_CLASS);
              }}
            />
          </View>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}>
            In Key Mapper use Send intent with Broadcast receiver as the target. Fill in Action,
            Package and Class using the values above.
          </Text>
        </View>
      </Collapsible>
    </View>
  );
}
