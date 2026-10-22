/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The pieces every settings page is built from: the scaffold with its top bar, the row of a
 * group, the group itself, the dialogs a page raises, and the lookup-mode row with the
 * disclosure flow behind its "Turn on".
 *
 * These are the RN ports of the shared composables in `SettingsScreen.kt`; the pages in
 * `ui/settings/` assemble them, and the copy stays in each page. Buttons, icons and the
 * press-scale come from `ui/components` / `ui/PressableScale`, so every tab draws them alike.
 */
import React, {useEffect, useState} from "react";
import {
  AppState,
  Modal,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  Switch as RNSwitch,
  Text,
  TextInput,
  View,
  useWindowDimensions,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {canDrawOverlays, openOverlaySettings, requestLookupMode, toast} from "../../native/GatenPlatform";
import {
  Button,
  Icon,
  IconButton,
  SectionLabel,
} from "../components";
import {PressableScale} from "../PressableScale";
import {InkIcon, type InkIconName} from "../InkIcon";
import {useTheme} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import type {IconName} from "../icons";

/**
 * A screen's content column: as wide as the screen up to the app's cap, centred, with the screen's
 * own horizontal rhythm inside it. The pages' scrollers and the hub's own scroller share it, so
 * every settings screen puts its content in the same box.
 */
export const CONTENT_COLUMN: ViewStyle = {
  width: "100%",
  maxWidth: MAX_CONTENT_WIDTH,
  alignSelf: "center",
  paddingHorizontal: 22,
};

/** A neutral settings glyph using the hub's existing icon set. */
export function RowIcon({name, inkName}: {name: IconName; inkName?: InkIconName}) {
  const {colors} = useTheme();
  return inkName
    ? <InkIcon name={inkName} size={22} color={colors.iconSecondary} />
    : <Icon name={name} size={24} color={colors.iconSecondary} />;
}

/** A flat form section; its closing rule separates it from the next section. */
export function SettingsTile({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <View style={[{borderBottomWidth: 1, borderBottomColor: colors.outlineVariant}, style]}>
      {children}
    </View>
  );
}

/** A quiet section heading and flat rows separated by the same rules as the study hubs. */
export function SettingsGroup({
  title,
  children,
  style,
}: {
  title: string;
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <View style={style}>
      <SectionLabel
        style={[
          typeScale.labelMedium,
          {color: colors.onSurfaceVariant, textTransform: "none", marginBottom: spacing.s},
        ]}
      >
        {title}
      </SectionLabel>
      {React.Children.toArray(children).map((child, index) => (
        <View key={index} style={{borderBottomWidth: 1, borderBottomColor: colors.outlineVariant}}>
          {child}
        </View>
      ))}
    </View>
  );
}

/**
 * A destination or informational row. Important values wrap instead of disappearing at large text
 * sizes; the optional description is reserved for information the title and value cannot convey.
 */
export function SettingsRow({
  icon,
  inkIcon,
  title,
  summary,
  value,
  onPress,
}: {
  icon: IconName;
  inkIcon?: InkIconName;
  title: string;
  summary?: string;
  value?: string | null;
  onPress?: () => void;
}) {
  const {colors} = useTheme();
  const rowStyle: ViewStyle = {
    minHeight: 60,
    paddingVertical: spacing.m,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
  };
  const body = (
    <>
      <RowIcon name={icon} inkName={inkIcon} />
      <View style={{flex: 1, minWidth: 0}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
        {summary != null && (
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
            {summary}
          </Text>
        )}
      </View>
      {value != null && (
        <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant, maxWidth: "40%", flexShrink: 1, textAlign: "right"}]}>
          {value}
        </Text>
      )}
      {onPress != null && <InkIcon name="chevron" size={15} color={colors.fine} />}
    </>
  );
  if (onPress == null) {
    return <View style={rowStyle}>{body}</View>;
  }
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={[title, value, summary].filter(Boolean).join(". ")}
      onPress={onPress}
      style={rowStyle}
    >
      {body}
    </PressableScale>
  );
}

