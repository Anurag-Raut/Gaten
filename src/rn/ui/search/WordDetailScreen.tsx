/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The word detail: the headword card, the numbered meanings, the kanji strip, and the pinned
 * Add-to-Anki bar. The entry is resolved by id from the shared search store — never carried
 * through the route — so a restored page resolves as soon as its search has answered.
 */
import React, {useCallback, useMemo} from "react";
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useFocusEffect} from "@react-navigation/native";
import {Button, Card, CommonChip, Icon, IconButton, KanjiStrip, distinctIdeographs} from "../components";
import {useTheme} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import * as GatenAnki from "../../native/GatenAnki";
import {hitFor, useSearchState} from "./searchStore";
import {SearchNotice} from "./SearchScreen";
import {useAnkiExport, type AnkiExport, type ExportState} from "./useAnkiExport";

/** The capsule a short pill takes: RN clamps it to half the box, so one number fits every pill. */
const CAPSULE = 999;

export interface SearchWordScreenProps {
  entryId: number;
  onBack: () => void;
  onOpenKanji: (character: string) => void;
  onOpenSettingsPage: (page: "anki" | "wanikani") => void;
}

export function SearchWordScreen({
  entryId,
  onBack,
  onOpenKanji,
  onOpenSettingsPage,
}: SearchWordScreenProps) {
  const {colors} = useTheme();
  const state = useSearchState();
  const hit = useMemo(() => hitFor(entryId), [state.hits, entryId]);
  const anki = useAnkiExport();
  const exportState = anki.exportFor(entryId);

  // Resume of the entry: status, settings and duplicate state are read again after any absence —
  // on the way in, and again every time the reader comes back to the page. Keyed to the entry and
  // not to the row it was resolved from: a search answering while this page is open replaces the
  // row, and the Compose page did not re-check the duplicate state for that.
  useFocusEffect(
    useCallback(() => {
      const entry = hitFor(entryId);
      if (entry) {
        void anki.prepareExport(entry);
      }
    }, [entryId]),
  );

  if (!hit) {
    return (
      <View style={[styles.root, {backgroundColor: colors.background}]}>
        <WordAppBar title="Search" onBack={onBack} />
        <View style={styles.body}>
          {state.status === "searching" ? (
            <SearchNotice spin title="Searching the dictionary…" />
          ) : (
            <SearchNotice
              title="This entry is no longer in the results"
              body="The search was run again and no longer has it. Go back for the results you have."
            />
          )}
        </View>
      </View>
    );
  }

  return (
    <View style={[styles.root, {backgroundColor: colors.background}]}>
      <WordAppBar title={hit.primary} onBack={onBack} />
      <ScrollView
        style={styles.body}
        contentContainerStyle={styles.scrollContent}
      >
        <HeadwordCard hit={hit} />
        <MeaningsCard hit={hit} />
        <View style={styles.strip}>
          <KanjiStrip
            characters={distinctIdeographs(hit.primary)}
            selected={null}
            onKanji={onOpenKanji}
            haptic="none"
          />
        </View>
        <View style={[styles.flowRow, styles.settingsRow]}>
          <Button
            variant="text"
            label="Anki settings"
            onPress={() => onOpenSettingsPage("anki")}
          />
          <Button
            variant="text"
            label="WaniKani settings"
            onPress={() => onOpenSettingsPage("wanikani")}
          />
        </View>
      </ScrollView>
      <AddToAnkiBar entry={hit} anki={anki} exportState={exportState} />
    </View>
  );
}

function WordAppBar({title, onBack}: {title: string; onBack: () => void}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      style={[
        styles.appBar,
        {
          backgroundColor: colors.surface,
          // 64dp below the status bar, as the Kotlin's top bar is: padding alone would leave the
          // row centred in what is left of a fixed 64.
          minHeight: 64 + insets.top,
          paddingTop: insets.top,
        },
      ]}
    >
      <IconButton name="arrow_back" accessibilityLabel="Back" onPress={onBack} />
      <Text
        numberOfLines={1}
        style={[typeScale.titleLarge, styles.appBarTitle, {color: colors.onSurface}]}
      >
        {title}
      </Text>
    </View>
  );
}

function HeadwordCard({hit}: {hit: SearchResult}) {
  const {colors} = useTheme();
  return (
    <Card style={styles.card}>
      <View style={styles.flowRow}>
        <Text style={[typeScale.displaySmall, {color: colors.onSurface}]}>{hit.primary}</Text>
        {hit.common && <CommonChip />}
      </View>
      {hit.kana.length > 0 && (
        <Text
          numberOfLines={1}
          accessibilityLabel={hit.kana.join("・")}
          style={[typeScale.titleMedium, styles.line, {color: colors.onSurfaceVariant}]}
        >
          {hit.kana.join("・")}
        </Text>
      )}
      {hit.kanji.length > 1 && (
        <Text style={[typeScale.bodyMedium, styles.line, {color: colors.onSurfaceVariant}]}>
          Also written: {hit.kanji.slice(1).join("・")}
        </Text>
      )}
      {(hit.match === "NORMALIZED" || hit.match === "LOOSE") && (
        <Text style={[typeScale.labelMedium, styles.line, {color: colors.primary}]}>
          from {hit.form}
        </Text>
      )}
    </Card>
  );
}

