/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The pieces every settings page is built from: the scaffold with its top bar, the row of a
 * group, the group itself, the dialogs a page raises, and the screen-access card with the
 * disclosure flow behind its "Turn on".
 *
 * These are the RN ports of the shared composables in `SettingsScreen.kt`; the pages in
 * `ui/settings/` assemble them, and the copy stays in each page. Cards, buttons, icons and the
 * press-scale come from `ui/components` / `ui/PressableScale`, so every tab draws them alike.
 */
import React, {useState} from "react";
import {
  Modal,
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
import {openAccessibilitySettings, toast} from "../../native/GatenPlatform";
import {
  Button,
  Card,
  Icon,
  IconButton,
  PressableCard,
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

/**
 * A settings glyph in the preview's neutral icon tile, with an app-native fallback for destinations
 * that are not present in the preview.
 */
export function RowIcon({name, tile = false, inkName}: {name: IconName; tile?: boolean; inkName?: InkIconName}) {
  const {colors} = useTheme();
  if (!tile) {
    return <Icon name={name} size={24} color={colors.iconSecondary} />;
  }
  return (
    <View
      style={{
        width: 37,
        height: 37,
        alignItems: "center",
        justifyContent: "center",
        borderRadius: shape.settingsIcon,
        backgroundColor: colors.surfaceVariant,
      }}
    >
      {inkName
        ? <InkIcon name={inkName} size={20} color={colors.iconPrimary} />
        : <Icon name={name} size={21} color={colors.iconPrimary} />}
    </View>
  );
}

/** A contained group uses the shared 20dp container radius. */
const SURFACE_RADIUS: ViewStyle = {borderRadius: shape.medium};

/** A settings tile uses the design language's neutral secondary surface in both themes. */
export function SettingsTile({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Card style={[SURFACE_RADIUS, {backgroundColor: colors.surfaceVariant}, style]}>
      {children}
    </Card>
  );
}

/** A group of rows inside no surface of its own, for callers that give each row its own card. */
const FLUSH_ROW: ViewStyle = {backgroundColor: "transparent", borderRadius: 0, elevation: 0};

/**
 * A named group of rows, under the quiet name of what they are: a sentence-case label, then either
 * one rounded surface holding the rows or — when the caller passes `bare` — the rows themselves,
 * each of which then brings its own card.
 */
export function SettingsGroup({
  title,
  children,
  style,
  bare = false,
}: {
  title: string;
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  bare?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View style={style}>
      <SectionLabel
        style={[
          typeScale.labelMedium,
          {
            color: colors.onSurfaceVariant,
            textTransform: "none",
            marginBottom: 9,
          },
        ]}
      >
        {title}
      </SectionLabel>
      {bare ? (
        <View style={{gap: 9}}>{children}</View>
      ) : (
        <SettingsTile>{children}</SettingsTile>
      )}
    </View>
  );
}

/**
 * One row of a group: the glyph, what the page is, what it currently amounts to on the right, and a
 * chevron when it goes somewhere. The value sits on the right in one quiet line, and a row that only
 * states something is not made to look tappable.
 *
 * `surface` gives the row its own rounded card rather than letting it sit flush in its group's
 * surface — how the Settings hub lists its pages, one calm card per row.
 */
export function SettingsRow({
  icon,
  inkIcon,
  title,
  summary,
  value,
  onPress,
  surface = false,
}: {
  icon: IconName;
  inkIcon?: InkIconName;
  title: string;
  summary: string;
  value?: string | null;
  onPress?: () => void;
  surface?: boolean;
}) {
  const {colors} = useTheme();
  const padding = {
    paddingHorizontal: 11,
    paddingVertical: 12,
  };
  const contentStyle: ViewStyle = surface
    ? {flex: 1, minHeight: 70, justifyContent: "center"}
    : {};
  const rowSurface: ViewStyle = {
    minHeight: 72,
    borderRadius: shape.settings,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.outlineVariant,
    elevation: 0,
    shadowOpacity: 0,
  };
  const body = (
    <View style={{flexDirection: "row", alignItems: "center"}}>
      <RowIcon name={icon} tile={surface} inkName={inkIcon} />
      <View
        style={{
          flex: 1,
          minWidth: 0,
          marginStart: 12,
        }}
      >
        <Text
          style={[
            typeScale.bodyLarge,
          {color: colors.onSurface, fontWeight: "700", letterSpacing: -0.3},
          ]}
        >
          {title}
        </Text>
        <Text
          style={[
            typeScale.bodySmall,
            {color: colors.onSurfaceVariant, marginTop: 5},
          ]}
        >
          {summary}
        </Text>
      </View>
      <View style={{flexDirection: "row", alignItems: "center", maxWidth: "36%", flexShrink: 1}}>
        {value != null && (
          <Text
            numberOfLines={1}
            ellipsizeMode="tail"
            style={[
              typeScale.labelMedium,
              {color: colors.onSurfaceVariant, maxWidth: 132, flexShrink: 1},
            ]}
          >
            {value}
          </Text>
        )}
        {onPress != null && (
          <View style={{marginStart: value != null ? 7 : 0}}>
            <InkIcon name="chevron" size={15} color={colors.fine} />
          </View>
        )}
      </View>
    </View>
  );
  if (onPress == null) {
    return surface ? (
      <Card style={rowSurface}>
        <View style={[padding, contentStyle]}>{body}</View>
      </Card>
    ) : (
      <View style={padding}>{body}</View>
    );
  }
  return (
    <PressableCard
      accessibilityLabel={`${title}${value != null ? `. ${value}` : onPress != null ? ". Open settings" : ""}. ${summary}`}
      onPress={onPress}
      style={surface ? rowSurface : FLUSH_ROW}
    >
      <View style={[padding, contentStyle]}>{body}</View>
    </PressableCard>
  );
}

/**
 * The state of screen access, for the page that owns it: a calm rounded surface either way, a quiet
 * glyph, what it means, and — while it is off — the one action that changes it. Being off is a setup
 * state, not an error, so nothing here is painted with the error roles.
 */
export function ScreenAccessCard({
  enabled,
  onTurnOn,
}: {
  enabled: boolean;
  onTurnOn: () => void;
}) {
  const {colors} = useTheme();
  return (
    <SettingsTile>
      <View style={{padding: spacing.xl}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Icon
            name={enabled ? "check_circle" : "shield"}
            size={24}
              color={enabled ? colors.success : colors.iconSecondary}
          />
          <Text
            style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1, marginStart: spacing.m}]}
          >
            {enabled ? "Screen access is on" : "Screen access is off"}
          </Text>
        </View>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, marginTop: spacing.m}]}>
          {enabled
            ? "Circle any Japanese text on screen to look it up."
            : "Gaten cannot read the screen until you turn it on."}
        </Text>
        {!enabled && (
          <View style={{marginTop: spacing.l, alignSelf: "flex-start"}}>
            <Button label="Turn on" onPress={onTurnOn} />
          </View>
        )}
      </View>
    </SettingsTile>
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
 * The box a settings page types into: a white/dark surface inside the tinted settings tile, with a
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
  summary: string;
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
        paddingHorizontal: spacing.l + spacing.xs,
        paddingVertical: spacing.l,
      }}
    >
      <RowIcon name={icon} />
      <View style={{flex: 1, marginStart: spacing.m}}>
        <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>{title}</Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>{summary}</Text>
      </View>
      <RNSwitch
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        accessibilityLabel={title}
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
}: {
  title: string;
  children: React.ReactNode;
  actions: React.ReactNode;
  onDismiss: () => void;
  scrollBody?: boolean;
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
            <ScrollView style={{maxHeight: 360, marginTop: spacing.m}}>{children}</ScrollView>
          ) : (
            <View style={{marginTop: spacing.m}}>{children}</View>
          )}
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
 * The disclosure behind every "Turn on": the app's own explanation of the accessibility service
 * first, the checkbox before the confirm, and system Settings only once the reader has agreed.
 */
