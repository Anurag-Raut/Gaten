/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The pieces every settings page is built from: the scaffold with its top bar, the row of a
 * group, the group itself, the dialogs a page raises, and the lookup-mode disclosure behind the
 * switch that needs it.
 *
 * These are the RN ports of the shared composables in `SettingsScreen.kt`; the pages in
 * `ui/settings/` assemble them, and the copy stays in each page. Buttons, icons and the
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

/** A neutral settings glyph using the hub's existing icon set; [color] is for a disabled row. */
export function RowIcon({name, inkName, color}: {name: IconName; inkName?: InkIconName; color?: string}) {
  const {colors} = useTheme();
  const ink = color ?? colors.iconSecondary;
  return inkName
    ? <InkIcon name={inkName} size={22} color={ink} />
    : <Icon name={name} size={24} color={ink} />;
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
 * A [disabled] row — one whose action is busy — draws dimmed, carries no chevron, and reports
 * itself disabled to a screen reader instead of looking tappable and doing nothing.
 */
export function SettingsRow({
  icon,
  inkIcon,
  title,
  summary,
  value,
  onPress,
  disabled,
}: {
  icon: IconName;
  inkIcon?: InkIconName;
  title: string;
  summary?: string;
  value?: string | null;
  onPress?: () => void;
  disabled?: boolean;
}) {
  const {colors} = useTheme();
  const inactive = disabled === true;
  const rowStyle: ViewStyle = {
    minHeight: 60,
    paddingVertical: spacing.m,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
  };
  const body = (
    <>
      <RowIcon name={icon} inkName={inkIcon} color={inactive ? colors.onDisabled : undefined} />
      <View style={{flex: 1, minWidth: 0}}>
        <Text style={[typeScale.titleMedium, {color: inactive ? colors.onDisabled : colors.onSurface}]}>
          {title}
        </Text>
        {summary != null && (
          <Text style={[typeScale.bodySmall, {color: inactive ? colors.onDisabled : colors.onSurfaceVariant, marginTop: spacing.xs}]}>
            {summary}
          </Text>
        )}
      </View>
      {value != null && (
        <Text style={[typeScale.labelMedium, {color: inactive ? colors.onDisabled : colors.onSurfaceVariant, maxWidth: "40%", flexShrink: 1, textAlign: "right"}]}>
          {value}
        </Text>
      )}
      {onPress != null && !inactive && <InkIcon name="chevron" size={15} color={colors.fine} />}
    </>
  );
  if (onPress == null || inactive) {
    return (
      <View style={rowStyle} accessibilityState={inactive ? {disabled: true} : undefined}>
        {body}
      </View>
    );
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

/**
 * The disclosure's facts, grouped and in the order the reader meets them: what the service sees,
 * what stays on the device, what is optional and off until asked, and what happens next. Every
 * consequence stays a visible line here — none of it is shortened into a summary and none of it
 * moves behind Help, which carries operational detail only.
 */
const DISCLOSURE_BLOCKS: {heading: string; lines: string[]}[] = [
  {
    heading: "Screen access",
    lines: [
      "Gaten captures the screen when you start a lookup.",
      "Gaten closes lookup when it is interrupted.",
    ],
  },
  {
    heading: "On this device",
    lines: [
      "Screenshots and selected text stay on this device.",
      "Japanese recognition and dictionary lookup work offline.",
      "The screenshot is discarded after lookup.",
      "Nothing is captured between lookups.",
      "The service does not read notification messages.",
      "The service does not type for you.",
    ],
  },
  {
    heading: "Optional connections",
    lines: [
      "The included Manga OCR model is prepared automatically on first lookup.",
      "WaniKani connects only when you add a token.",
      "The token is checked with wanikani.com.",
      "A tapped kanji requests its reference page.",
      "WaniKani study progress is saved on this device for Analytics.",
      "Progress includes your level, due reviews and review stages.",
      "Due reviews cover now and the next 24 hours.",
      "Gaten never answers or changes WaniKani lessons or reviews.",
      "Your screenshot, sentence and other words are not sent to WaniKani.",
    ],
  },
  {
    heading: "Next step",
    lines: [
      "Enable Gaten in Android accessibility settings.",
      "Return here to check lookup mode.",
    ],
  },
];

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
  bodyGap = spacing.m,
}: {
  title: string;
  children: React.ReactNode;
  actions: React.ReactNode;
  onDismiss: () => void;
  scrollBody?: boolean;
  /** Pinned under the body, scrolling or not: what the reader must reach whatever the body's length. */
  footer?: React.ReactNode;
  /** The gap between the title and the body; the disclosure pulls its first heading closer. */
  bodyGap?: number;
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
            <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} style={{maxHeight: 360, marginTop: bodyGap}}>{children}</ScrollView>
          ) : (
            <View style={{marginTop: bodyGap}}>{children}</View>
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
 * The disclosure behind every "Turn on": the app's own explanation of the accessibility service
 * lookup mode needs first, the checkbox before the confirm, and system Settings only once the
 * reader has agreed. The facts scroll in four headed groups while the acknowledgement and the two
 * actions stay pinned below them, so a consequence is never what the confirmation hides.
 */
export function LookupModeDisclosureDialog({
  onAccept,
  onDismiss,
}: {
  onAccept: () => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  const {fontScale} = useWindowDimensions();
  const [agreed, setAgreed] = useState(false);
  // At the largest system text the two actions no longer fit on one line without shrinking below
  // their touch target, so each takes a full-width line instead.
  const stackedActions = fontScale >= 1.6;
  return (
    <DialogShell
      title="Turn on lookup mode"
      onDismiss={onDismiss}
      scrollBody
      bodyGap={spacing.s}
      actions={
        <View
          style={{
            width: "100%",
            flexDirection: stackedActions ? "column" : "row",
            justifyContent: "flex-end",
            alignItems: stackedActions ? "stretch" : "flex-end",
            gap: spacing.s,
          }}
        >
          <Button variant="text" label="Cancel" onPress={onDismiss} fullWidth={stackedActions} />
          <Button
            variant="text"
            label="Open settings"
            disabled={!agreed}
            fullWidth={stackedActions}
            onPress={onAccept}
          />
        </View>
      }
      footer={
        // Pinned rather than a child of the scrolling body: the four groups are longer than any
        // phone, and an acknowledgement the reader cannot reach makes "Open settings" look broken
        // instead of gated.
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{checked: agreed}}
          onPress={() => setAgreed(!agreed)}
          style={{flexDirection: "row", alignItems: "center", minHeight: 48, marginTop: spacing.l}}
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
              typeScale.description,
              {color: colors.onSurfaceVariant, flex: 1, marginStart: spacing.s},
            ]}
          >
            I understand how this permission is used
          </Text>
        </Pressable>
      }
    >
      {DISCLOSURE_BLOCKS.map((block, index) => (
        <View key={block.heading} style={index > 0 ? {marginTop: spacing.l} : undefined}>
          <Text style={[typeScale.labelMedium, {color: colors.onSurface}]}>{block.heading}</Text>
          {block.lines.map((line) => (
            <Text
              key={line}
              style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}
            >
              {line}
            </Text>
          ))}
        </View>
      ))}
    </DialogShell>
  );
}

/**
 * The state behind every lookup-mode "Turn on", so a caller needs no dialog of its own and the
 * disclosure is the same one wherever it is raised from. Returns the action that opens it and
 * the dialog element to render.
 */
export function useLookupModeRequest() {
  const [showDisclosure, setShowDisclosure] = useState(false);
  const request = () => setShowDisclosure(true);
  const dialog = showDisclosure ? (
    <LookupModeDisclosureDialog
      onAccept={() => {
        setShowDisclosure(false);
        // The only switch that exists is Android's own accessibility service list, so this is the
        // screen the reader enables Gaten on. `false` means nothing on the device took the intent.
        void openAccessibilitySettings().then((opened) => {
          if (!opened) void toast("Could not open Accessibility Settings");
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
