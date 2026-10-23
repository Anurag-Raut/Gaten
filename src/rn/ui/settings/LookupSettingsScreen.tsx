/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Look up: whether lookup mode is on, the three ways a lookup can start, and the optional pause of
 * whatever is playing behind the overlay.
 *
 * Lookup mode is Gaten's accessibility service being enabled, and that is a system setting the
 * reader grants outside the app, so it is read again on every arrival and every foregrounding — as
 * is the notification-listener permission the media pause needs. Nothing here asks for anything by
 * itself: the disclosure comes first, and only then the system screen.
 *
 * Every row states what the system actually reports — a service, an access grant, a tile the
 * system added — rather than a switch Gaten owns. The detail a row cannot carry in one line goes to
 * Help or to the row's own expansion, never onto the page as prose.
 */
import React, {useEffect, useState} from "react";
import {
  AppState,
  Platform,
  Switch as RNSwitch,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenTrigger from "../../native/GatenTrigger";
import * as GatenHaptics from "../../native/GatenHaptics";
import {Button, Icon, IconButton} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import type {IconName} from "../icons";
import {
  OutlinedButton,
  SettingsGroup,
  SettingsPageScaffold,
  useLookupModeRequest,
} from "./settings-components";

/** The broadcast a key remapper sends to start a lookup (LookupTriggerReceiver). */
const TRIGGER_LOOKUP_ACTION = "com.gaten.app.action.TRIGGER_LOOKUP";

/** The API the in-place "Add tile" button asks the system from. */
const ADD_TILE_API = 33;

/** Below this many dp of readable column a row's value or action moves under the title block. */
const ROW_TRAILING_MIN_MEASURE = 270;

/**
 * The page's long explanations, in the order the reader meets them: how a lookup starts, what the
 * floating button and the tile editor own, what the key mapping is for, what the media grant does
 * and what happens without it, and where the optional reference and Anki data goes. Operational
 * detail only — every permission consequence stays in the disclosure, where it is read before the
 * grant, not behind a Help button.
 */
const HELP_PARAGRAPHS = [
  "Circle text or tap a Japanese line. A missed tap keeps lookup open.",
  "Drag the floating button to move it. The button appears while lookup mode is on.",
  "Add or remove Screen lookup in the Quick Settings editor. Use Add tile on supported Android versions.",
  "Map a key to a broadcast action in Key Mapper.",
  "Notification access controls media sessions. Without it, Gaten sends a media key. Some players ignore that key. Gaten resumes media after a normal lookup dismissal. Media stays paused after an interruption.",
  "Japanese recognition and dictionary lookup run on this device. The stylized-text model is optional.",
  "WaniKani is an optional online reference. Its content is not included in Anki exports.",
  "With AnkiDroid access, Add to Anki writes a card. Without access, Send to AnkiDroid opens its add screen. Card layout and deck settings live in Anki cards.",
];

export function LookupSettingsScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  const {request: requestLookupMode, dialog: disclosureDialog} = useLookupModeRequest();

  const [lookupMode, setLookupMode] = useState(false);
  const [notificationAccess, setNotificationAccess] = useState(false);
  const [floatingEnabled, setFloatingEnabled] = useState(false);
  const [tileAdded, setTileAdded] = useState(false);

  // Tile callbacks can arrive while this page sits under the Quick Settings shade, and both
  // lookup mode and the notification-listener permission are granted in system Settings: re-read
  // on every arrival and foregrounding.
  useEffect(() => {
    let cancelled = false;
    const readAll = () => {
      void GatenAppInfo.isLookupModeActive().then((v) => {
        if (!cancelled) setLookupMode(v);
      });
      void GatenPlatform.isNotificationAccessEnabled().then((v) => {
        if (!cancelled) setNotificationAccess(v);
      });
      void GatenTrigger.getFloatingButtonEnabled().then((v) => {
        if (!cancelled) setFloatingEnabled(v);
      });
      void GatenTrigger.tileAdded().then((v) => {
        if (!cancelled) setTileAdded(v);
      });
    };
    readAll();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") readAll();
    });
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, []);

  const toggleFloating = (next: boolean) => {
    void GatenHaptics.play("tick").catch(() => {});
    setFloatingEnabled(next);
    void GatenTrigger.setFloatingButtonEnabled(next).catch(() => {});
    // The bubble only exists while lookup mode is on, so switching it on without lookup mode is
    // the moment to offer to turn lookup mode on. The preference itself stands either way, and the
    // row keeps saying that the service is still off.
    if (next && !lookupMode) requestLookupMode();
  };

  const requestAddTile = () => {
    void GatenTrigger.requestAddTile().then((outcome) => {
      if (outcome === "added") {
        setTileAdded(true);
        void GatenTrigger.setTileAdded(true).catch(() => {});
      } else if (outcome !== "notAdded") {
        void GatenPlatform.toast("Could not add the tile. Try the Quick Settings editor.");
      }
    });
  };

  const floatingSummary = lookupMode ? "Tap to look up" : "Needs lookup mode";

  return (
    <SettingsPageScaffold title="Screen lookup" onClose={onClose} help={HELP_PARAGRAPHS}>
      <SettingsGroup title="Lookup" style={{marginTop: spacing.s}}>
        {/* On is one neutral check, not a trophy: the system owns the service switch, so a success
            colour here would claim a state Gaten cannot set or confirm. */}
        <LookupRow
          icon={lookupMode ? "check_circle" : "shield"}
          title="Lookup mode"
          value={lookupMode ? "On" : "Off"}
          action={lookupMode ? undefined : <Button label="Turn on" onPress={requestLookupMode} />}
        />
        <LookupRow
          icon="touch_app"
          title="Floating button"
          summary={floatingSummary}
          // The switch is the shared row's own control with this page's row around it: the shared
          // SwitchRow keeps a 24 dp glyph and always puts its control on the row's end line.
          action={
            <RNSwitch
              value={floatingEnabled}
              onValueChange={toggleFloating}
              accessibilityLabel="Floating button"
              accessibilityHint={floatingSummary}
              style={{minHeight: 48}}
              trackColor={{false: colors.surfaceVariant, true: colors.accent}}
              thumbColor={floatingEnabled ? colors.onActionPrimary : colors.iconSecondary}
            />
          }
        />
        <TileRow added={tileAdded} onAddTile={requestAddTile} />
        <KeyMapperRow />
      </SettingsGroup>
      <SettingsGroup title="During lookup" style={{marginTop: spacing.xl}}>
        <MediaPauseRow
          granted={notificationAccess}
          onGrant={() => {
            void GatenPlatform.openNotificationListenerSettings().then((opened) => {
              if (!opened) {
                void GatenPlatform.toast("Could not open Notification Access settings");
              }
            });
          }}
        />
      </SettingsGroup>
      {disclosureDialog}
    </SettingsPageScaffold>
  );
}