/** Lookup status and its existing permission action, without a separate card or explanation. */
export function LookupModeRow({
  enabled,
  onTurnOn,
}: {
  enabled: boolean;
  onTurnOn: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View style={{minHeight: 60, paddingVertical: spacing.m, flexDirection: "row", alignItems: "center", gap: spacing.m}}>
      <Icon name={enabled ? "check_circle" : "shield"} size={24} color={enabled ? colors.success : colors.iconSecondary} />
      <View style={{flex: 1}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>Lookup mode</Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>{enabled ? "On" : "Off"}</Text>
      </View>
      {!enabled && <Button label="Turn on" onPress={onTurnOn} />}
    </View>
  );
}

/** An outlined action: a quiet alternative to the filled one on the same page. */
export function OutlinedButton({
  label,
  onPress,
  disabled,
  style,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <Button
      variant="outlined"
      label={label}
      onPress={onPress}
      disabled={disabled}
      accessibilityLabel={label}
      style={style}
    />
  );
}

/**
 * The label over a field: what the field holds, in the reader's quiet ink, at the same rhythm on
 * every page that asks for something.
 */
export function FieldLabel({
  label,
  style,
}: {
  label: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text
      style={[
        typeScale.labelMedium,
        {color: colors.onSurfaceVariant, marginBottom: spacing.s},
        style,
      ]}
    >
      {label}
    </Text>
  );
}

/**
 * The box a settings page types into: a white/dark surface with a
 * quiet outline and the app's own corner and control height. Everything a caller passes to a
 * `TextInput` goes through, and its own `style` still wins over the box.
 */
export function TextField(props: TextInputProps) {
  const {colors} = useTheme();
  const [focused, setFocused] = useState(false);
  return (
    <TextInput
      {...props}
      onFocus={(event) => {
        setFocused(true);
        props.onFocus?.(event);
      }}
      onBlur={(event) => {
        setFocused(false);
        props.onBlur?.(event);
      }}
      placeholderTextColor={props.placeholderTextColor ?? colors.onSurfaceVariant}
      underlineColorAndroid="transparent"
      style={[
        typeScale.bodyMedium,
        {
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.outlineVariant,
          borderRadius: shape.small,
          color: colors.onSurface,
          paddingHorizontal: spacing.l,
          paddingVertical: spacing.m,
          minHeight: 49,
        },
        props.style,
        focused && {outlineWidth: 2, outlineColor: colors.accent, outlineOffset: 2},
      ]}
    />
  );
}

/** A small state chip: what a row currently is, stated in the accent's own colour. */
export function Chip({label}: {label: string}) {
  const {colors} = useTheme();
  return <Text style={[typeScale.labelLarge, {color: colors.accent}]}>{label}</Text>;
}

/**
 * A row that ends in a switch. The switch carries the row's own name as its label — it is the
 * one label a control sitting this far from its title can give a screen reader.
 */
export function SwitchRow({
  icon,
  title,
  summary,
  value,
  onValueChange,
  disabled,
}: {
  icon: IconName;
  title: string;
  summary?: string;
  value: boolean;
  onValueChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        flexDirection: "row",
        alignItems: "center",
        minHeight: 60,
        paddingVertical: spacing.m,
        gap: spacing.m,
      }}
    >
      <RowIcon name={icon} />
      <View style={{flex: 1, minWidth: 0}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
        {summary != null && <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>{summary}</Text>}
      </View>
      <RNSwitch
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        accessibilityLabel={title}
        accessibilityHint={summary}
        style={{minHeight: 48}}
        trackColor={{
          false: disabled ? colors.disabled : colors.surfaceVariant,
          true: disabled ? colors.disabled : colors.accent,
        }}
        thumbColor={disabled ? colors.onDisabled : value ? colors.onActionPrimary : colors.iconSecondary}
      />
    </View>
  );
}

