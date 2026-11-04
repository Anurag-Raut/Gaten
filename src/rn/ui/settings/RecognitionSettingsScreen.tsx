/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Text recognition: the reader's manual engine choice, and the explicit confirmed downloads that put
 * an engine on the device.
 *
 * Four choices, no classifier. ML Kit is bundled and is the default: it reads out of the box with
 * nothing to download. The other three — PP-OCRv6 Medium (recommended) for ordinary screen and novel
 * text, PP-OCRv6 Manga for stylized lettering and video thumbnails, and Manga OCR — download only
 * when their own button is pressed; each tap asks for confirmation first, warning that Wi-Fi is
 * recommended and mobile data may incur charges, then works offline. An unavailable engine still
 * selects, and lookups fall back to the bundled ML Kit. Whether a download is running is read from
 * the native module, so leaving and returning does not show a download as finished.
 */
import React, {useEffect, useRef, useState} from "react";
import {Alert, AppState, Text, View} from "react-native";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenRecognition from "../../native/GatenRecognition";
import type {
  EngineStatus,
  RecognitionEngine,
  RecognitionSettings,
} from "../../native/GatenRecognition";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {
  OutlinedButton,
  RowIcon,
  SettingsGroup,
  SettingsPageScaffold,
} from "./settings-components";

const ML_KIT: RecognitionEngine = "ml_kit";

const HELP_PARAGRAPHS = [
  "Every lookup reads the selection with the engine you choose here. Google ML Kit's Japanese recognizer is included with Gaten, so text is read out of the box with nothing to download.",
  "The other engines are optional and download only when you press Download and confirm, then work offline. PP-OCRv6 Medium is recommended for ordinary screen and novel text; PP-OCRv6 Manga suits manga lettering, stylized fonts and outlined video thumbnails; Manga OCR suits Japanese and stylized text. Any connection works; Wi-Fi is recommended, and mobile data may incur charges.",
  "PP-OCRv6 engines read a single line at a time. For a multiline selection, or a vertical result that reads poorly, choose Manga OCR.",
  "ML Kit finds the lines in every mode. When a downloaded engine cannot read a selection — it is not installed yet, or its read fails or holds no Japanese — ML Kit reads it instead.",
];

/** The pinned download size as decimal MB, matching the size named in the docs and NOTICE. */
function formatMegabytes(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

/** What an engine is for, so the row says more than its name. */
function engineUse(engine: EngineStatus): string {
  switch (engine.id) {
    case "ml_kit":
      return "Japanese text";
    case "medium":
      return "Screen and novels";
    case "manga":
      return "Manga, stylized and thumbnails";
    default:
      return "Japanese and stylized text";
  }
}

/** What is actually on the device: the bundled default, an installed download, or neither. */
function engineState(engine: EngineStatus): string {
  if (engine.bundled) return "bundled · works offline";
  return engine.installed ? "installed · works offline" : "not installed";
}

/** The note under a selected engine that is not installed: the bundled ML Kit reads meanwhile. */
const ML_KIT_FALLBACK_NOTE = "Not installed · ML Kit reads text in the meantime.";

/** The "Recommended" marker beside the engine it names. */
function RecommendedBadge({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        backgroundColor: colors.accentSubtle,
        borderRadius: 6,
        paddingHorizontal: spacing.s,
        paddingVertical: 1,
      }}
    >
      <Text style={[typeScale.labelSmall, {color: colors.onPrimaryContainer}]}>{label}</Text>
    </View>
  );
}

/** One selectable engine: title, one-line state, and a check on the selected one. */
function ModeRow({
  title,
  summary,
  badge,
  selected,
  disabled,
  onPress,
}: {
  title: string;
  summary: string;
  badge?: string;
  selected: boolean;
  disabled?: boolean;
  onPress: () => void;
}) {
  const {colors} = useTheme();
  return (
    <PressableScale
      accessibilityRole="radio"
      accessibilityState={{selected, disabled}}
      accessibilityLabel={badge != null ? `${title}, ${badge}` : title}
      accessibilityHint={summary}
      disabled={disabled}
      onPress={onPress}
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.m,
        minHeight: 60,
        paddingVertical: spacing.m,
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <RowIcon name="text_snippet" />
      <View style={{flex: 1, minWidth: 0}}>
        <View style={{flexDirection: "row", alignItems: "center", gap: spacing.s}}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
          {badge != null && <RecommendedBadge label={badge} />}
        </View>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          {summary}
        </Text>
      </View>
      {selected && <Icon name="check" size={20} color={colors.accent} />}
    </PressableScale>
  );
}

/** One engine: the choice row, and its download button while it is not installed. */
function EngineRow({
  engine,
  selected,
  downloading,
  busy,
  disabled,
  onSelect,
  onDownload,
}: {
  engine: EngineStatus;
  selected: boolean;
  downloading: boolean;
  busy: boolean;
  disabled?: boolean;
  onSelect: () => void;
  onDownload: () => void;
}) {
  const {colors} = useTheme();
  const size = formatMegabytes(engine.downloadBytes);
  const summary = `${engineUse(engine)} · ${engineState(engine)}`;
  return (
    <View>
      <ModeRow
        title={engine.name}
        summary={summary}
        badge={engine.recommended ? "Recommended" : undefined}
        selected={selected}
        disabled={disabled}
        onPress={onSelect}
      />
      {!engine.installed && (
        <View style={{marginBottom: spacing.m, marginStart: 22 + spacing.m}}>
          <OutlinedButton
            label={downloading ? "Downloading…" : `Download · ${size}`}
            disabled={downloading || busy || disabled === true}
            onPress={onDownload}
          />
          {selected && (
            <Text
              style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}
            >
              {ML_KIT_FALLBACK_NOTE}
            </Text>
          )}
        </View>
      )}
    </View>
  );
}

