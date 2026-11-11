/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani detail panel the Compose WaniKaniDetailPanel drew: the shared page behind a tapped
 * kanji or radical, drawn by the search reference screen full-screen, the review screen inline
 * and (later) the overlay sheet.
 *
 * The lookup is re-requested whenever the target changes; a retry goes to WaniKani itself with
 * `refresh=true`, replacing whatever cached page is on screen. A radical's SVG is drawn by a
 * locked-down WebView: no JavaScript, no storage, no network, no navigation, not clickable.
 */
import React, {useEffect, useMemo, useState} from "react";
import {
  Linking,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {GatenCardWebView} from "../../native/GatenCardWebView";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {
  KanjiSubject,
  RadicalSubject,
  WaniKaniFailure,
  WaniKaniLookupResult,
  WaniKaniSubject,
} from "../../native/GatenWaniKani";
import {Button, Icon, IconButton} from "../components";
import {ReferenceBody, ReferenceSection, ReferenceTile, ReferenceValue} from "../reference/ReferenceText";
import {useTheme} from "../theme";
import {
  CONTENT_COLUMN,
  contentInsetFor,
  KANJI_LETTERING,
  shape,
  spacing,
  typeScale,
} from "../tokens";

/** What was tapped: a kanji by its character, or a radical by its WaniKani subject id. */
export type WaniKaniTarget =
  | {kind: "kanji"; character: string}
  | {kind: "radical"; id: number};

export interface WaniKaniPanelProps {
  target: WaniKaniTarget;
  /** The back arrow in the heading row; the review screen hides it (the opening chip closes). */
  showBack?: boolean;
  onBack?: () => void;
  /** Whether the body scrolls inside the panel or the caller scrolls around it. */
  scrollable?: boolean;
  /** Only the full-size (compact=false) rhythm is built so far. */
  compact?: boolean;
  /** The caller already applies the content grid: keep the body flush with the panel's heading. */
  embedded?: boolean;
  /**
   * The hosted tree already paints the surface — the lookup sheet's panel. The page then drops the
   * fills and rounding of its component chips and its loading blocks; a page on its own background
   * keeps them.
   */
  flat?: boolean;
  /** A component chip was tapped; the caller owns the Tick and the open-or-toggle decision. */
  onRadical?: (id: number) => void;
  /** The NeedsToken/AuthRejected notices' button: the shell opens the WaniKani settings page. */
  onSettings?: () => void;
  /**
   * A value that changes when the caller wants the target read again — the review page bumps it
   * when the reader comes back from connecting a token in Settings. The lookup is re-requested from
   * the device (token, then cache), never with `refresh`, so a page that was `needsToken` becomes
   * the page itself once the token is there. Callers with no navigation of their own leave it alone.
   */
  reloadKey?: number;
}

/** The failure strings the Compose panel showed, one per WaniKaniFailure. */
const FAILURE_COPY: Record<WaniKaniFailure, string> = {
  OFFLINE: "no connection",
  TIMEOUT: "the request timed out",
  RATE_LIMITED: "WaniKani is limiting requests just now",
  SERVER: "WaniKani reported a server error",
  TOO_LARGE: "the answer was too large to keep",
  MALFORMED: "WaniKani sent something Gaten could not read",
  STORAGE: "this device could not save the answer",
};

/** The bridge names its rejections `WK_<FAILURE>`; anything else is the generic fallback. */
function failureCopy(code: string | undefined | null): string {
  const name = (code ?? "").replace(/^WK_/, "");
  return name in FAILURE_COPY ? FAILURE_COPY[name as WaniKaniFailure] : "WaniKani could not be asked";
}

/** Only a page on wanikani.com or a subdomain of it opens in the browser. */
function isWaniKaniPage(url: string | undefined): boolean {
  if (!url || !url.startsWith("https://")) {
    return false;
  }
  const rest = url.slice("https://".length);
  const hostEnd = rest.search(/[/?#]/);
  const host = hostEnd === -1 ? rest : rest.slice(0, hostEnd);
  return host === "wanikani.com" || host.endsWith(".wanikani.com");
}

function openExternal(url: string) {
  // A silent no-op when there is no browser to take it.
  Linking.openURL(url).catch(() => {});
}

/** The locked-down HTML document a radical's SVG is loaded into. */
export function svgDocument(svg: string, dark: boolean): string {
  const invert = dark ? "<style>svg{filter:invert(1)}</style>" : "";
  return (
    '<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">' +
    "<style>html,body{margin:0;padding:0;height:100%;background:transparent}" +
    "body{display:flex;align-items:center;justify-content:center}svg{width:100%;height:100%}</style>" +
    invert +
    `</head><body>${svg}</body></html>`
  );
}

export function WaniKaniPanel({
  target,
  showBack = false,
  onBack,
  scrollable = true,
  compact = false,
  embedded = false,
  flat = false,
  onRadical,
  onSettings,
  reloadKey = 0,
}: WaniKaniPanelProps) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  const targetKey =
    target.kind === "kanji" ? `kanji:${target.character}` : `radical:${target.id}`;
  // The answered page and the target it answers, together: a new target has no answer yet, the way
  // the Compose panel's `remember(target)` dropped it as it recomposed — so the skeleton is drawn
  // for the page being looked up rather than the last one's page under the new heading.
  const [answer, setAnswer] = useState<{
    key: string;
    result: WaniKaniLookupResult | null;
    failure: string | null;
  }>({key: targetKey, result: null, failure: null});
  const lookup = answer.key === targetKey ? answer.result : null;
  const unreachable = answer.key === targetKey ? answer.failure : null;
  // The retry counter belongs to the target it was raised for as well: the Compose panel remembered
  // it per target, so a page opened after a refresh is read from the cache rather than fetched again.
  const [retry, setRetry] = useState({key: targetKey, count: 0});
  const retryCount = retry.key === targetKey ? retry.count : 0;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const result =
          target.kind === "kanji"
            ? await GatenWaniKani.lookupKanji(target.character, retryCount > 0)
            : await GatenWaniKani.lookupRadical(target.id, retryCount > 0);
        if (!cancelled) {
          setAnswer({key: targetKey, result, failure: null});
        }
      } catch (error) {
        if (!cancelled) {
          const code = (error as {code?: string} | null)?.code;
          setAnswer({key: targetKey, result: null, failure: failureCopy(code)});
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // `target` is rebuilt by the caller on every render; the key is the stable identity. A changed
    // `reloadKey` re-requests the page from the device — after a token was connected behind it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetKey, retryCount, reloadKey]);

  const subject: WaniKaniSubject | null =
    lookup?.status === "available" ? lookup.subject : null;
  const heading = subject
    ? subject.kind === "kanji"
      ? subject.character
      : subject.primaryMeaning
    : target.kind === "kanji"
      ? target.character
      : "Radical";
  // A kanji is this page's anchor, so it is drawn at the headword size before and after the lookup;
  // a radical's anchor is its drawing, which lives in the body.
  const headingIsCharacter =
    (subject !== null && subject.kind === "kanji") || target.kind === "kanji";

  const body = (
    <View
      style={[
        styles.content,
        {paddingHorizontal: embedded ? 0 : contentInsetFor(width)},
      ]}
    >
      {lookup === null && unreachable === null && <Skeleton compact={compact} flat={flat} />}
      {unreachable !== null && (
        <PanelNotice
          message={`Could not load this page from WaniKani: ${unreachable}.`}
          action={{
            label: "Retry",
            onPress: () => setRetry({key: targetKey, count: retryCount + 1}),
          }}
        />
      )}
      {lookup?.status === "available" && (
        <AvailableContent
          kanji={lookup.subject.kind === "kanji" ? lookup.subject : null}
          radical={lookup.subject.kind === "radical" ? lookup.subject : null}
          stale={lookup.stale}
          cacheFailure={lookup.cacheFailure}
          onRadical={onRadical}
          onRefresh={() => setRetry({key: targetKey, count: retryCount + 1})}
          embedded={embedded}
        />
      )}
      {lookup?.status === "noEntry" && (
        <PanelNotice
          message={
            target.kind === "kanji"
              ? `WaniKani has no entry for ${target.character}.`
              : "WaniKani has no entry for this radical."
          }
        />
      )}
      {lookup?.status === "restricted" && (
        <PanelNotice
          message={
            target.kind === "kanji"
              ? `${target.character} is above the levels your WaniKani account includes.`
              : "This radical is above the levels your WaniKani account includes."
          }
        />
      )}
      {lookup?.status === "needsToken" && (
        <PanelNotice
          message="Connect WaniKani to see kanji and radical pages here."
          action={onSettings ? {label: "Connect WaniKani", onPress: onSettings} : undefined}
        />
      )}
      {lookup?.status === "authRejected" && (
        <PanelNotice
          message="WaniKani rejected the saved token. Connect WaniKani again to keep seeing kanji and radical pages here."
          action={onSettings ? {label: "Connect WaniKani", onPress: onSettings} : undefined}
        />
      )}
    </View>
  );

  // A kanji's character is the page's anchor, so it sits in the heading row beside the page
  // actions. A radical's anchor is its drawing and its name is the body's primary content: a
  // heading here would say the name twice and leave a toolbar over the page. Only the overlay's
  // back action stays in that row for a radical.
  const isKanjiPage = target.kind === "kanji";

  return (
    <View style={styles.root}>
      {isKanjiPage && !embedded ? (
        <View style={[
          styles.headerRow,
          !showBack && {...CONTENT_COLUMN, paddingHorizontal: contentInsetFor(width)},
        ]}>
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
            style={[
              headingIsCharacter ? styles.heroGlyph : typeScale.titleLarge,
              {
                color: headingIsCharacter ? colors.onSurface : colors.onSurfaceVariant,
                flexShrink: 1,
              },
            ]}
          >
            {heading}
          </Text>
          <View style={styles.headerActions}>
            {subject !== null && (
              <>
                <IconButton
                  name="refresh"
                  accessibilityLabel="Refresh this page from WaniKani"
                  onPress={() => setRetry({key: targetKey, count: retryCount + 1})}
                  compact={compact}
                  size={compact ? 18 : 20}
                />
                {isWaniKaniPage(subject.documentUrl) && (
                  <IconButton
                    name="open_in_new"
                    accessibilityLabel="Open this page on WaniKani"
                    onPress={() => openExternal(subject.documentUrl ?? "")}
                    compact={compact}
                    size={compact ? 18 : 20}
                  />
                )}
              </>
            )}
          </View>
        </View>
      ) : !isKanjiPage ? (
        showBack && onBack && (
          <View style={styles.headerRow}>
            <IconButton
              name="arrow_back"
              accessibilityLabel="Close these details"
              onPress={onBack}
              compact={compact}
              size={compact ? 18 : 20}
            />
          </View>
        )
      ) : null}
      {scrollable ? (
        <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={styles.scrollContent}>{body}</ScrollView>
      ) : (
        body
      )}
    </View>
  );
}

function AvailableContent({
  kanji,
  radical,
  stale,
  cacheFailure,
  onRadical,
  onRefresh,
  embedded,
}: {
  kanji: KanjiSubject | null;
  radical: RadicalSubject | null;
  stale: boolean;
  cacheFailure: WaniKaniFailure | undefined;
  onRadical?: (id: number) => void;
  /** Asks WaniKani again; the foot actions are where the heading row's icons are not drawn. */
  onRefresh: () => void;
  /** The Anki answer's inline page: no heading row, so its page actions sit at the foot. */
  embedded: boolean;
}) {
  const {colors, dark} = useTheme();
  const subject: KanjiSubject | RadicalSubject | null = kanji ?? radical;
  const meanings = subject?.meanings ?? [];
  const primaryMeaning = subject?.primaryMeaning ?? "";
  const alternates = meanings.filter((meaning) => meaning !== primaryMeaning);
  const meaningMnemonic = subject?.meaningMnemonic ?? "";

  return (
    <View style={CONTENT_COLUMN}>
      {/* A radical's drawing is the page's anchor, so it leads the page on its own — no panel behind
          it and no rule under it; the meaning is the first section, the way every other page states
          one. */}
      {radical != null && (
        <View style={styles.radicalMark}>
          {radical.svg ? (
            <RadicalImage svg={radical.svg} dark={dark} background="transparent" size={56} />
          ) : radical.character != null ? (
            <Text style={[styles.heroGlyph, {color: colors.onSurface}]}>{radical.character}</Text>
          ) : null}
        </View>
      )}
      {subject != null && (
        <ReferenceSection title="Meaning">
          <ReferenceValue>{[primaryMeaning, ...alternates].join(", ")}</ReferenceValue>
          {meaningMnemonic.trim() !== "" && (
            <ReferenceBody selectable>{meaningMnemonic}</ReferenceBody>
          )}
        </ReferenceSection>
      )}
      {kanji != null && (kanji.onyomi.length > 0 || kanji.kunyomi.length > 0) && (
        <ReferenceSection title="Reading">
          {kanji.onyomi.length > 0 && (
            <View>
              {kanji.kunyomi.length > 0 && (
                <Text
                  style={[
                    typeScale.labelSmall,
                    styles.readingCaption,
                    {color: colors.onSurfaceVariant},
                  ]}
                >
                  On’yomi
                </Text>
              )}
              <ReferenceValue>{kanji.onyomi.join("・")}</ReferenceValue>
            </View>
          )}
          {kanji.kunyomi.length > 0 && (
            <View style={kanji.onyomi.length > 0 ? styles.readingGroup : undefined}>
              {kanji.onyomi.length > 0 && (
                <Text
                  style={[
                    typeScale.labelSmall,
                    styles.readingCaption,
                    {color: colors.onSurfaceVariant},
                  ]}
                >
                  Kun’yomi
                </Text>
              )}
              <ReferenceValue>{kanji.kunyomi.join("・")}</ReferenceValue>
            </View>
          )}
          {kanji.readingMnemonic.trim() !== "" && (
            <ReferenceBody selectable>{kanji.readingMnemonic}</ReferenceBody>
          )}
        </ReferenceSection>
      )}
      {kanji != null && kanji.components.length > 0 && onRadical != null && (
        <ReferenceSection title="Radical composition">
          <View style={styles.componentList}>
            {kanji.components.map((component) => {
              const label = component.character ?? component.name ?? "…";
              return (
                <ReferenceTile
                  key={component.id}
                  glyph={component.character}
                  name={component.name ?? (component.character == null ? "…" : null)}
                  onPress={() => onRadical(component.id)}
                  accessibilityLabel={`WaniKani details for the radical ${label}`}
                />
              );
            })}
          </View>
        </ReferenceSection>
      )}
      {(cacheFailure !== undefined || stale) && (
        <View style={styles.notes}>
          {cacheFailure !== undefined && (
            <Text style={[typeScale.labelMedium, {color: colors.error}]}>
              Some details could not be saved for offline use.
            </Text>
          )}
          {stale && (
            <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
              Showing the saved copy — WaniKani could not be reached just now.
            </Text>
          )}
        </View>
      )}
      {/* A radical page's actions always sit at the foot, out of the reading path; a kanji page
          keeps the same pair there only when its heading row — which holds them otherwise — is not
          drawn. */}
      {(radical != null || embedded) && (
        <View style={styles.pageActions}>
          <Button
            variant="text"
            label="Refresh"
            accessibilityLabel="Refresh this page from WaniKani"
            leading={<Icon name="refresh" size={18} color={colors.accent} />}
            onPress={onRefresh}
          />
          {isWaniKaniPage(subject?.documentUrl) && (
            <Button
              variant="text"
              label="View on WaniKani"
              accessibilityLabel="Open this page on WaniKani"
              leading={<Icon name="open_in_new" size={18} color={colors.accent} />}
              onPress={() => openExternal(subject?.documentUrl ?? "")}
            />
          )}
        </View>
      )}
    </View>
  );
}

/**
 * The radical's SVG in the locked-down native card WebView; the document reloads only when it
 * differs (the native side caches on the document itself). The lock-down — no JavaScript, no
 * storage, no network, no navigation — lives in GatenCardWebViewManager.
 */
function RadicalImage({
  svg,
  dark,
  background,
  size,
}: {
  svg: string;
  dark: boolean;
  background: string;
  size: number;
}) {
  const document = useMemo(() => svgDocument(svg, dark), [svg, dark]);
  return (
    <GatenCardWebView
      document={document}
      backgroundColor={background}
      mode="svg"
      style={{width: size, height: size, flexShrink: 0}}
    />
  );
}

/**
 * A state with no page to show: what happened, and — when something can be done about it — the one
 * action that can. A plain line of prose with the action under it, the way the rest of the app
 * states a state that needs something from the reader: no card and no badge, because none of it is
 * content from WaniKani and it must not read as a panel inside the page.
 */
function PanelNotice({
  message,
  action,
}: {
  message: string;
  action?: {label: string; onPress: () => void};
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.notice}>
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>{message}</Text>
      {action != null && (
        <View style={styles.noticeAction}>
          <Button variant="outlined" label={action.label} onPress={action.onPress} />
        </View>
      )}
    </View>
  );
}

/** A label-sized block, in the same rhythm a real caption keeps: more gap above it than below. */
function SkeletonLabel({compact, flat}: {compact: boolean; flat?: boolean}) {
  const {colors} = useTheme();
  return (
    <View style={{marginTop: compact ? spacing.s : spacing.m}}>
      <View
        style={[
          styles.skeletonBlock,
          flat && styles.skeletonBlockFlat,
          styles.skeletonLabel,
          {backgroundColor: colors.surfaceVariant},
        ]}
      />
      <View style={{height: spacing.xs}} />
    </View>
  );
}

/** A paragraph's worth of lines, the last one short like a real paragraph's end. */
function SkeletonParagraph({lines, flat}: {lines: number; flat?: boolean}) {
  const {colors} = useTheme();
  return (
    <View>
      {Array.from({length: lines}, (_, index) => (
        <View
          key={index}
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            index === lines - 1 ? styles.skeletonTail : styles.skeletonLine,
            {backgroundColor: colors.surfaceVariant},
            index > 0 ? styles.skeletonLineGap : null,
          ]}
        />
      ))}
    </View>
  );
}

