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
import {Collapsible, DisclosureChevron} from "../Collapsible";
import {PressableScale} from "../PressableScale";
import {useTheme, type ThemeColors} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import * as GatenAnki from "../../native/GatenAnki";
import type {AnkiDeckResult} from "../../native/GatenAnki";
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

/** Section boundaries: the muted ink at a third, so they read apart from the row rules' hairline. */
function sectionRule(colors: ThemeColors): string {
  return `${colors.onSurfaceVariant}52`;
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
        <MeaningSection key={hit.id} glosses={hit.glosses} partsOfSpeech={hit.pos} />
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

  return (
    <View style={[styles.headwordSection, {borderBottomColor: sectionRule(colors)}]}>
      <Text style={[styles.wordTitle, {color: colors.onSurface}]}>{hit.primary}</Text>
      {readings.length > 0 && (
        <Text style={[styles.reading, {color: colors.onSurfaceVariant}]}>
          {readings.join("・")}
        </Text>
      )}
      {(hit.common || posSummary) && (
        <Text style={[styles.metadata, {color: colors.onSurfaceVariant}]}>
          {hit.common ? "Common" : null}
          {hit.common && posSummary ? " · " : null}
          {posSummary && <Text style={{color: colors.primary}}>{posSummary}</Text>}
        </Text>
      )}
    </View>
  );
}

