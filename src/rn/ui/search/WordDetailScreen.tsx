/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * A focused dictionary entry with expandable spellings and a pinned Anki action. The entry is
 * resolved by id from the shared search store so a restored route never carries stale result data.
 */
import React, {useCallback, useMemo, useRef, useState} from "react";
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useFocusEffect} from "@react-navigation/native";
import {Button, Icon, IconButton, distinctIdeographs} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import * as GatenDict from "../../native/GatenDict";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import {hitFor, useSearchState} from "./searchStore";
import {SearchNotice} from "./SearchScreen";
import {useAnkiExport, type AnkiExport, type ExportState} from "./useAnkiExport";

export interface SearchWordScreenProps {
  entryId: number;
  onBack: () => void;
  onOpenKanji: (character: string) => void;
}

export function SearchWordScreen({entryId, onBack, onOpenKanji}: SearchWordScreenProps) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  const state = useSearchState();
  const hit = useMemo(() => hitFor(entryId), [state.hits, entryId]);
  const anki = useAnkiExport();
  const exportState = anki.exportFor(entryId);
  const recordedEntryId = useRef<number | null>(null);
  // The WaniKani kanji section is only there when WaniKani can answer for it, so the token's
  // presence is read here rather than assumed. It is stored or forgotten on the WaniKani settings
  // page, which this screen returns from, so reading on arrival covers every way it changes.
  const [wanikaniConfigured, setWanikaniConfigured] = useState(false);

  // Save a lookup and refresh Anki's duplicate state whenever the reader returns to this entry.
  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      void GatenWaniKani.isConfigured().then((configured) => {
        if (!cancelled) setWanikaniConfigured(configured);
      });
      const entry = hitFor(entryId);
      if (entry) {
        if (recordedEntryId.current !== entry.id) {
          recordedEntryId.current = entry.id;
          void GatenDict.rememberLookup(entry).catch((error: unknown) => {
            recordedEntryId.current = null;
            console.warn("Could not save the recent word lookup", error);
          });
        }
        void anki.prepareExport(entry);
      }
      return () => {
        cancelled = true;
      };
    }, [entryId]),
  );

  if (!hit) {
    return (
      <View style={[styles.root, {backgroundColor: colors.background}]}>
        <WordAppBar onBack={onBack} />
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
      <WordAppBar onBack={onBack} />
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.body}
        contentContainerStyle={[
          styles.scrollContent,
          {paddingHorizontal: width < 360 ? spacing.l : spacing.contentInset},
        ]}
      >
        <HeadwordSection hit={hit} />
        <MeaningSection glosses={hit.glosses} partsOfSpeech={hit.pos} />
        <OtherSpellingsRow primary={hit.primary} spellings={hit.kanji} />
        {wanikaniConfigured && (
          <WordKanjiSection
            characters={distinctIdeographs(hit.primary)}
            onOpenKanji={onOpenKanji}
          />
        )}
      </ScrollView>
      <AddToAnkiBar entry={hit} anki={anki} exportState={exportState} />
    </View>
  );
}

function WordAppBar({onBack}: {onBack: () => void}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      style={[
        styles.appBar,
        {
          backgroundColor: colors.background,
          minHeight: 64 + insets.top,
          paddingTop: insets.top,
          borderBottomColor: colors.outlineVariant,
        },
      ]}
    >
      <IconButton name="arrow_back" accessibilityLabel="Back" onPress={onBack} />
      <Text
        accessibilityRole="header"
        style={[styles.appBarTitle, {color: colors.onSurface}]}
      >
        Word details
      </Text>
    </View>
  );
}

export function HeadwordSection({hit}: {hit: SearchResult}) {
  const {colors} = useTheme();
  const readings = hit.kana.filter((reading) => reading !== hit.primary);
  const posSummary = hit.pos[0]
    ? compactPartOfSpeech(hit.pos[0]).split(" / ")[0].replace(/s$/i, "")
    : null;
  const metadata = [hit.common ? "Common" : null, posSummary]
    .filter(Boolean)
    .join(" · ");

  return (
    <View style={[styles.headwordSection, {borderBottomColor: colors.outlineVariant}]}>
      <Text style={[styles.wordTitle, {color: colors.onSurface}]}>{hit.primary}</Text>
      {readings.length > 0 && (
        <Text style={[styles.reading, {color: colors.onSurfaceVariant}]}>
          {readings.join("・")}
        </Text>
      )}
      {metadata.length > 0 && (
        <Text style={[styles.metadata, {color: colors.onSurfaceVariant}]}>
          {metadata}
        </Text>
      )}
    </View>
  );
}

