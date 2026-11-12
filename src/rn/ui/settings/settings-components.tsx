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
import React, {useRef, useState} from "react";
import {
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
import {
  markAwaitingAccessibilityReturn,
  openAccessibilitySettings,
  toast,
} from "../../native/GatenPlatform";
import {
  Button,
  Icon,
  IconButton,
  SectionLabel,
} from "../components";
import {DialogShell} from "../DialogShell";
import {DisclosureChevron} from "../Collapsible";
import {PressableScale} from "../PressableScale";
import * as GatenHaptics from "../../native/GatenHaptics";
import {InkIcon, type InkIconName} from "../InkIcon";
import {ScreenHeader} from "../ScreenHeader";
import {useTheme} from "../theme";
import {CONTENT_COLUMN as CONTENT_BLOCK, contentInsetFor, shape, spacing, typeScale} from "../tokens";
import type {IconName} from "../icons";

/**
 * A screen's content column: as wide as the screen up to the app's cap, centred, with the screen's
 * own horizontal rhythm inside it. The pages' scrollers and the hub's own scroller share it, so
 * every settings screen puts its content in the same box.
 */
export const CONTENT_COLUMN: ViewStyle = {...CONTENT_BLOCK, paddingHorizontal: 22};

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
  expanded,
}: {
  icon: IconName;
  inkIcon?: InkIconName;
  title: string;
  summary?: string;
  value?: string | null;
  onPress?: () => void;
  disabled?: boolean;
  expanded?: boolean;
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
      {onPress != null && !inactive && (expanded == null
        ? <InkIcon name="chevron" size={15} color={colors.fine} />
        : <DisclosureChevron expanded={expanded} size={15} color={colors.fine} />)}
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
      accessibilityState={expanded == null ? undefined : {expanded}}
      onPress={onPress}
      style={rowStyle}
    >
      {body}
    </PressableScale>
  );
}

/**
 * The lookup-mode dialog's summary: one lead line and three check rows, in plain words. `**…**`
 * marks the bold lead phrase of a row; the screen-reader label is the same text without markers.
 */
export const DISCLOSURE_SUMMARY_LEAD = "To look things up, Gaten needs Android's accessibility access.";
export const DISCLOSURE_SUMMARY_ROWS: string[] = [
  "**Only when you ask.** Gaten looks at the screen when you start a lookup.",
  "**Stays on your phone.** Nothing from your screen is sent anywhere, and the screenshot is deleted right after.",
  "**Private by design.** It never reads your notifications or types for you.",
];

/** One run of a disclosure line: plain, or the emphasised words drawn in the primary ink. */
export type DisclosureSegment = {text: string; emphasis: boolean};

/**
 * A disclosure line as runs: `**…**` marks the emphasised words. A plain-text run never keeps a
 * marker, so joining the runs gives the reader's wording for a screen-reader label.
 */
export function disclosureLineSegments(line: string): DisclosureSegment[] {
  return line
    .split(/(\*\*[^*]+\*\*)/g)
    .filter(Boolean)
    .map((part) =>
      part.startsWith("**") && part.endsWith("**")
        ? {text: part.slice(2, -2), emphasis: true}
        : {text: part, emphasis: false},
    );
}

/** The plain wording of a line: its runs joined, with no emphasis markers left in it. */
export function disclosureLineText(line: string): string {
  return disclosureLineSegments(line)
    .map((segment) => segment.text)
    .join("");
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
 * The name of the field under it: what the field holds, at the rhythm every page that asks for
 * something uses.
 *
 * It is deliberately the strongest of the two labels a form has — `labelLarge` in the page's own
 * ink, where a section heading is the quiet 12/18 `labelMedium` — so a reader can tell "Lessons"
 * (the group) from "New per day" (the field) at a glance.
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
        typeScale.labelLarge,
        {color: colors.onSurface, marginBottom: spacing.s},
        style,
      ]}
    >
      {label}
    </Text>
  );
}

/**
 * The box a settings page types into: the quiet filled surface the app already uses for fields, the
 * app's own corner and control height, and no outline — not at rest, and not the ring a focused field
 * could wear. Focus is the caret and the keyboard. Everything a caller passes to a `TextInput` goes
 * through, and its own `style` still wins over the text inside the box.
 *
 * The box's inset belongs to the wrapper around the input, not to the input's own style: on Android
 * a `TextInput` carries the platform's EditText padding, which beats the style padding for some
 * input types — measured on the emulator, the value that had been typed sat at the platform's ~8 dp
 * while the placeholder used the box's 16 dp. An input sized inside the wrapper's padding follows
 * both, and the platform's own padding is cleared so it cannot add a second inset.
 */
