/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Remembering the Kanji page behind a tapped kanji: the same page, heading and section rhythm
 * the WaniKani panel uses, drawn from the bundled dataset instead of the network.
 *
 * It shows only what RTK actually carries — keyword, Heisig story, Heisig primitive comment and the
 * community mnemonics. There is no reading section, no radical breakdown and no refresh: RTK teaches
 * meanings and writing, and inventing the rest from the story text would be guesswork. A kanji the
 * dataset does not carry says so instead of drawing an empty page.
 *
 * Community stories arrive already attributed (author, profile, date, score). The first is shown in
 * place and the rest expand under it without leaving the page.
 */
import React, {useEffect, useState} from "react";
import {Linking, ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import * as GatenRtk from "../../native/GatenRtk";
import type {RtkLookupResult, RtkMnemonic} from "../../native/GatenRtk";
import {Collapsible, DisclosureChevron} from "../Collapsible";
import {IconButton} from "../components";
import {ReferenceSection, ReferenceValue} from "../reference/ReferenceText";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {
  CONTENT_COLUMN,
  contentInsetFor,
  KANJI_LETTERING,
  shape,
  spacing,
  typeScale,
} from "../tokens";
import {EmphasizedText} from "./richText";

export interface RtkKanjiPanelProps {
  /** The kanji character the reader tapped; RTK is keyed by the character itself. */
  character: string;
  /** The back arrow in the heading row; the review screen hides it (the open tile closes). */
  showBack?: boolean;
  onBack?: () => void;
  /** Whether the body scrolls inside the panel or the caller scrolls around it. */
  scrollable?: boolean;
  compact?: boolean;
  /** The caller already applies the content grid: keep the body flush with the panel's heading. */
  embedded?: boolean;
  /**
   * The hosted tree already paints the surface — the lookup sheet's panel. The page then squares its
   * loading blocks; a page on its own background keeps their rounding.
   */
  flat?: boolean;
}

/** Only a page on kanji.koohii.com is opened; the native side already dropped anything else. */
function isKoohiiPage(url: string): boolean {
  if (!url.startsWith("https://") && !url.startsWith("http://")) return false;
  const rest = url.replace(/^https?:\/\//, "");
  const hostEnd = rest.search(/[/?#]/);
  const host = hostEnd === -1 ? rest : rest.slice(0, hostEnd);
  return host === "kanji.koohii.com" || host.endsWith(".kanji.koohii.com");
}

function openProfile(url: string) {
  if (!isKoohiiPage(url)) return;
  Linking.openURL(url).catch(() => {});
}

export function RtkKanjiPanel({
  character,
  showBack = false,
  onBack,
  scrollable = true,
  compact = false,
  embedded = false,
  flat = false,
}: RtkKanjiPanelProps) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  // The answer and the character it answers, together: a new character starts without content, so
  // the previous character's story can never sit under the new heading.
  const [answer, setAnswer] = useState<{
    key: string;
    result: RtkLookupResult | null;
    failed: boolean;
  }>({key: character, result: null, failed: false});
  const current = answer.key === character ? answer : null;

  useEffect(() => {
    let cancelled = false;
    void GatenRtk.lookupKanji(character)
      .then((result) => {
        if (!cancelled) setAnswer({key: character, result, failed: false});
      })
      .catch(() => {
        if (!cancelled) setAnswer({key: character, result: null, failed: true});
      });
    return () => {
      cancelled = true;
    };
  }, [character]);

  const body = (
    <View
      style={[styles.content, {paddingHorizontal: embedded ? 0 : contentInsetFor(width)}]}
    >
      {current == null && <RtkSkeleton flat={flat} />}
      {current?.failed === true && (
        <Notice message="Could not read the Remembering the Kanji data on this device." />
      )}
      {current?.result?.status === "notAvailable" && (
        <Notice message="Not available in Remembering the Kanji." />
      )}
      {current?.result?.status === "available" && (
        // Keyed by character so a kanji opened over another starts with its stories collapsed.
        <EntryContent
          key={current.result.entry.character}
          entry={current.result.entry}
          embedded={embedded}
        />
      )}
    </View>
  );

  return (
    <View style={styles.root}>
      {!embedded && (
        <View
          style={[
            styles.headerRow,
            !showBack && {...CONTENT_COLUMN, paddingHorizontal: contentInsetFor(width)},
          ]}
        >
          {showBack && onBack && (
            <IconButton
              name="arrow_back"
              accessibilityLabel="Close these details"
              onPress={onBack}
              compact={compact}
              size={compact ? 18 : 20}
            />
          )}
          <Text
            numberOfLines={1}
            style={[styles.heroGlyph, {color: colors.onSurface, flexShrink: 1}]}
          >
            {character}
          </Text>
        </View>
      )}
      {scrollable ? (
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.scrollContent}
        >
          {body}
        </ScrollView>
      ) : (
        body
      )}
    </View>
  );
}

function EntryContent({entry, embedded}: {entry: GatenRtk.RtkEntry; embedded: boolean}) {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  const [first, ...others] = entry.mnemonics;
  const primitive = entry.primitive.replace(/^\*\s+/, "");

  return (
    <View style={CONTENT_COLUMN}>
      {/* The source is part of the heading's own block: it says where this page came from. In the
          Anki answer the surrounding strip already says it, so the line is not said twice. */}
      {!embedded && (
        <Text
          style={[
            typeScale.labelLarge,
            styles.sourceLabel,
            {color: colors.onSurfaceVariant},
          ]}
        >
          Remembering the Kanji
        </Text>
      )}
      <ReferenceSection title="Keyword">
        <ReferenceValue>{entry.keyword}</ReferenceValue>
      </ReferenceSection>
      {entry.story !== "" && (
        <ReferenceSection title="Heisig’s story">
          <EmphasizedText
            text={entry.story}
            style={[
              typeScale.bodyMedium,
              {color: colors.onSurfaceVariant, marginTop: spacing.m},
            ]}
          />
        </ReferenceSection>
      )}
      {primitive !== "" && (
        <ReferenceSection title="Primitive">
          <EmphasizedText
            text={primitive}
            style={[
              typeScale.bodyMedium,
              {color: colors.onSurfaceVariant, marginTop: spacing.m},
            ]}
          />
        </ReferenceSection>
      )}
      {first != null && (
        <ReferenceSection title="Community mnemonics">
          <MnemonicStory mnemonic={first} />
          {others.length > 0 && (
            <>
              <PressableScale
                accessibilityRole="button"
                accessibilityLabel={
                  expanded ? "Hide other stories" : `View other stories (${others.length})`
                }
                accessibilityState={{expanded}}
                onPress={() => setExpanded((value) => !value)}
                style={styles.disclosureRow}
              >
                <Text
                  style={[
                    typeScale.labelLarge,
                    {color: colors.accent, flex: 1},
                  ]}
                >
                  {expanded ? "Hide other stories" : "View other stories"}
                </Text>
                <DisclosureChevron expanded={expanded} color={colors.accent} size={14} />
              </PressableScale>
              <Collapsible expanded={expanded}>
                <View style={styles.otherStories}>
                  {others.map((mnemonic) => (
                    <MnemonicStory key={mnemonic.index} mnemonic={mnemonic} />
                  ))}
                </View>
              </Collapsible>
            </>
          )}
        </ReferenceSection>
      )}
    </View>
  );
}

function MnemonicStory({mnemonic}: {mnemonic: RtkMnemonic}) {
  const {colors} = useTheme();
  // A story with no author is still attributed to the source it came from, never to nobody.
  const name = mnemonic.author !== "" ? mnemonic.author : "Kanji Koohii";
  const profile = mnemonic.profileUrl;
  return (
    <View style={styles.story}>
      <EmphasizedText
        text={mnemonic.body}
        style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}
      />
      <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
        {profile != null && isKoohiiPage(profile) ? (
          <Text
            accessibilityRole="link"
            onPress={() => openProfile(profile)}
            style={{color: colors.accent}}
          >
            {name}
          </Text>
        ) : (
          name
        )}
        {mnemonic.date !== "" ? ` · ${mnemonic.date}` : ""}
      </Text>
    </View>
  );
}

/** A state with no page to show: what happened, and only that. */
function Notice({message}: {message: string}) {
  const {colors} = useTheme();
  return (
    <View style={styles.notice}>
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>{message}</Text>
    </View>
  );
}

/** The page while its answer is read: the shape it will take, so it never blanks out. */
function RtkSkeleton({flat}: {flat?: boolean}) {
  const {colors} = useTheme();
  return (
    <View accessibilityLabel="Reading the Remembering the Kanji entry…">
      <View style={styles.sectionBlock}>
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLabel,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLine,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLabel,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
        {[0, 1, 2].map((line) => (
          <View
            key={line}
            style={[
              styles.skeletonBlock,
              flat && styles.skeletonBlockFlat,
              line === 2 ? styles.skeletonTail : styles.skeletonLine,
              {backgroundColor: colors.surfaceVariant},
              line > 0 ? {marginTop: spacing.xs} : null,
            ]}
          />
        ))}
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLabel,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLine,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  headerRow: {flexDirection: "row", alignItems: "center"},
  content: {
    flex: 1,
    paddingHorizontal: spacing.contentInset,
    paddingTop: spacing.s,
    paddingBottom: spacing.xl,
  },
  scrollContent: CONTENT_COLUMN,
  // The character wears the shared glyph role and the one kanji lettering weight; the keyword below
  // it is a meaning, not a character, and is drawn as the shared accent value.
  heroGlyph: {...typeScale.displaySmall, ...KANJI_LETTERING},
  sourceLabel: {marginTop: spacing.xs},
  story: {gap: spacing.xs},
  // The skeleton keeps the block rhythm the loaded sections replace.
  sectionBlock: {paddingVertical: spacing.s, gap: spacing.xs},
  sectionGap: {marginTop: spacing.l},
  disclosureRow: {
    minHeight: 48,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.s,
    marginTop: spacing.s,
  },
  otherStories: {gap: spacing.l, paddingTop: spacing.s},
  // A state with no page: one flat line of prose, the same the WaniKani panel's notice is, so a
  // card never sits inside the page it is not part of.
  notice: {...CONTENT_COLUMN, marginTop: spacing.s, marginBottom: spacing.l},
  // Flat, a loading block is a square bar: rounding is what would read as a card on the sheet's own
  // surface.
  skeletonBlock: {borderRadius: shape.small},
  skeletonBlockFlat: {borderRadius: 0},
  skeletonLabel: {width: 72, height: 14},
  skeletonLine: {height: 14},
  skeletonTail: {width: 160, height: 14},
});