// Preview five dictionary senses, preserving their grouping, and reveal the rest on request.
export function MeaningSection({glosses, partsOfSpeech}: {
  glosses: string[];
  partsOfSpeech: string[];
}) {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  const visibleCount = expanded ? glosses.length : Math.min(5, glosses.length);
  const meaningRows = glosses.map((gloss, index) => (
    <View
      key={`${index}-${gloss}`}
      style={[
        styles.meaningRow,
        index > 0 && {borderTopWidth: 1, borderTopColor: colors.outlineVariant},
      ]}
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>
        {gloss.length > 0 ? `${gloss[0].toUpperCase()}${gloss.slice(1)}` : gloss}
      </Text>
    </View>
  ));
  return (
    <View style={[styles.meaningSection, {borderBottomColor: sectionRule(colors)}]}>
      <View style={styles.meaningHeading}>
        <Text
          accessibilityRole="header"
          style={[typeScale.labelLarge, styles.meaningTitle, {color: colors.onSurface}]}
        >
          Meanings
        </Text>
        {glosses.length > 0 && (
          <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
            {visibleCount} of {glosses.length}
          </Text>
        )}
      </View>
      {glosses.length === 0 ? (
        <Text style={[typeScale.bodyMedium, styles.meaningFallback, {color: colors.onSurfaceVariant}]}>
          The dictionary has no gloss for this entry.
        </Text>
      ) : (
        <View style={styles.glosses}>
          {meaningRows.slice(0, 5)}
          {glosses.length > 5 && (
            <Collapsible expanded={expanded}>
              {meaningRows.slice(5)}
            </Collapsible>
          )}
        </View>
      )}
      {glosses.length > 5 && (
        <PressableScale
          accessibilityRole="button"
          accessibilityState={{expanded}}
          onPress={() => setExpanded((value) => !value)}
          style={styles.meaningAction}
        >
          <DisclosureChevron expanded={expanded} color={colors.primary} size={20} />
          <Text style={[typeScale.labelLarge, styles.meaningActionLabel, {color: colors.primary}]}>
            {expanded ? "Show fewer meanings" : `View all meanings (${glosses.length})`}
          </Text>
        </PressableScale>
      )}
      {partsOfSpeech.length > 0 && (
        <View style={styles.partsOfSpeech}>
          <Text accessibilityRole="header" style={[typeScale.labelLarge, {color: colors.onSurface}]}>
            Part of speech
          </Text>
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
      style={[styles.spellingsRow, {borderBottomColor: sectionRule(colors)}]}
    >
      <View style={styles.detailRowContent}>
        <Text style={[typeScale.labelLarge, styles.detailLabel, {color: colors.onSurface}]}>
          Other spellings
        </Text>
        <View style={styles.spellingsSummary}>
          <Text style={[typeScale.labelLarge, {color: colors.onSurface}]}>
            {alternatives.length} {alternatives.length === 1 ? "variant" : "variants"}
          </Text>
          <DisclosureChevron expanded={expanded} size={20} color={colors.onSurfaceVariant} />
        </View>
      </View>
      {/* One row per spelling: a list the eye can count, not variants strung together with a dot. */}
      <Collapsible expanded={expanded}>
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
      </Collapsible>
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
        <Text accessibilityRole="header" style={[typeScale.labelLarge, {color: colors.onSurface}]}>
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
  // The deck list is read when the row is opened, not on every render: AnkiDroid's provider is the
  // only source, and the reader may never touch the control.
  const [deckOpen, setDeckOpen] = useState(false);
  const [decks, setDecks] = useState<AnkiDeckResult[] | null>(null);
  const [deckLoadFailed, setDeckLoadFailed] = useState(false);
  const [deckSaving, setDeckSaving] = useState(false);
  const [deckError, setDeckError] = useState<string | null>(null);

  // The Anki action belongs to a connected AnkiDroid only: without database access there is
  // nothing to add to and no fallback to offer, so the whole bar stays out of the way rather
  // than leaving an orphaned deck row or empty footer space behind.
  if (anki.ankiStatus !== "ready") return null;

  const busy = exportState.kind === "checking" || exportState.kind === "busy";
  const enabled = exportState.kind === "idle" || exportState.kind === "failed";
  // An export in flight owns the saved configuration, so the deck cannot be moved under it.
  const deckLocked = deckSaving || busy;

  /**
   * Read AnkiDroid's decks. A failure is reported and leaves the list unread, so the next open
   * reads again instead of showing an empty collection.
   */
  const loadDecks = () => {
    setDeckLoadFailed(false);
    void GatenAnki.decks()
      .then((list) => setDecks(list))
      .catch(() => setDeckLoadFailed(true));
  };

  /** Open or close the deck list, reading AnkiDroid's decks the first time it is opened. */
  const toggleDeckList = () => {
    if (deckLocked) return;
    const next = !deckOpen;
    setDeckOpen(next);
    if (next && decks === null) {
      loadDecks();
    }
  };

  /**
   * Move the export destination: everything but the deck name is kept, and the button is re-judged
   * against the new deck — `prepareExport` re-reads the saved configuration — so a word already in
   * that deck says so before Add is offered again. Add is held while the write is in flight, so a
   * tap can never export into the deck the reader is leaving.
   */
  const chooseDeck = async (deckName: string) => {
    setDeckOpen(false);
    if (deckLocked || deckName === anki.ankiSettings.deckName) return;
    setDeckSaving(true);
    setDeckError(null);
    try {
      const refused = await GatenAnki.savePreferences({...anki.ankiSettings, deckName});
      if (refused) {
        setDeckError("AnkiDroid did not accept that deck.");
        return;
      }
      await anki.refreshAnkiStatus();
      await anki.prepareExport(entry);
    } catch {
      setDeckError("Could not save the deck.");
    } finally {
      setDeckSaving(false);
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

  return (
    <View
      style={[
        styles.bar,
        {
          backgroundColor: colors.background,
          borderTopColor: sectionRule(colors),
          // The design's 24 dp of air under the action, above the bottom inset: this bar is the last
          // thing on screen, and the button must not sit on the screen's edge.
          paddingBottom: insets.bottom + spacing.xl,
        },
      ]}
    >
      <View
        style={[
          styles.footerContent,
          {paddingHorizontal: width < 360 ? spacing.l : spacing.contentInset},
        ]}
      >
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel={`Anki deck: ${anki.ankiSettings.deckName}`}
          accessibilityHint="Changes the deck new cards are added to"
          accessibilityState={{expanded: deckOpen, disabled: deckLocked}}
          disabled={deckLocked}
          onPress={toggleDeckList}
          style={styles.deckRow}
        >
          <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>Anki deck</Text>
          <View style={styles.deckValue}>
            <Text style={[typeScale.labelLarge, styles.deckName, {color: colors.onSurface}]}>
              {anki.ankiSettings.deckName}
            </Text>
            <DisclosureChevron expanded={deckOpen} size={20} color={colors.onSurfaceVariant} />
          </View>
        </PressableScale>
        {deckOpen && (
          // Bounded, so a collection with many decks cannot push the action out of the sheet.
          <ScrollView
            style={styles.deckList}
            contentContainerStyle={styles.deckListContent}
            showsVerticalScrollIndicator={false}
            showsHorizontalScrollIndicator={false}
          >
            {deckLoadFailed ? (
              <PressableScale
                accessibilityRole="button"
                accessibilityLabel="Try reading AnkiDroid's decks again"
                onPress={loadDecks}
                style={styles.deckOption}
              >
                <Text style={[typeScale.bodyMedium, styles.deckOptionName, {color: colors.error}]}>
                  Could not read AnkiDroid's decks. Try again.
                </Text>
              </PressableScale>
            ) : decks === null ? (
              <Text style={[typeScale.bodyMedium, styles.deckNote, {color: colors.onSurfaceVariant}]}>
                Reading AnkiDroid decks…
              </Text>
            ) : decks.length === 0 ? (
              <Text style={[typeScale.bodyMedium, styles.deckNote, {color: colors.onSurfaceVariant}]}>
                AnkiDroid has no decks to choose from.
              </Text>
            ) : (
              decks.map((deck) => (
                <PressableScale
                  key={deck.id}
                  accessibilityRole="button"
                  accessibilityState={{
                    selected: deck.name === anki.ankiSettings.deckName,
                    disabled: deckLocked,
                  }}
                  disabled={deckLocked}
                  onPress={() => void chooseDeck(deck.name)}
                  style={styles.deckOption}
                >
                  <Text
                    numberOfLines={1}
                    style={[typeScale.bodyMedium, styles.deckOptionName, {color: colors.onSurface}]}
                  >
                    {deck.name}
                  </Text>
                  {deck.name === anki.ankiSettings.deckName && (
                    <Icon name="check" size={18} color={colors.primary} />
                  )}
                </PressableScale>
              ))
            )}
          </ScrollView>
        )}
        <Button
          fullWidth
          disabled={!enabled || deckSaving}
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
        {deckError !== null && (
          <Text style={[typeScale.bodyMedium, {color: colors.error}]}>{deckError}</Text>
        )}
        {exportState.kind === "failed" && (
          <Text style={[typeScale.bodyMedium, {color: colors.error}]}>{exportState.message}</Text>
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
  appBarTitle: {...typeScale.labelLarge, flex: 1},
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
  reading: {...typeScale.bodyMedium, marginTop: spacing.s},
  metadata: {...typeScale.bodyMedium, marginTop: spacing.m},
  meaningSection: {paddingVertical: spacing.l, borderBottomWidth: 1},
  meaningHeading: {flexDirection: "row", alignItems: "center", gap: spacing.m},
  meaningTitle: {flex: 1},
  glosses: {marginTop: spacing.s},
  meaningRow: {paddingVertical: spacing.l},
  meaningAction: {
    minHeight: 48,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.s,
    paddingVertical: spacing.m,
  },
  meaningActionLabel: {flexShrink: 1},
  partsOfSpeech: {marginTop: spacing.m},
  partsOfSpeechValue: {marginTop: spacing.xs},
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
  kanjiCharacter: {fontSize: 36, fontWeight: "700"},
  bar: {borderTopWidth: 1, paddingTop: spacing.m},
  footerContent: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center", gap: spacing.s},
  deckRow: {minHeight: 48, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: spacing.m},
  deckValue: {flexDirection: "row", alignItems: "center", gap: spacing.xs, flexShrink: 1},
  deckName: {flexShrink: 1, textAlign: "right"},
  deckList: {maxHeight: 216},
  deckListContent: {paddingVertical: spacing.xs},
  deckOption: {
    minHeight: 48,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.m,
    paddingHorizontal: spacing.s,
  },
  deckOptionName: {flexShrink: 1},
  deckNote: {paddingVertical: spacing.s, paddingHorizontal: spacing.s},
});
