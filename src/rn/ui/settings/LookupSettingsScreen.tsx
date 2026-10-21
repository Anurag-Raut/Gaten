/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Look up: whether lookup mode is on, the three ways a lookup can start, and the optional pause of
 * whatever is playing behind the overlay.
 *
 * Lookup mode is a live media projection, and it ends outside Gaten as easily as it starts — the
 * status-bar chip, the notification, the screen locking — so it is read again on every arrival and
 * every foregrounding, as is the notification-listener permission the media pause needs. Nothing
 * here asks for anything by itself: the disclosure comes first, and only then the system dialogs.
 */
import React, {useEffect, useState} from "react";
import {AppState, Platform, Pressable, Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenTrigger from "../../native/GatenTrigger";
import * as GatenHaptics from "../../native/GatenHaptics";
import {IconButton} from "../components";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {
  LookupModeCard,
  OutlinedButton,
  RowIcon,
  SettingsGroup,
  SettingsPageScaffold,
  SwitchRow,
  useLookupModeRequest,
} from "./settings-components";

/** The broadcast a key remapper sends to start a lookup (LookupTriggerReceiver). */
const TRIGGER_LOOKUP_ACTION = "com.gaten.app.action.TRIGGER_LOOKUP";

/** The API the in-place "Add tile" button asks the system from. */
const ADD_TILE_API = 33;

export function LookupSettingsScreen({onClose}: {onClose: () => void}) {
  const theme = useTheme();
  const {request: requestLookupMode, dialog: disclosureDialog} = useLookupModeRequest();

  const [lookupMode, setLookupMode] = useState(false);
  const [notificationAccess, setNotificationAccess] = useState(false);
  const [floatingEnabled, setFloatingEnabled] = useState(false);
  const [tileAdded, setTileAdded] = useState(false);

  // Tile callbacks can arrive while this page sits under the Quick Settings shade, lookup mode can
  // end without the app being asked, and the notification-listener permission is granted in system
  // Settings: re-read on every arrival and foregrounding.
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
    // the moment to offer to turn lookup mode on.
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

  return (
    <SettingsPageScaffold title="Screen lookup" onClose={onClose}>
      <View style={{marginTop: spacing.s}}>
        <LookupModeCard enabled={lookupMode} onTurnOn={requestLookupMode} />
      </View>
      <SettingsGroup title="Start a lookup" style={{marginTop: spacing.xl}}>
        <SwitchRow
          icon="touch_app"
          title="Floating button"
          summary={
            lookupMode
              ? "Tap the bubble on screen to look up."
              : "Appears once lookup mode is on."
          }
          value={floatingEnabled}
          onValueChange={toggleFloating}
        />
        <TileRow added={tileAdded} onAddTile={requestAddTile} />
        <KeyMapperRow />
      </SettingsGroup>
      <SettingsGroup title="While a lookup is open" style={{marginTop: spacing.xl}}>
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
 * The Quick Settings tile: its presence comes from the system's own add request, and Android owns
 * removal in its editor, so the row either offers the request (API 33+) or says where the editor
 * is. Once it is there, "Added to Quick Settings" is the row's value on the right.
 */
function TileRow({added, onAddTile}: {added: boolean; onAddTile: () => void}) {
  const {colors} = useTheme();
  const addTileAvailable = Number(Platform.Version) >= ADD_TILE_API;
  return (
    <View
      style={{
        paddingHorizontal: spacing.l,
        paddingVertical: spacing.m,
        flexDirection: "row",
      }}
    >
      <RowIcon name="widgets" />
      <View style={{flex: 1, marginStart: spacing.m}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1}]}>
            Quick Settings tile
          </Text>
          {added && (
            <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant, marginStart: spacing.s}]}>
              Added to Quick Settings
            </Text>
          )}
        </View>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          {added
            ? "To remove it, open the Quick Settings editor and drag Gaten out."
            : "Add Gaten to Quick Settings to look up from the notification shade."}
        </Text>
        {!added && !addTileAvailable && (
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
            Open the Quick Settings editor and drag Gaten into your tiles.
          </Text>
        )}
        {!added && addTileAvailable && (
          <View style={{marginTop: spacing.s, alignSelf: "flex-start"}}>
            <OutlinedButton label="Add tile" onPress={onAddTile} />
          </View>
        )}
      </View>
    </View>
  );
}

/**
 * Optional hardware-key instructions: collapsed until the reader needs the broadcast action, then
 * the action itself in monospace with a copy button beside it.
 */
function KeyMapperRow() {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <View
      style={{
        paddingHorizontal: spacing.l,
        paddingVertical: spacing.m,
        flexDirection: "row",
      }}
    >
      <RowIcon name="key" />
      <View style={{flex: 1, marginStart: spacing.m}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1}]}>
            Hardware key (Key Mapper)
          </Text>
          <Pressable onPress={() => setExpanded(!expanded)}>
            <Text style={[typeScale.labelLarge, {color: colors.accent}]}>
              {expanded ? "Hide" : "Set up"}
            </Text>
          </Pressable>
        </View>
        {expanded && (
          <>
            <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
              Send a broadcast with this action:
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
          </>
        )}
      </View>
    </View>
  );
}

/**
 * The optional media pause: notification access is what lets Gaten reach the media sessions, so
 * the row says whether it has it on the right, and is the only place the grant is offered.
 */
function MediaPauseRow({granted, onGrant}: {granted: boolean; onGrant: () => void}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        paddingHorizontal: spacing.l,
        paddingVertical: spacing.m,
        flexDirection: "row",
      }}
    >
      <RowIcon name="pause_circle" />
      <View style={{flex: 1, marginStart: spacing.m}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1}]}>
            Pause video while looking up
          </Text>
          <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant, marginStart: spacing.s}]}>
            {granted ? "Allowed" : "Not allowed"}
          </Text>
        </View>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          {granted
            ? "Gaten pauses what is playing when a lookup opens, and plays it again when it closes."
            : "Without notification access Gaten sends a media pause key instead, which stops some players and not others."}
        </Text>
        {!granted && (
          <View style={{marginTop: spacing.s, alignSelf: "flex-start"}}>
            <OutlinedButton label="Turn on" onPress={onGrant} />
          </View>
        )}
      </View>
    </View>
  );
}
