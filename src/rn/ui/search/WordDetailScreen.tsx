/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * A focused dictionary entry with expandable spellings, ending in the Anki action as the content's
 * own last item — reached by scrolling the entry it belongs to, never a bar pinned to the screen's
 * bottom (the lookup sheet reads the same way). The entry is resolved by id from the shared search
 * store so a restored route never carries stale result data.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
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
import {Button, Icon, distinctIdeographs} from "../components";
import {Collapsible, DisclosureChevron} from "../Collapsible";
import {PressableScale, heldTone} from "../PressableScale";
import {ScreenHeader} from "../ScreenHeader";
import {kanjiReferenceLabel, showKanjiStrip, useKanjiReferenceSource} from "../kanjiReference";
import {useTheme, type ThemeColors} from "../theme";
import {CONTENT_COLUMN, contentInsetFor, shape, spacing, typeScale} from "../tokens";
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
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const inset = contentInsetFor(width);
  const state = useSearchState();
  const hit = useMemo(() => hitFor(entryId), [state.hits, entryId]);
  const anki = useAnkiExport();
  const exportState = anki.exportFor(entryId);
  // AddToAnkiBar draws nothing unless AnkiDroid is ready, and the in-flow case must not double its gap.
  const ankiBarPresent = anki.ankiStatus === "ready";
  const recordedEntryId = useRef<number | null>(null);
  // The WaniKani kanji section is only there when WaniKani can answer for it, so the token's
  // presence is read here rather than assumed. It is stored or forgotten on the WaniKani settings
  // page, which this screen returns from, so reading on arrival covers every way it changes.
  const [wanikaniConfigured, setWanikaniConfigured] = useState(false);
  // Which reference a tap opens. RTK needs no account, so its tiles show even when no token is
  // stored; WaniKani's still need one, exactly as before.
  const kanjiReference = useKanjiReferenceSource();

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
          // The in-flow bar supplies the last gap when it draws; when AnkiDroid is not connected it
          // draws nothing, so the scroller pads for the screen edge itself.
          {paddingHorizontal: inset, paddingBottom: ankiBarPresent ? 0 : insets.bottom + spacing.xl},
        ]}
      >
        <HeadwordSection hit={hit} />
        <MeaningSection key={hit.id} glosses={hit.glosses} partsOfSpeech={hit.pos} />
        <OtherSpellingsRow primary={hit.primary} spellings={hit.kanji} />
        {showKanjiStrip(kanjiReference, wanikaniConfigured) && (
          <WordKanjiSection
            label={kanjiReferenceLabel(kanjiReference)}
            characters={distinctIdeographs(hit.primary)}
            onOpenKanji={onOpenKanji}
          />
        )}
        <ExampleSentencesSection term={hit.primary} />
        {/* The Anki action ends the entry's content, so the reader scrolls to it with the entry it
            belongs to, drawn as the entry's own last section: its rule, row and action share the
            section grid the sections above it use. */}
        <AddToAnkiBar entry={hit} anki={anki} exportState={exportState} />
      </ScrollView>
    </View>
  );
}