export function AccessibilityDisclosureDialog({
  onAccept,
  onDismiss,
}: {
  onAccept: () => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  const [agreed, setAgreed] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  return (
    <DialogShell
      title="Grant Accessibility Permission"
      onDismiss={onDismiss}
      scrollBody
      actions={
        <>
          <Button variant="text" label="Cancel" onPress={onDismiss} />
          <Button variant="text" label="Turn on" disabled={!agreed} onPress={onAccept} />
        </>
      }
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
        {
          "Gaten uses the accessibility service to take a screenshot of the screen only when you start a lookup, and to close the lookup when something interrupts it. The screenshot never leaves your device."
        }
      </Text>
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
          style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, flex: 1, marginStart: spacing.s}]}
        >
          I understand how this permission will be used and agree to proceed
        </Text>
      </Pressable>
      <Button
        variant="text"
        label={showDetails ? "Hide the details" : "Read the details"}
        onPress={() => setShowDetails(!showDetails)}
      />
      {showDetails && (
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
          {
            "Gaten uses the accessibility service to capture a screenshot when you start a lookup and detect interruptions so the lookup closes automatically. The screenshot never leaves your device — Japanese text is recognised and looked up entirely offline. The service does not read notification messages or type for you.\n\nOptional, and off until you ask: the stylized-text model download, and WaniKani if you connect a personal access token. Connecting checks that token with wanikani.com, and each kanji you tap asks it for that character's page. With a token connected, Gaten also reads your own study progress from wanikani.com — the level you are on, the reviews due now and in the next 24 hours, and how your items are spread across its review stages — and keeps it on this device for the analytics screen; no lesson or review is ever answered or changed. Your selected sentence, the other words and the screenshot stay on the device."
          }
        </Text>
      )}
    </DialogShell>
  );
}

/**
 * The state behind every screen-access "Turn on", so a caller needs no dialog of its own and the
 * disclosure is the same one wherever it is raised from. Returns the action that opens it and
 * the dialog element to render, exactly as `rememberScreenAccessRequest` returned its action.
 */
export function useScreenAccessRequest() {
  const [showDisclosure, setShowDisclosure] = useState(false);
  const request = () => setShowDisclosure(true);
  const dialog = showDisclosure ? (
    <AccessibilityDisclosureDialog
      onAccept={() => {
        setShowDisclosure(false);
        openAccessibilitySettings().then((opened) => {
          if (!opened) {
            void toast("Could not open Accessibility Settings");
          }
        });
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