export function MeaningSection({glosses, partsOfSpeech}: {
  glosses: string[];
  partsOfSpeech: string[];
}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.meaningSection, {borderBottomColor: colors.outlineVariant}]}>
      <Text
        accessibilityRole="header"
        style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}
      >
        Meaning
      </Text>
      {glosses.length === 0 ? (
        <Text style={[typeScale.bodyMedium, styles.meaningFallback, {color: colors.onSurfaceVariant}]}>
          The dictionary has no gloss for this entry.
        </Text>
      ) : (
        <View style={styles.glosses}>
          {glosses.map((gloss, index) => (
            <Text key={`${index}-${gloss}`} style={[styles.meaning, {color: colors.onSurface}]}>
              {gloss.length > 0 ? `${gloss[0].toUpperCase()}${gloss.slice(1)}` : gloss}
            </Text>
          ))}
        </View>
      )}
      {partsOfSpeech.length > 0 && (
        <View style={styles.partsOfSpeech}>
          <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>Part of speech</Text>
          <Text
            style={[
              typeScale.bodyMedium,
              styles.partsOfSpeechValue,
              {color: colors.onSurfaceVariant},
            ]}
          >
            {partsOfSpeech.join(" / ")}
          </Text>
        </View>
      )}
    </View>
  );
}

function compactPartOfSpeech(label: string): string {
  const shortLabel = label.split("(")[0].trim();
  return /^expressions?$/i.test(shortLabel) ? "Expression / phrase" : shortLabel;
}

export function OtherSpellingsRow({primary, spellings}: {primary: string; spellings: string[]}) {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  const alternatives = [...new Set(spellings.filter((spelling) => spelling !== primary))];
  if (alternatives.length === 0) return null;

  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={`Other spellings, ${alternatives.length} variants`}
      accessibilityHint={expanded ? "Collapse other spellings" : "Expand other spellings"}
      accessibilityState={{expanded}}
      onPress={() => setExpanded((value) => !value)}
      style={[styles.spellingsRow, {borderBottomColor: colors.outlineVariant}]}
    >
      <View style={styles.detailRowContent}>
        <Text style={[typeScale.bodyMedium, styles.detailLabel, {color: colors.onSurfaceVariant}]}>
          Other spellings
        </Text>
        <View style={styles.spellingsSummary}>
          <Text style={[typeScale.titleSmall, {color: colors.onSurface}]}>
            {alternatives.length} {alternatives.length === 1 ? "variant" : "variants"}
          </Text>
          <Icon
            name={expanded ? "keyboard_arrow_down" : "keyboard_arrow_right"}
            size={24}
            color={colors.onSurfaceVariant}
          />
        </View>
      </View>
      {/* One row per spelling: a list the eye can count, not variants strung together with a dot. */}
      {expanded && (
        <View style={styles.alternatives}>
          {alternatives.map((spelling, index) => (
            <View
              key={spelling}
              style={[
                styles.alternativeRow,
                index > 0
                  ? {borderTopWidth: 1, borderTopColor: colors.outlineVariant}
                  : null,
              ]}
            >
              <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{spelling}</Text>
            </View>
          ))}
        </View>
      )}
    </PressableScale>
  );
}

export function WordKanjiSection({
  characters,
  onOpenKanji,
}: {
  characters: string[];
  onOpenKanji: (character: string) => void;
}) {
  const {colors} = useTheme();
  if (characters.length === 0) return null;

  return (
    <View style={styles.kanjiSection}>
      <View style={styles.kanjiHeading}>
        <Text accessibilityRole="header" style={[typeScale.titleSmall, {color: colors.onSurface}]}>
          WaniKani kanji
        </Text>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
          {characters.length} {characters.length === 1 ? "character" : "characters"}
        </Text>
      </View>
      <View style={styles.kanjiTiles}>
        {characters.map((character) => (
          <PressableScale
            key={character}
            accessibilityRole="button"
            accessibilityLabel={`WaniKani details for ${character}`}
            accessibilityHint="Opens the kanji details"
            onPress={() => onOpenKanji(character)}
            style={[
              styles.kanjiTile,
              {
                backgroundColor: colors.surfaceVariant,
                borderColor: colors.outlineVariant,
              },
            ]}
          >
            <Text style={[styles.kanjiCharacter, {color: colors.onSurface}]}>{character}</Text>
          </PressableScale>
        ))}
      </View>
    </View>
  );
}

