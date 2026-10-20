/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Look up: whether Gaten may read the screen, the three ways a lookup can start, and the optional
 * pause of whatever is playing behind the overlay.
 *
 * The permissions this page reports on are the platform's own — the enabled accessibility
 * services and the enabled notification listeners — and both are read again on every arrival and
 * every foregrounding, because both are granted in system Settings, outside the app. Nothing here
 * is asked for by itself: the disclosure comes first, and only then the system screen.
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
  OutlinedButton,
  RowIcon,
  ScreenAccessCard,
  SettingsGroup,
  SettingsPageScaffold,
  SwitchRow,
  useScreenAccessRequest,
} from "./settings-components";

/** The broadcast a key remapper sends to start a lookup (LookupTriggerReceiver). */
const TRIGGER_LOOKUP_ACTION = "com.gaten.app.action.TRIGGER_LOOKUP";

/** The API the in-place "Add tile" button asks the system from. */
const ADD_TILE_API = 33;

export function LookupSettingsScreen({onClose}: {onClose: () => void}) {
  const theme = useTheme();
  const {request: requestScreenAccess, dialog: disclosureDialog} = useScreenAccessRequest();

  const [screenAccess, setScreenAccess] = useState(false);
  const [notificationAccess, setNotificationAccess] = useState(false);
  const [floatingEnabled, setFloatingEnabled] = useState(false);
  const [tileAdded, setTileAdded] = useState(false);

  // Tile callbacks can arrive while this page sits under the Quick Settings shade, and both
  // permissions are granted in system Settings: re-read on every arrival and foregrounding.
  useEffect(() => {
    let cancelled = false;
    const readAll = () => {
      void GatenAppInfo.isScreenAccessEnabled().then((v) => {
        if (!cancelled) setScreenAccess(v);
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
        <ScreenAccessCard enabled={screenAccess} onTurnOn={requestScreenAccess} />
      </View>
      <SettingsGroup title="Start a lookup" style={{marginTop: spacing.xl}}>
        <SwitchRow
          icon="touch_app"
          title="Floating button"
          summary={
            screenAccess
              ? "Tap the bubble on screen to look up."
              : "Appears once screen access is on."
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
            <Text style={[typeScale.labelLarge, {color: colors.primary}]}>
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