/** The base of every dialog the settings pages raise: dimmed backdrop, centred card, rounded. */
export function DialogShell({
  title,
  children,
  actions,
  onDismiss,
  scrollBody = false,
  footer,
}: {
  title: string;
  children: React.ReactNode;
  actions: React.ReactNode;
  onDismiss: () => void;
  scrollBody?: boolean;
  /** Pinned under the body, scrolling or not: what the reader must reach whatever the body's length. */
  footer?: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <Modal transparent animationType="fade" onRequestClose={onDismiss} visible>
      <Pressable
        style={{
          flex: 1,
          backgroundColor: "rgba(0,0,0,0.5)",
          alignItems: "center",
          justifyContent: "center",
          padding: spacing.xl,
        }}
        onPress={onDismiss}
      >
        <Pressable
          style={{
          backgroundColor: colors.surface,
          borderRadius: shape.medium,
            padding: spacing.xl,
            width: "100%",
            maxWidth: 360,
          }}
          onPress={(event) => event.stopPropagation()}
        >
          <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>{title}</Text>
          {scrollBody ? (
            <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} style={{maxHeight: 360, marginTop: spacing.m}}>{children}</ScrollView>
          ) : (
            <View style={{marginTop: spacing.m}}>{children}</View>
          )}
          {footer}
          <View style={{flexDirection: "row", justifyContent: "flex-end", marginTop: spacing.l}}>
            {actions}
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

/**
 * A page's own long explanation in a dialog, scrolling when it is longer than the dialog is.
 * What it shows is the page's own text, moved whole rather than rewritten.
 */
export function HelpDialog({
  paragraphs,
  onDismiss,
}: {
  paragraphs: string[];
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  return (
    <DialogShell
      title="Help"
      onDismiss={onDismiss}
      scrollBody
      actions={<Button variant="text" label="Close" onPress={onDismiss} />}
    >
      {paragraphs.map((paragraph, index) => (
        <Text
          key={index}
          style={[
            typeScale.bodyMedium,
            {color: colors.onSurfaceVariant, marginBottom: index < paragraphs.length - 1 ? spacing.m : 0},
          ]}
        >
          {paragraph}
        </Text>
      ))}
    </DialogShell>
  );
}

/**
 * The one toolbar action a page with long explanations carries: a question mark that opens them
 * in a dialog, so the page itself stays rows instead of paragraphs.
 */
export function HelpAction({paragraphs}: {paragraphs: string[]}) {
  const [open, setOpen] = useState(false);
  return (
    <View>
      <IconButton
        name="help"
        accessibilityLabel="Open help"
        onPress={() => setOpen(true)}
      />
      {open && <HelpDialog paragraphs={paragraphs} onDismiss={() => setOpen(false)} />}
    </View>
  );
}

/**
 * The disclosure behind every "Turn on": the app's own explanation of the two permissions lookup
 * mode asks for first, the checkbox before the confirm, and the system dialogs only once the
 * reader has agreed.
 */
export function LookupModeDisclosureDialog({
  onAccept,
  onDismiss,
}: {
  onAccept: () => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  const [agreed, setAgreed] = useState(false);
  return (
    <DialogShell
      title="Turn on lookup mode"
      onDismiss={onDismiss}
      scrollBody
      actions={
        <>
          <Button variant="text" label="Cancel" onPress={onDismiss} />
          <Button variant="text" label="Turn on" disabled={!agreed} onPress={onAccept} />
        </>
      }
      footer={
        // Pinned rather than a child of the scrolling body: the body is long enough on a phone that
        // the checkbox lands below the fold, and "Turn on" then looks broken instead of gated.
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{checked: agreed}}
          onPress={() => setAgreed(!agreed)}
          style={{flexDirection: "row", alignItems: "center", marginTop: spacing.l}}
        >
          <View
            style={{
              width: 18,
              height: 18,
              borderRadius: 2,
              borderWidth: 2,
              borderColor: agreed ? colors.accent : colors.iconSecondary,
              backgroundColor: agreed ? colors.accent : "transparent",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            {agreed && <Icon name="check" size={14} color={colors.onActionPrimary} />}
          </View>
          <Text
            style={[
              typeScale.bodySmall,
              {color: colors.onSurfaceVariant, flex: 1, marginStart: spacing.s},
            ]}
          >
            I understand how these permissions will be used and agree to proceed
          </Text>
        </Pressable>
      }
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
        {
          "Gaten reads Japanese off your screen only while you ask it to. Turning lookup mode on asks for two things:\n\n• Screen capture — Android shows its own dialog, and a privacy chip stays in the status bar while the capture is live. Tap that chip or turn lookup mode off and the capture ends at once; on Android 15 QPR1 and later, locking the screen ends it too.\n• Display over other apps — how the frozen frame, the lasso and the floating bubble are drawn on top of what you are reading.\n\nNothing is recorded and nothing leaves the device: a frame is taken when you start a lookup, recognised and looked up offline, and then dropped — between lookups the screen is not being captured. Gaten does not ask for accessibility at all: no accessibility service is enabled for it, it never inspects another app's accessibility tree, and it does not read notification message text.\n\nOptional, and off until you ask: the stylized-text model download, and WaniKani if you connect a personal access token. Connecting checks that token with wanikani.com, and each kanji you tap asks it for that character's page. With a token connected, Gaten also reads your own study progress from wanikani.com — the level you are on, the reviews due now and in the next 24 hours, and how your items are spread across its review stages — and keeps it on this device for the analytics screen; no lesson or review is ever answered or changed. Your selected sentence, the other words and the screenshot stay on the device."
        }
      </Text>
    </DialogShell>
  );
}

/**
 * True between sending the reader to the "display over other apps" screen and their coming back:
 * the grant is a system screen, so the turn-on they already asked for is carried on afterwards
 * instead of leaving them to start over.
 */
let awaitingOverlayGrant = false;

/**
 * Turns lookup mode on, in the order the permissions can actually be asked for: display over other
 * apps first (a Settings screen the reader has to come back from), then the notification permission
 * — a dialog, and only so lookup mode's own notification is visible, since the capture works
 * without it — and the screen-capture consent last, which is the one Android insists on every
 * session. Declining any of them leaves lookup mode off, and nothing is captured either way.
 */
async function turnOnLookupMode() {
  if (!(await canDrawOverlays())) {
    awaitingOverlayGrant = true;
    void toast("Allow Gaten to display over other apps, then come back here");
    if (!(await openOverlaySettings())) {
      awaitingOverlayGrant = false;
      void toast("Could not open the Display over other apps settings");
    }
    return;
  }
  awaitingOverlayGrant = false;
  if (Platform.OS === "android" && Number(Platform.Version) >= 33) {
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }
  await requestLookupMode();
}

/**
 * The state behind every lookup-mode "Turn on", so a caller needs no dialog of its own and the
 * disclosure is the same one wherever it is raised from. Returns the action that opens it and
 * the dialog element to render.
 */
export function useLookupModeRequest() {
  const [showDisclosure, setShowDisclosure] = useState(false);
  const request = () => setShowDisclosure(true);

  // The one permission that cannot be asked for in a dialog takes the reader to a system screen.
  // Coming back with it granted picks the sequence up where it stopped; coming back without it
  // leaves the button they tapped exactly where it was.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => {
      if (state !== "active" || !awaitingOverlayGrant) return;
      void canDrawOverlays().then((granted) => {
        if (granted) void turnOnLookupMode();
      });
    });
    return () => subscription.remove();
  }, []);

  const dialog = showDisclosure ? (
    <LookupModeDisclosureDialog
      onAccept={() => {
        setShowDisclosure(false);
        void turnOnLookupMode();
      }}
      onDismiss={() => setShowDisclosure(false)}
    />
  ) : null;
  return {request, dialog};
}