export function AddToAnkiBar({
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
  const {width} = useWindowDimensions();

  // The Anki action belongs to a connected AnkiDroid only: without database access there is
  // nothing to add to and no fallback to offer, so the whole bar stays out of the way rather
  // than leaving an orphaned deck row or empty footer space behind.
  if (anki.ankiStatus !== "ready") return null;

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
          backgroundColor: colors.background,
          borderTopColor: colors.outlineVariant,
          paddingBottom: insets.bottom + spacing.s,
        },
      ]}
    >
      <View
        style={[
          styles.footerContent,
          {paddingHorizontal: width < 360 ? spacing.l : spacing.contentInset},
        ]}
      >
        <View style={styles.deckRow}>
          <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>Anki deck</Text>
          <Text
            accessibilityLabel={`Anki deck: ${anki.ankiSettings.deckName}`}
            style={[typeScale.titleSmall, styles.deckName, {color: colors.onSurface}]}
          >
            {anki.ankiSettings.deckName}
          </Text>
        </View>
        <Button
          fullWidth
          disabled={!enabled}
          label={busy ? undefined : buttonLabel}
          leading={
            busy ? (
              <ActivityIndicator size="small" color={colors.onActionPrimary} />
            ) : exportState.kind === "idle" || exportState.kind === "failed" ? (
              <Icon name="add" size={18} color={colors.onActionPrimary} />
            ) : null
          }
          onPress={() => void anki.addToAnki(entry)}
        />
        {exportState.kind === "failed" && (
          <Text style={[typeScale.bodySmall, {color: colors.error}]}>{exportState.message}</Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  appBar: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: spacing.xs,
    borderBottomWidth: 1,
  },
  appBarTitle: {
    flex: 1,
    fontSize: 17,
    fontWeight: "700",
    fontFamily: "Inter",
  },
  body: {flex: 1},
  scrollContent: {
    alignSelf: "center",
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    paddingTop: spacing.xl,
    paddingBottom: spacing.xl,
  },
  headwordSection: {paddingBottom: spacing.xl, borderBottomWidth: 1},
  wordTitle: {fontSize: 36, fontWeight: "700"},
  reading: {fontSize: 18, marginTop: spacing.s},
  metadata: {fontSize: 14, marginTop: spacing.m},
  meaningSection: {paddingVertical: spacing.xl, borderBottomWidth: 1},
  glosses: {marginTop: spacing.m, gap: spacing.s},
  partsOfSpeech: {marginTop: spacing.l},
  partsOfSpeechValue: {marginTop: spacing.xs},
  meaning: {fontSize: 29, fontWeight: "700"},
  meaningFallback: {marginTop: spacing.m},
  detailLabel: {flex: 1},
  spellingsRow: {borderBottomWidth: 1},
  detailRowContent: {
    minHeight: 58,
    paddingVertical: spacing.l,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
  },
  spellingsSummary: {flexDirection: "row", alignItems: "center", gap: spacing.xs},
  alternatives: {paddingBottom: spacing.l},
  alternativeRow: {minHeight: 40, justifyContent: "center", paddingVertical: spacing.s},
  kanjiSection: {paddingTop: spacing.xl},
  kanjiHeading: {flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: spacing.s},
  kanjiTiles: {flexDirection: "row", flexWrap: "wrap", gap: spacing.m, marginTop: spacing.m},
  kanjiTile: {
    width: 58,
    height: 58,
    borderRadius: shape.empty,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  kanjiCharacter: {fontSize: 34, fontWeight: "600"},
  bar: {borderTopWidth: 1, paddingTop: spacing.m},
  footerContent: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center", gap: spacing.s},
  deckRow: {minHeight: 32, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: spacing.m},
  deckName: {flexShrink: 1, textAlign: "right"},
});