/**
 * One row of this page: a bare 22 dp glyph, a 15/20 title, an optional 13/19 summary, and whatever
 * the row states or offers at its end. That trailing value, action or control sits at the row's end
 * while the column can hold it beside the title, and drops under the copy when it cannot — the
 * title is never squeezed to make room for it.
 */
function LookupRow({
  icon,
  title,
  summary,
  value,
  action,
  children,
}: {
  icon: IconName;
  title: string;
  summary?: string;
  value?: string;
  action?: React.ReactNode;
  children?: React.ReactNode;
}) {
  const {colors} = useTheme();
  const {width, fontScale} = useWindowDimensions();
  // The readable measure the scaffold's own column leaves at the reader's text scale; the two
  // insets are the scaffold's, duplicated here because the column belongs to it.
  const column =
    Math.min(width, MAX_CONTENT_WIDTH) - (width < 360 ? spacing.l : spacing.contentInset) * 2;
  const stacked = column / fontScale < ROW_TRAILING_MIN_MEASURE;
  const trailing = value != null || action != null;
  return (
    <View
      style={{
        minHeight: 60,
        paddingVertical: spacing.m,
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.m,
      }}
    >
      <Icon name={icon} size={22} color={colors.iconSecondary} />
      <View style={{flex: 1, minWidth: 0}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
        {summary != null && (
          <Text
            style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}
          >
            {summary}
          </Text>
        )}
        {stacked && trailing && (
          <View style={{alignItems: "flex-start", marginTop: spacing.s, gap: spacing.s}}>
            {value != null && (
              <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>{value}</Text>
            )}
            {action}
          </View>
        )}
        {children}
      </View>
      {!stacked && value != null && (
        <Text
          style={[
            typeScale.labelMedium,
            {color: colors.onSurfaceVariant, maxWidth: "40%", flexShrink: 1, textAlign: "right"},
          ]}
        >
          {value}
        </Text>
      )}
      {!stacked && action}
    </View>
  );
}