/**
 * A standard settings app bar and scrolling body: the page's title, the way back, and — when the
 * page has long explanations of its own — the one toolbar action that holds them, so every row
 * below stays one line.
 *
 * The bottom inset belongs to the scroller below, which is the only thing that knows how much
 * content there is; nothing above pads it as well.
 */
export function SettingsPageScaffold({
  title,
  onClose,
  help,
  children,
}: {
  title: string;
  onClose: () => void;
  help?: string[];
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  return (
    <View style={{flex: 1, backgroundColor: colors.background}}>
      <View
        style={{
          // The Compose top bar is 64 dp tall *below* the status bar. Yoga is border-box, so the
          // bar's own inset padding would eat into a fixed height instead of sitting above it: the
          // bar carries the inset and 64 dp on top of it, exactly like the search screens' bars.
          minHeight: 64 + insets.top,
          paddingTop: insets.top,
          flexDirection: "row",
          alignItems: "center",
        }}
      >
        <IconButton name="arrow_back" accessibilityLabel="Back" onPress={onClose} color={colors.onSurface} />
        <Text
          style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1}]}
          numberOfLines={1}
        >
          {title}
        </Text>
        {help != null && <HelpAction paragraphs={help} />}
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={[
          CONTENT_COLUMN,
          {paddingHorizontal: width < 360 ? 16 : 22, paddingBottom: insets.bottom + spacing.l},
        ]}
      >
        {children}
      </ScrollView>
    </View>
  );
}
