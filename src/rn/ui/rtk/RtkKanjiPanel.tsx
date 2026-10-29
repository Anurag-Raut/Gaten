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
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {CONTENT_COLUMN, contentInsetFor, shape, spacing, typeScale} from "../tokens";
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
      {current == null && <RtkSkeleton />}
      {current?.failed === true && (
        <Notice message="Could not read the Remembering the Kanji data on this device." />
      )}
      {current?.result?.status === "notAvailable" && (
        <Notice message="Not available in Remembering the Kanji." />
      )}
      {current?.result?.status === "available" && (
        // Keyed by character so a kanji opened over another starts with its stories collapsed.
        <EntryContent key={current.result.entry.character} entry={current.result.entry} />
      )}
    </View>
  );

  return (
    <View style={styles.root}>
      <View
        style={[
          styles.headerRow,
          !showBack && !embedded && {...CONTENT_COLUMN, paddingHorizontal: contentInsetFor(width)},
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

function EntryContent({entry}: {entry: GatenRtk.RtkEntry}) {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  const [first, ...others] = entry.mnemonics;
  const primitive = entry.primitive.replace(/^\*\s+/, "");

  return (
    <View style={CONTENT_COLUMN}>
      {/* The source is part of the heading's own block: it says where this page came from. */}
      <Text
        style={[
          typeScale.labelLarge,
          styles.sourceLabel,
          {color: colors.onSurfaceVariant},
        ]}
      >
        Remembering the Kanji
      </Text>
      <View
        style={[
          styles.sectionBlock,
          styles.sectionRule,
          {borderBottomColor: colors.outlineVariant},
        ]}
      >
        <Text style={[typeScale.labelLarge, styles.caption, {color: colors.onSurfaceVariant}]}>
          Keyword
        </Text>
        <Text style={[styles.keyword, {color: colors.onSurface}]}>{entry.keyword}</Text>
      </View>
      {entry.story !== "" && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          <Text style={[typeScale.labelLarge, styles.caption, {color: colors.onSurfaceVariant}]}>
            Heisig’s story
          </Text>
          <EmphasizedText
            text={entry.story}
            style={[typeScale.bodyMedium, {color: colors.onSurface}]}
          />
        </View>
      )}
      {primitive !== "" && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          <Text style={[typeScale.labelLarge, styles.caption, {color: colors.onSurfaceVariant}]}>
            Primitive
          </Text>
          <EmphasizedText
            text={primitive}
            style={[typeScale.bodyMedium, {color: colors.onSurface}]}
          />
        </View>
      )}
      {first != null && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          <Text style={[typeScale.labelLarge, styles.caption, {color: colors.onSurfaceVariant}]}>
            Community mnemonics
          </Text>
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
        </View>
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
        style={[typeScale.bodyMedium, {color: colors.onSurface}]}
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
    <View style={[styles.notice, {backgroundColor: colors.surface}]}>
      <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{message}</Text>
    </View>
  );
}

/** The page while its answer is read: the shape it will take, so it never blanks out. */
function RtkSkeleton() {
  const {colors} = useTheme();
  return (
    <View accessibilityLabel="Reading the Remembering the Kanji entry…">
      <View style={styles.sectionBlock}>
        <View
          style={[styles.skeletonBlock, styles.skeletonLabel, {backgroundColor: colors.surfaceVariant}]}
        />
        <View
          style={[styles.skeletonBlock, styles.skeletonLine, {backgroundColor: colors.surfaceVariant}]}
        />
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <View
          style={[styles.skeletonBlock, styles.skeletonLabel, {backgroundColor: colors.surfaceVariant}]}
        />
        {[0, 1, 2].map((line) => (
          <View
            key={line}
            style={[
              styles.skeletonBlock,
              line === 2 ? styles.skeletonTail : styles.skeletonLine,
              {backgroundColor: colors.surfaceVariant},
              line > 0 ? {marginTop: spacing.xs} : null,
            ]}
          />
        ))}
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <View
          style={[styles.skeletonBlock, styles.skeletonLabel, {backgroundColor: colors.surfaceVariant}]}
        />
        <View
          style={[styles.skeletonBlock, styles.skeletonLine, {backgroundColor: colors.surfaceVariant}]}
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
  heroGlyph: {fontSize: 36, fontWeight: "700"},
  keyword: {fontSize: 29, fontWeight: "700"},
  sourceLabel: {marginTop: spacing.xs},
  sectionBlock: {paddingVertical: spacing.s, gap: spacing.xs},
  sectionRule: {borderBottomWidth: 1},
  sectionGap: {marginTop: spacing.l},
  caption: {marginBottom: spacing.xs},
  story: {gap: spacing.xs},
  disclosureRow: {
    minHeight: 48,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.s,
    marginTop: spacing.s,
  },
  otherStories: {gap: spacing.l, paddingTop: spacing.s},
  notice: {
    ...CONTENT_COLUMN,
    borderRadius: shape.medium,
    padding: spacing.l,
    marginTop: spacing.s,
    marginBottom: spacing.l,
  },
  skeletonBlock: {borderRadius: shape.small},
  skeletonLabel: {width: 72, height: 14},
  skeletonLine: {height: 14},
  skeletonTail: {width: 160, height: 14},
});