/**
 * The Quick Settings tile: its presence comes from the system's own add request, and Android owns
 * removal in its editor, so the row either offers the request (API 33+) or says where the editor
 * is. Once it is there, "Added" is the row's value and the row offers nothing to duplicate it.
 */
function TileRow({added, onAddTile}: {added: boolean; onAddTile: () => void}) {
  if (added) {
    return <LookupRow icon="widgets" title="Quick Settings tile" value="Added" />;
  }
  const addTileAvailable = Number(Platform.Version) >= ADD_TILE_API;
  return (
    <LookupRow
      icon="widgets"
      title="Quick Settings tile"
      summary={addTileAvailable ? undefined : "Add in Quick Settings"}
      action={addTileAvailable ? <OutlinedButton label="Add tile" onPress={onAddTile} /> : undefined}
    />
  );
}

/**
 * Optional hardware-key instructions: collapsed until the reader needs the broadcast action, then
 * the action itself in monospace with a copy button beside it. The exact value belongs to the row
 * that has to paste it, not to Help, which only says what the mapping is for.
 */
function KeyMapperRow() {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <LookupRow
      icon="key"
      title="Key Mapper"
      action={
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Key Mapper setup"
          accessibilityState={{expanded}}
          accessibilityHint={expanded ? "Hides the broadcast action" : "Shows the broadcast action"}
          onPress={() => setExpanded(!expanded)}
          style={{
            minHeight: 48,
            paddingHorizontal: spacing.m,
            alignItems: "flex-end",
            justifyContent: "center",
          }}
        >
          <Text style={[typeScale.labelLarge, {color: colors.accent}]}>
            {expanded ? "Hide" : "Set up"}
          </Text>
        </PressableScale>
      }
    >
      {expanded && (
        <>
          <Text
            style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}
          >
            Broadcast action
          </Text>
          <View style={{flexDirection: "row", alignItems: "center", marginTop: spacing.xs}}>
            <Text
              selectable
              style={[
                typeScale.description,
                {color: colors.onSurface, flex: 1, fontFamily: "monospace"},
              ]}
            >
              {TRIGGER_LOOKUP_ACTION}
            </Text>
            <IconButton
              name="content_copy"
              accessibilityLabel="Copy the trigger action"
              onPress={() => {
                void GatenPlatform.copyToClipboard(TRIGGER_LOOKUP_ACTION);
              }}
            />
          </View>
        </>
      )}
    </LookupRow>
  );
}

/**
 * The optional media pause: notification access is what lets Gaten reach the media sessions, so
 * the row says whether it has it and is the only place the grant is offered — an access state whose
 * owner is Android, not a preference of Gaten's. What the grant buys and what happens without it
 * are in Help.
 */
function MediaPauseRow({granted, onGrant}: {granted: boolean; onGrant: () => void}) {
  return (
    <LookupRow
      icon="pause_circle"
      title="Pause media"
      value={granted ? "Allowed" : "Not allowed"}
      summary={granted ? "Pauses media during lookup" : "Some players may keep playing"}
      action={granted ? undefined : <OutlinedButton label="Turn on" onPress={onGrant} />}
    />
  );
}
