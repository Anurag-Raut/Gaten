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
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";

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
  /** A component chip was tapped; the caller owns the Tick and the open-or-toggle decision. */
  onRadical?: (id: number) => void;
  /** The NeedsToken/AuthRejected notices' button: the shell opens the WaniKani settings page. */
  onSettings?: () => void;
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
  onRadical,
  onSettings,
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
    // `target` is rebuilt by the caller on every render; the key is the stable identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetKey, retryCount]);

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
        {paddingHorizontal: embedded ? 0 : width < 360 ? spacing.l : spacing.contentInset},
      ]}
    >
      {lookup === null && unreachable === null && <Skeleton compact={compact} />}
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
          compact={compact}
          onRadical={onRadical}
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
          message="Connect a WaniKani personal access token in the Gaten app to see kanji and radical pages here."
          action={onSettings ? {label: "Open Gaten", onPress: onSettings} : undefined}
        />
      )}
      {lookup?.status === "authRejected" && (
        <PanelNotice
          message="WaniKani rejected the saved token. Open Gaten to connect again."
          action={onSettings ? {label: "Open Gaten", onPress: onSettings} : undefined}
        />
      )}
    </View>
  );

  return (
    <View style={styles.root}>
      <View style={styles.headerRow}>
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
            headingIsCharacter ? typeScale.displaySmall : typeScale.titleLarge,
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
  compact,
  onRadical,
}: {
  kanji: KanjiSubject | null;
  radical: RadicalSubject | null;
  stale: boolean;
  cacheFailure: WaniKaniFailure | undefined;
  compact: boolean;
  onRadical?: (id: number) => void;
}) {
  const {colors, dark} = useTheme();
  const meanings = radical ? radical.meanings : kanji?.meanings ?? [];
  const primaryMeaning = radical ? radical.primaryMeaning : kanji?.primaryMeaning;
  const alternates = meanings.filter((meaning) => meaning !== primaryMeaning);
  const meaningMnemonic = (radical ?? kanji)?.meaningMnemonic;

  return (
    <View style={{maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center", width: "100%"}}>
      {/* A radical's own drawing is what the page is about, so it gets the page's one focal
          surface; a kanji has its character in the heading and needs none. */}
      {radical && (
        <View style={[styles.hero, {backgroundColor: colors.surfaceVariant}]}>
          {radical.svg ? (
            <RadicalImage
              svg={radical.svg}
              dark={dark}
              background={colors.surfaceVariant}
              size={compact ? 56 : 72}
            />
          ) : (
            <Text style={[typeScale.displaySmall, {color: colors.onSurface}]}>
              {radical.character ?? radical.primaryMeaning}
            </Text>
          )}
        </View>
      )}
      <View style={[styles.sectionBlock, styles.sectionRule, {borderBottomColor: colors.outlineVariant}]}>
        <Text style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}>
          Meaning
        </Text>
        <Text style={[typeScale.bodyLarge, styles.gloss, {color: colors.onSurface}]}>
          {primaryMeaning}
        </Text>
        {alternates.length > 0 && (
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            {alternates.join(" · ")}
          </Text>
        )}
      </View>
      {kanji && (kanji.onyomi.length > 0 || kanji.kunyomi.length > 0) && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          {kanji.onyomi.length > 0 && (
            <>
              <Text
                style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}
              >
                On’yomi
              </Text>
              <Text style={[typeScale.titleSmall, {color: colors.onSurface}]}>
                {kanji.onyomi.join("・")}
              </Text>
            </>
          )}
          {kanji.kunyomi.length > 0 && (
            <View style={kanji.onyomi.length > 0 ? styles.readingRow : undefined}>
              <Text
                style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}
              >
                Kun’yomi
              </Text>
              <Text style={[typeScale.titleSmall, {color: colors.onSurface}]}>
                {kanji.kunyomi.join("・")}
              </Text>
            </View>
          )}
        </View>
      )}
      {kanji && kanji.components.length > 0 && onRadical && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          <Text style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}>
            Radicals
          </Text>
          <ScrollView
            showsVerticalScrollIndicator={false}
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.componentRow}
          >
            {kanji.components.map((component) => {
              const label = component.character ?? component.name ?? "…";
              return (
                <PressableScale
                  key={component.id}
                  accessibilityRole="button"
                  accessibilityLabel={`WaniKani details for the radical ${label}`}
                  onPress={() => onRadical(component.id)}
                  style={[styles.componentChip, {backgroundColor: colors.surfaceVariant}]}
                >
                  <Text style={[typeScale.labelLarge, {color: colors.onSurface}]}>{label}</Text>
                </PressableScale>
              );
            })}
          </ScrollView>
        </View>
      )}
      <View
        style={[
          styles.sectionBlock,
          styles.sectionGap,
          styles.sectionRule,
          {borderBottomColor: colors.outlineVariant},
        ]}
      >
        <Text style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}>
          WaniKani’s meaning mnemonic
        </Text>
        <Text selectable style={[typeScale.bodyMedium, {color: colors.onSurface}]}>
          {meaningMnemonic}
        </Text>
      </View>
      {kanji && (
        <View
          style={[
            styles.sectionBlock,
            styles.sectionGap,
            styles.sectionRule,
            {borderBottomColor: colors.outlineVariant},
          ]}
        >
          <Text style={[typeScale.labelMedium, styles.caption, {color: colors.onSurfaceVariant}]}>
            WaniKani’s reading mnemonic
          </Text>
          <Text selectable style={[typeScale.bodyMedium, {color: colors.onSurface}]}>
            {kanji.readingMnemonic}
          </Text>
        </View>
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
      style={{width: size, height: size}}
    />
  );
}