export function RecognitionSettingsScreen({onClose}: {onClose: () => void}) {
  const [settings, setSettings] = useState<RecognitionSettings | null>(null);
  const [requested, setRequested] = useState<RecognitionEngine | null>(null);
  // One mode save at a time: the native writes run on a multi-threaded IO dispatcher, so two quick
  // taps must not race which persisted value wins.
  const [saving, setSaving] = useState(false);
  const readSeq = useRef(0);
  const mounted = useRef(true);
  // Guards one download confirmation at a time; Android fires Alert's onDismiss after a button press
  // too, so the button paths clear this before acting and onDismiss only handles a real dismissal.
  const confirmOpen = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // One read applied only while it is still the newest: a read that started before a switch must
  // not put the old mode or download state back on screen.
  const refresh = () => {
    readSeq.current += 1;
    const seq = readSeq.current;
    void GatenRecognition.getRecognitionSettings()
      .then((next) => {
        if (seq === readSeq.current && mounted.current) setSettings(next);
      })
      .catch(() => {
        if (seq === readSeq.current && mounted.current) {
          void GatenPlatform.toast("Could not read text recognition settings.");
        }
      });
  };

  useEffect(() => {
    refresh();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") refresh();
    });
    return () => {
      readSeq.current += 1;
      subscription.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const nativeDownloading = settings?.downloading ?? [];
  const downloadingKey = nativeDownloading.join(",");

  // A download is native module state that outlives this screen. While one is running — including
  // one started before the screen opened — read it at a low rate so completion, or a module torn
  // down under it, is observed; the interval clears itself once nothing is downloading.
  useEffect(() => {
    if (downloadingKey === "") return;
    const timer = setInterval(refresh, 2000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [downloadingKey]);

  const selectMode = (mode: RecognitionEngine) => {
    if (settings == null || settings.mode === mode || saving) return;
    // Discard any read that started before this choice: it would put the old mode back.
    readSeq.current += 1;
    setSaving(true);
    setSettings({...settings, mode});
    void GatenRecognition.setRecognitionMode(mode)
      .catch(() => {
        void GatenPlatform.toast("Could not save the recognition engine.");
      })
      // Re-read once the save has settled, so the screen converges on what was persisted.
      .then(() => refresh())
      .finally(() => {
        if (mounted.current) setSaving(false);
      });
  };

  /** The confirmed download: the only path that reaches the network. */
  const startDownload = (engine: RecognitionEngine) => {
    if (!mounted.current) return;
    if (saving || nativeDownloading.includes(engine)) {
      setRequested(null);
      return;
    }
    setRequested(engine);
    void GatenRecognition.downloadRecognitionEngine(engine)
      .then(() => {
        // Re-read rather than trust the response: a mode chosen while the download ran is the
        // newest truth, and refresh() applies only reads it started itself.
        refresh();
      })
      .catch((error: unknown) => {
        const message =
          error instanceof Error && error.message
            ? error.message
            : "The model could not be downloaded.";
        void GatenPlatform.toast(message);
        refresh();
      })
      .finally(() => {
        if (mounted.current) setRequested(null);
      });
  };

  /**
   * A Download tap: nothing is fetched until the reader confirms. The chosen engine is marked
   * pending immediately, so every other row and button is disabled while the alert is up.
   */
  const requestDownload = (engine: EngineStatus) => {
    if (requested !== null || saving || confirmOpen.current || nativeDownloading.includes(engine.id)) {
      return;
    }
    confirmOpen.current = true;
    setRequested(engine.id);
    Alert.alert(
      `Download ${engine.name}?`,
      `${engine.name} is ${formatMegabytes(engine.downloadBytes)}. Wi-Fi is recommended. Downloading on mobile data may incur charges.`,
      [
        {
          text: "Cancel",
          style: "cancel",
          onPress: () => {
            confirmOpen.current = false;
            if (mounted.current) setRequested(null);
          },
        },
        {
          text: "Download",
          onPress: () => {
            confirmOpen.current = false;
            // An alert left over from a screen that has gone away must not start a transfer.
            if (mounted.current) startDownload(engine.id);
          },
        },
      ],
      {
        cancelable: true,
        // Only reached when the alert was dismissed without a button; a pressed button has already
        // cleared the flag and taken ownership of the pending state.
        onDismiss: () => {
          if (!confirmOpen.current) return;
          confirmOpen.current = false;
          if (mounted.current) setRequested(null);
        },
      },
    );
  };

  const mode = settings?.mode ?? ML_KIT;
  const engines = settings?.engines ?? [];
  const busy = requested !== null;

  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={HELP_PARAGRAPHS}
    >
      <SettingsGroup title="Reader" style={{marginTop: spacing.s}}>
        {engines.map((engine) => (
          <EngineRow
            key={engine.id}
            engine={engine}
            selected={mode === engine.id}
            downloading={requested === engine.id || nativeDownloading.includes(engine.id)}
            busy={busy && requested !== engine.id}
            disabled={saving}
            onSelect={() => selectMode(engine.id)}
            onDownload={() => requestDownload(engine)}
          />
        ))}
      </SettingsGroup>
    </SettingsPageScaffold>
  );
}