function WordAppBar({onBack}: {onBack: () => void}) {
  const insets = useSafeAreaInsets();
  return (
    <View style={{paddingTop: insets.top}}>
      <ScreenHeader title="Word details" onBack={onBack} bordered />
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
  label = "WaniKani kanji",
  onOpenKanji,
}: {
  characters: string[];
  /** The selected reference's name: WaniKani's kanji, or Remembering the Kanji. */
  label?: string;
  onOpenKanji: (character: string) => void;
}) {
  const {colors} = useTheme();
  if (characters.length === 0) return null;

  return (
    <View style={[styles.kanjiSection, {borderBottomColor: sectionRule(colors)}]}>
      <View style={styles.kanjiHeading}>
        <Text accessibilityRole="header" style={[typeScale.labelLarge, {color: colors.onSurface}]}>
          {label}
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
            accessibilityLabel={`${label} details for ${character}`}
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

/**
 * The bundled Tanaka Corpus's sentences for `term`, shortest first — read from the on-device
 * corpus, never the network. The word detail page and the lookup sheet's entry both render this,
 * so the overlay offers the same detail as the full page. Three states: the lookup is unanswered
 * (a muted line, not an empty section), no sentence carries the word, or the sentences themselves.
 */
export function ExampleSentencesSection({term}: {term: string}) {
  const {colors} = useTheme();
  const [examples, setExamples] = useState<GatenDict.SentenceExample[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setExamples(null);
    setFailed(false);
    if (term.length === 0) {
      setExamples([]);
      return;
    }
    void GatenDict.lookupExamples(term)
      .then((found) => {
        if (!cancelled) setExamples(found);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setFailed(true);
          console.warn("Could not read example sentences", error);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [term]);

  return (
    <View style={[styles.examplesSection, {borderBottomColor: sectionRule(colors)}]}>
      <Text accessibilityRole="header" style={[typeScale.labelLarge, {color: colors.onSurface}]}>
        Example sentences
      </Text>
      {failed ? (
        // A read failure is not an empty corpus: it says so, and what to do, rather than claiming
        // the word has no examples.
        <Text style={[typeScale.bodyMedium, styles.examplesState, {color: colors.error}]}>
          Could not read example sentences. Reopen the word to try again.
        </Text>
      ) : examples === null ? (
        <Text style={[typeScale.bodyMedium, styles.examplesState, {color: colors.onSurfaceVariant}]}>
          Finding examples…
        </Text>
      ) : examples.length === 0 ? (
        <Text style={[typeScale.bodyMedium, styles.examplesState, {color: colors.onSurfaceVariant}]}>
          No example sentences found for this word.
        </Text>
      ) : (
        <View style={styles.examplesList}>
          {examples.map((example, index) => (
            <View
              key={`${index}-${example.japanese}`}
              style={[
                styles.exampleRow,
                index > 0 && {borderTopWidth: 1, borderTopColor: colors.outlineVariant},
              ]}
            >
              <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>{example.japanese}</Text>
              <Text
                style={[typeScale.bodySmall, styles.exampleEnglish, {color: colors.onSurfaceVariant}]}
              >
                {example.english}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

/** Renders the export deck picker and filled Anki action, preserving export and deck-save states. */
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
  // The deck list is read when the row is opened, not on every render: AnkiDroid's provider is the
  // only source, and the reader may never touch the control.
  const [deckOpen, setDeckOpen] = useState(false);
  const [decks, setDecks] = useState<AnkiDeckResult[] | null>(null);
  const [deckLoadFailed, setDeckLoadFailed] = useState(false);
  const [deckSaving, setDeckSaving] = useState(false);
  const [deckError, setDeckError] = useState<string | null>(null);

  // The Anki action belongs to a connected AnkiDroid only: without database access there is
  // nothing to add to and no fallback to offer, so the whole section stays out of the way rather
  // than leaving an orphaned deck row or empty space behind.
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
    // The entry's last section. It draws no rule of its own: the section above always ends on one
    // (Meanings is unconditional), and the deck row keeps the page's detail-row height and padding,
    // so it sits on the same rhythm as the rows above it. The 24 dp under the action, above the
    // bottom inset, keep the button off the screen's edge.
    <View style={{paddingBottom: insets.bottom + spacing.xl}}>
      {/* The callers already inset this content: Word details pads its scroller by the page inset,
          and the lookup sheet's entry content carries the same inset inside its capped panel. The
          section adds no horizontal padding of its own, so its rule, rows and action share the one
          page grid. */}
      <View style={styles.ankiContent}>
        {/* The row and its list are one group: the list discloses the row, so the section's gap
            belongs between the group and the action, not on both sides of a collapsed list. */}
        <View>
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel={`Anki deck: ${anki.ankiSettings.deckName}`}
            accessibilityHint="Changes the deck new cards are added to"
            accessibilityState={{expanded: deckOpen, disabled: deckLocked}}
            disabled={deckLocked}
            onPress={toggleDeckList}
            style={styles.deckRow}
          >
            <Icon name="library_books" size={20} color={colors.onSurfaceVariant} />
            <Text style={[typeScale.bodyMedium, styles.deckLabel, {color: colors.onSurfaceVariant}]}>
              Deck
            </Text>
            <View style={styles.deckValue}>
              <Text style={[typeScale.labelLarge, styles.deckName, {color: colors.onSurface}]}>
                {anki.ankiSettings.deckName}
              </Text>
              {/* 16 dp, the label's own size: at 20 the glyph read taller than the deck name it
                  discloses. The row keeps its 48 dp target — only the glyph is smaller. */}
              <DisclosureChevron expanded={deckOpen} size={16} color={colors.onSurfaceVariant} />
            </View>
          </PressableScale>
          {/* Measured while clipped and animated open and closed, the way the entry's own
              disclosures are, so the section grows instead of jumping; collapsed it takes no touches
              and stays out of the accessibility tree. Bounded, so a collection with many decks
              cannot push the action out of the sheet. */}
          <Collapsible expanded={deckOpen}>
            <ScrollView
              style={styles.deckList}
              contentContainerStyle={styles.deckListContent}
              // This list can sit inside the entry's own scroller (the lookup sheet's section is the
              // content's last item in both orientations). Android needs nested scrolling enabled on
              // the inner list or it swallows the drag over the list and the entry never scrolls.
              // Inert where the list is not nested, which is Home.
              nestedScrollEnabled
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
                <Text
                  style={[typeScale.bodyMedium, styles.deckNote, {color: colors.onSurfaceVariant}]}
                >
                  Reading AnkiDroid decks…
                </Text>
              ) : decks.length === 0 ? (
                <Text
                  style={[typeScale.bodyMedium, styles.deckNote, {color: colors.onSurfaceVariant}]}
                >
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
                      style={[
                        typeScale.bodyMedium,
                        styles.deckOptionName,
                        {color: colors.onSurface},
                      ]}
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
          </Collapsible>
        </View>
        <Button
          fullWidth
          disabled={!enabled || deckSaving}
          label={busy ? undefined : buttonLabel}
          // The one control that travels: the Add action sinks and takes a held tone while it is
          // pressed, on top of the tick every button plays. Disabled, it does neither.
          pressDrop={{
            from: colors.actionPrimary,
            to: heldTone(colors.actionPrimary, colors.onActionPrimary),
          }}
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
  body: {flex: 1},
  scrollContent: {...CONTENT_COLUMN, paddingTop: spacing.xl, paddingBottom: spacing.xl},
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
  // Ends on the section rule like the sections above, so the entry's last boundary is drawn once by
  // whichever section is last.
  kanjiSection: {paddingVertical: spacing.xl, borderBottomWidth: 1},
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
  examplesSection: {paddingVertical: spacing.l, borderBottomWidth: 1},
  examplesState: {marginTop: spacing.m},
  examplesList: {marginTop: spacing.s},
  exampleRow: {paddingVertical: spacing.l},
  exampleEnglish: {marginTop: spacing.xs},
  // The section's own rhythm is the detail rows' (`detailRowContent`): the same 58 dp box with the
  // same vertical padding, so the deck row does not carry air the rows above it do not.
  ankiContent: {...CONTENT_COLUMN, gap: spacing.s},
  deckRow: {minHeight: 58, paddingVertical: spacing.l, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: spacing.m},
  deckLabel: {flex: 1},
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