/**
 * A state with no page to show: what happened, and — when something can be done about it — the one
 * action that can. A card rather than a line of prose, because none of it is content from WaniKani,
 * so it should not read like a page.
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
    <View style={[styles.notice, {backgroundColor: colors.surface}]}>
      <View style={styles.noticeRow}>
        <View style={[styles.noticeBadge, {backgroundColor: colors.surfaceVariant}]}>
          <Icon name="info" size={22} color={colors.onSurfaceVariant} />
        </View>
        <Text style={[typeScale.bodyMedium, styles.noticeMessage, {color: colors.onSurface}]}>
          {message}
        </Text>
      </View>
      {action != null && (
        <View style={styles.noticeAction}>
          <Button fullWidth label={action.label} onPress={action.onPress} />
        </View>
      )}
    </View>
  );
}

/** A label-sized block, in the same rhythm a real caption keeps: more gap above it than below. */
function SkeletonLabel({compact}: {compact: boolean}) {
  const {colors} = useTheme();
  return (
    <View style={{marginTop: compact ? spacing.s : spacing.m}}>
      <View
        style={[
          styles.skeletonBlock,
          styles.skeletonLabel,
          {backgroundColor: colors.surfaceVariant},
        ]}
      />
      <View style={{height: spacing.xs}} />
    </View>
  );
}

/** A paragraph's worth of lines, the last one short like a real paragraph's end. */
function SkeletonParagraph({lines}: {lines: number}) {
  const {colors} = useTheme();
  return (
    <View>
      {Array.from({length: lines}, (_, index) => (
        <View
          key={index}
          style={[
            styles.skeletonBlock,
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
function Skeleton({compact}: {compact: boolean}) {
  const {colors} = useTheme();
  return (
    <View
      accessibilityLabel="Looking up WaniKani…"
      style={{maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center", width: "100%"}}
    >
      <View style={styles.sectionBlock}>
        <SkeletonLabel compact={compact} />
        <View style={[styles.skeletonBlock, styles.skeletonLine, {backgroundColor: colors.surfaceVariant}]} />
        <SkeletonLabel compact={compact} />
        <View style={[styles.skeletonBlock, styles.skeletonLine, {backgroundColor: colors.surfaceVariant}]} />
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <SkeletonLabel compact={compact} />
        <View style={styles.skeletonChips}>
          {[0, 1, 2, 3].map((chip) => (
            <View
              key={chip}
              style={[styles.skeletonBlock, styles.skeletonChip, {backgroundColor: colors.surfaceVariant}]}
            />
          ))}
        </View>
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <SkeletonLabel compact={compact} />
        <SkeletonParagraph lines={3} />
      </View>
      <View style={[styles.sectionBlock, styles.sectionGap]}>
        <SkeletonLabel compact={compact} />
        <SkeletonParagraph lines={2} />
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
  scrollContent: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  // Keep the meaning at the shared body size so longer glosses remain easy to scan.
  gloss: {fontSize: 14, lineHeight: 20},
  hero: {
    alignSelf: "center",
    borderRadius: shape.medium,
    padding: spacing.xl,
    marginBottom: spacing.m,
    alignItems: "center",
    justifyContent: "center",
  },
  // A flat section with a readable caption and enough room for a long mnemonic to wrap.
  sectionBlock: {
    paddingVertical: spacing.s,
    gap: spacing.xs,
  },
  sectionRule: {borderBottomWidth: 1},
  sectionGap: {marginTop: spacing.l},
  readingRow: {marginTop: spacing.s},
  caption: {marginBottom: spacing.xs},
  componentRow: {gap: spacing.s},
  componentChip: {
    borderRadius: shape.small,
    minHeight: 48,
    justifyContent: "center",
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.s,
  },
  notes: {gap: spacing.s, marginTop: spacing.s},
  notice: {
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
    borderRadius: shape.medium,
    padding: spacing.l,
    marginTop: spacing.s,
    marginBottom: spacing.l,
  },
  noticeRow: {flexDirection: "row", alignItems: "flex-start", gap: spacing.m},
  noticeBadge: {
    width: 44,
    height: 44,
    borderRadius: shape.small,
    alignItems: "center",
    justifyContent: "center",
  },
  noticeMessage: {flex: 1, paddingTop: spacing.xs},
  noticeAction: {marginTop: spacing.l, width: "100%"},
  skeletonBlock: {borderRadius: shape.small},
  skeletonLabel: {width: 72, height: 14},
  skeletonLine: {height: 14},
  skeletonLineGap: {marginTop: spacing.xs},
  skeletonTail: {width: 160, height: 14},
  skeletonChip: {width: 28, height: 28},
  skeletonChips: {flexDirection: "row", gap: spacing.s},
});