export function TextField({
  inputRef,
  ...props
}: TextInputProps & {
  /**
   * The input itself, for a screen that has to put the caret in it — the setup help's "Open token
   * form", which hands the reader back to a field they have already started typing into.
   */
  inputRef?: React.Ref<TextInput>;
}) {
  const {colors} = useTheme();
  const input = useRef<TextInput>(null);
  return (
    <Pressable
      // The box is one target: a tap on the inset it keeps for the input focuses the input too.
      // Not an accessibility element itself, so the field keeps the label it already carries.
      accessible={false}
      onPress={() => {
        if (props.editable !== false) input.current?.focus();
      }}
      style={{
        backgroundColor: colors.surfaceVariant,
        borderRadius: shape.small,
        minHeight: 49,
        justifyContent: "center",
        paddingLeft: spacing.l,
        paddingRight: spacing.l,
        paddingTop: spacing.m,
        paddingBottom: spacing.m,
      }}
    >
      <TextInput
        {...props}
        ref={inputRef ?? input}
        placeholderTextColor={props.placeholderTextColor ?? colors.onSurfaceVariant}
        cursorColor={props.cursorColor ?? colors.accent}
        underlineColorAndroid="transparent"
        style={[typeScale.bodyMedium, {color: colors.onSurface, padding: 0}, props.style]}
      />
    </Pressable>
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
        // The switch is the control, so it plays the tick here rather than each screen doing it in
        // its own handler; a disabled switch never reports a change and so never ticks.
        onValueChange={(next) => {
          void GatenHaptics.play("tick").catch(() => {});
          onValueChange(next);
        }}
        disabled={disabled}
        accessibilityLabel={title}
        accessibilityHint={summary}
        style={{minHeight: 48}}
        // Android's thumb is a disc larger than its track, so it reaches past the track onto the
        // row's background: the accent fills the thumb and its quiet container fills the track.
        trackColor={{
          false: disabled ? colors.disabled : colors.surfaceVariant,
          true: disabled ? colors.disabled : colors.accentSubtle,
        }}
        thumbColor={disabled ? colors.onDisabled : value ? colors.accent : colors.iconSecondary}
      />
    </View>
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
      <IconButton name="help" accessibilityLabel="Open help" onPress={() => setOpen(true)} />
      {open && <HelpDialog paragraphs={paragraphs} onDismiss={() => setOpen(false)} />}
    </View>
  );
}

/**
 * The dialog behind every "Turn on": the short summary, the checkbox before the confirm, and system
 * Settings only once the reader has agreed. The checkbox and the two actions are pinned below the
 * body, so they stay in reach at any text size; the body scrolls only when it must.
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
      fitToViewport
      bodyGap={spacing.m}
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
        // Pinned rather than a child of the scrolling body, so an acknowledgement the reader cannot
        // reach never makes "Open settings" look broken instead of gated.
        <PressableScale
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
              // A plain margin: the harness and older engines do not read marginStart.
              {color: colors.onSurfaceVariant, flex: 1, marginLeft: spacing.s},
            ]}
          >
            I understand how this permission is used
          </Text>
        </PressableScale>
      }
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
        {DISCLOSURE_SUMMARY_LEAD}
      </Text>
      <View style={{marginTop: spacing.m, gap: spacing.s}}>
        {DISCLOSURE_SUMMARY_ROWS.map((line) => (
          <View key={line} style={{flexDirection: "row", alignItems: "flex-start", gap: spacing.s}}>
            <View style={{marginTop: 2}}>
              <Icon name="check" size={20} color={colors.accent} />
            </View>
            <Text
              // The marker-free wording: TalkBack must never read a `**` out loud.
              accessibilityLabel={disclosureLineText(line)}
              style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, flex: 1, minWidth: 0}]}
            >
              {disclosureLineSegments(line).map((segment, index) => (
                <Text
                  key={index}
                  style={segment.emphasis ? {color: colors.onSurface, fontWeight: "600"} : undefined}
                >
                  {segment.text}
                </Text>
              ))}
            </Text>
          </View>
        ))}
      </View>
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
        // Arm the return first: the next time the service connects, Gaten comes back to the page
        // that sent the reader here. A build without the bridge resolves quietly, and a marker
        // failure must never keep the Settings screen from opening.
        void markAwaitingAccessibilityReturn()
          .catch(() => {})
          .then(() => {
            void openAccessibilitySettings().then((opened) => {
              if (!opened) void toast("Could not open Accessibility Settings");
            });
          });
      }}
      onDismiss={() => setShowDisclosure(false)}
    />
  ) : null;
  // [open] lets an onboarding page keep its own done state false while the dialog owns the scene.
  return {request, dialog, open: showDisclosure};
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
      <View style={{paddingTop: insets.top}}>
        <ScreenHeader
          title={title}
          onBack={onClose}
          trailing={help != null ? <HelpAction paragraphs={help} /> : undefined}
        />
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={[
          CONTENT_COLUMN,
          {paddingHorizontal: contentInsetFor(width), paddingBottom: insets.bottom + spacing.l},
        ]}
      >
        {children}
      </ScrollView>
    </View>
  );
}