function MeaningsCard({hit}: {hit: SearchResult}) {
  const {colors} = useTheme();
  return (
    <Card style={styles.card}>
      <Text style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}>Meaning</Text>
      {hit.glosses.length === 0 ? (
        <Text
          style={[
            typeScale.bodyMedium,
            styles.line,
            {color: colors.onSurfaceVariant},
          ]}
        >
          The dictionary has no gloss for this entry.
        </Text>
      ) : (
        <View style={styles.glosses}>
          {hit.glosses.map((gloss, index) => (
            <View key={index} style={styles.glossRow}>
              <Text
                style={[typeScale.bodyLarge, styles.glossNumber, {color: colors.onSurfaceVariant}]}
              >
                {index + 1}.
              </Text>
              <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1}]}>{gloss}</Text>
            </View>
          ))}
        </View>
      )}
      {hit.pos.length > 0 && (
        <>
          <Text style={[typeScale.labelLarge, styles.posCaption, {color: colors.onSurfaceVariant}]}>
            Part of speech
          </Text>
          <View style={styles.posRow}>
            {hit.pos.map((pos) => (
              <PosPill key={pos} label={pos} />
            ))}
          </View>
        </>
      )}
    </Card>
  );
}

/** One part of speech: a quiet capsule — named, but not competing with the word's own colour. */
function PosPill({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.posPill, {backgroundColor: colors.surfaceVariant}]}>
      <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>{label}</Text>
    </View>
  );
}

function AddToAnkiBar({
  entry,
  anki,
  exportState,
}: {
  entry: SearchResult;
  anki: AnkiExport;
  exportState: ExportState;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();

  const share = async () => {
    try {
      const shared = await anki.shareToAnkiDroid(entry);
      if (!shared) {
        ToastAndroid.show("AnkiDroid cannot be opened for sharing", ToastAndroid.SHORT);
      }
    } catch {
      ToastAndroid.show("AnkiDroid cannot be opened for sharing", ToastAndroid.SHORT);
    }
  };

  const buttonLabel = (() => {
    if (anki.celebrating === entry.id) {
      return "Added ✓";
    }
    if (exportState.kind === "done") {
      return exportState.outcome === "added" ? "Added" : "Already in Anki";
    }
    return "Add to Anki";
  })();

  const busy = exportState.kind === "checking" || exportState.kind === "busy";
  const enabled = exportState.kind === "idle" || exportState.kind === "failed";

  return (
    <View
      style={[
        styles.bar,
        {
          backgroundColor: colors.surface,
          borderTopColor: colors.outlineVariant,
          paddingBottom: insets.bottom + spacing.l,
        },
      ]}
    >
      <Text numberOfLines={1} style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
        Deck: {anki.ankiSettings.deckName}
      </Text>
      {anki.ankiStatus !== "notInstalled" && (
        <Button
          fullWidth
          disabled={!enabled}
          label={busy ? undefined : buttonLabel}
          leading={
            busy ? (
              <ActivityIndicator size="small" color={colors.onPrimary} />
            ) : exportState.kind === "idle" || exportState.kind === "failed" ? (
              <Icon name="add" size={18} color={colors.onPrimary} />
            ) : null
          }
          onPress={() => {
            if (anki.ankiStatus === "ready") {
              void anki.addToAnki(entry);
            } else {
              void GatenAnki.requestPermission();
            }
          }}
        />
      )}
      {anki.ankiStatus === "needsPermission" && (
        <Button variant="text" label="Send to AnkiDroid instead" onPress={() => void share()} />
      )}
      {anki.ankiStatus === "notInstalled" && (
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
          Install AnkiDroid to add cards. You can configure Gaten cards here first.
        </Text>
      )}
      {exportState.kind === "failed" && (
        <Text style={[typeScale.bodySmall, {color: colors.error}]}>{exportState.message}</Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  appBar: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: spacing.xs,
  },
  appBarTitle: {flex: 1},
  body: {flex: 1},
  scrollContent: {
    alignSelf: "center",
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.l,
    paddingBottom: spacing.l,
  },
  card: {padding: spacing.xl, borderRadius: shape.medium},
  line: {marginTop: spacing.m},
  strip: {marginTop: spacing.m},
  settingsRow: {marginTop: spacing.m},
  flowRow: {flexDirection: "row", flexWrap: "wrap", gap: spacing.s},
  glosses: {marginTop: spacing.s, gap: spacing.xs},
  glossRow: {flexDirection: "row"},
  glossNumber: {width: 28},
  posCaption: {marginTop: spacing.xl},
  posPill: {
    borderRadius: CAPSULE,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.xs,
  },
  posRow: {flexDirection: "row", flexWrap: "wrap", gap: spacing.s, marginTop: spacing.m},
  bar: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.l,
    gap: spacing.s,
    // A hairline is all that separates the pinned action from the page above it.
    borderTopWidth: StyleSheet.hairlineWidth,
  },
});