/**
 * The page while its answer is on its way: the shape it will take, so switching between two kanji
 * holds the layout still instead of blanking out and filling in a beat later. Plain blocks in the
 * surface's own colour — no shimmer, no animation — and the state is still announced.
 */
function Skeleton({compact, flat}: {compact: boolean; flat?: boolean}) {
  const {colors} = useTheme();
  return (
    <View
      accessibilityLabel="Looking up WaniKani…"
      style={CONTENT_COLUMN}
    >
      <View style={styles.sectionBlock}>
        <SkeletonLabel compact={compact} flat={flat} />
        <View
          style={[
            styles.skeletonBlock,
            flat && styles.skeletonBlockFlat,
            styles.skeletonLine,
            {backgroundColor: colors.surfaceVariant},
          ]}
        />
        <SkeletonLabel compact={compact} flat={flat} />
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
        <SkeletonLabel compact={compact} flat={flat} />
        <View style={styles.skeletonChips}>
          {[0, 1, 2, 3].map((chip) => (
            <View
              key={chip}
              style={[
                styles.skeletonBlock,
                flat && styles.skeletonBlockFlat,
                styles.skeletonChip,
                {backgroundColor: colors.surfaceVariant},
              ]}
            />
          ))}
        </View>
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <SkeletonLabel compact={compact} flat={flat} />
        <SkeletonParagraph lines={3} flat={flat} />
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <SkeletonLabel compact={compact} flat={flat} />
        <SkeletonParagraph lines={2} flat={flat} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  headerRow: {flexDirection: "row", alignItems: "center"},
  headerActions: {flexDirection: "row", marginLeft: "auto"},
  content: {
    flex: 1,
    paddingHorizontal: spacing.contentInset,
    paddingTop: spacing.s,
    paddingBottom: spacing.xl,
  },
  scrollContent: CONTENT_COLUMN,
  // The page's anchor wears the shared kanji role and the one kanji lettering weight, the same the
  // review's hero and the chips under an answer wear.
  heroGlyph: {...typeScale.displaySmall, ...KANJI_LETTERING},
  // A radical's drawing leads the page on its own, left-aligned, with no rule or caption over it.
  radicalMark: {alignItems: "flex-start"},
  // The radical page's actions, at the foot and out of the reading path.
  pageActions: {flexDirection: "row", flexWrap: "wrap", gap: spacing.m, paddingTop: spacing.m},
  // A reading keeps its two kinds apart when both exist: a small caption, its value, then the next.
  readingCaption: {marginBottom: spacing.xs},
  readingGroup: {marginTop: spacing.m},
  // The radical breakdown is a vertical list of tiles, the way the lesson draws a composition.
  componentList: {gap: spacing.s},
  // The skeleton keeps the block rhythm the loaded sections replace.
  sectionBlock: {
    paddingVertical: spacing.s,
    gap: spacing.xs,
  },
  sectionGap: {marginTop: spacing.l},
  notes: {gap: spacing.s, marginTop: spacing.s},
  // A state with no page: one flat line of prose and, when there is one, the action under it.
  notice: {...CONTENT_COLUMN, marginTop: spacing.s, marginBottom: spacing.l},
  noticeAction: {marginTop: spacing.m, alignSelf: "flex-start"},
  // Flat, a loading block is a square bar: on a page that paints its own surface, rounding is what
  // would read as a card inside it.
  skeletonBlock: {borderRadius: shape.small},
  skeletonBlockFlat: {borderRadius: 0},
  skeletonLabel: {width: 72, height: 14},
  skeletonLine: {height: 14},
  skeletonLineGap: {marginTop: spacing.xs},
  skeletonTail: {width: 160, height: 14},
  skeletonChip: {width: 28, height: 28},
  skeletonChips: {flexDirection: "row", gap: spacing.s},
});
