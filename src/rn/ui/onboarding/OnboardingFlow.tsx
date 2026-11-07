/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run setup flow: three pages, one per thing a reader sets up before the app is theirs —
 * lookup mode, AnkiDroid, WaniKani.
 *
 * The shell shows it in place of the tabs while the stored onboarding step is below 3, and it is
 * shown on the first launch only. Each page does, or hands off to, the real setup for its item and
 * reports the real status; each has its own Skip, and there is deliberately no "skip all" — the
 * reader skips one page at a time, and the last page's Skip finishes the flow. Nothing here creates,
 * deletes or changes any Anki or WaniKani data: a page reads what is already there and, at most,
 * opens the app that owns it.
 *
 * The pages are three views in one component — no carousel library and no navigator. The shell owns
 * the step: a page advances by writing the next step first, and the shell shows the next page once
 * that write lands. A write that cannot be saved keeps the page and offers a retry rather than
 * silently skipping the reader forward.
 */
import React, {useCallback, useEffect, useRef, useState} from "react";
import {
  AccessibilityInfo,
  AppState,
  BackHandler,
  findNodeHandle,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {SafeAreaView, useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {downloadAnkiDroid} from "../anki/anki-actions";
import {Button, IconButton} from "../components";
import {WaniKaniConnect} from "../review/WaniKaniConnect";
import type {WaniKaniConnectOutcome} from "../review/review-store";
import {useLookupModeRequest} from "../settings/settings-components";
import {useTheme} from "../theme";
import {CONTENT_COLUMN, MAX_CONTENT_WIDTH, contentInsetFor, spacing, typeScale} from "../tokens";
import {AnkiArtwork, LookupArtwork, WaniKaniArtwork} from "./OnboardingArtwork";

/** The short name of each page, for the progress row's announcement. */
const PAGES = [
  {name: "Look up mode"},
  {name: "Anki"},
  {name: "WaniKani"},
] as const;

/** One action the footer can offer: a filled primary, or a quiet outlined alternative. */
interface Action {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}

/** What every page is handed by the flow: where it is, and how to leave it. */
interface PageProps {
  step: number;
  /** Asks the shell for the next step; it writes first and shows the page once that lands. */
  onAdvance: (next: number) => void;
  /** The way back a page shows, or null on the first page (there is nothing behind it). */
  onBack: (() => void) | null;
  /** True while a write is still failing, so the page offers its retry instead of advancing. */
  saveError: boolean;
  onRetry: () => void;
  /** The heading the flow focuses when the page arrives. */
  headingRef: React.RefObject<Text | null>;
}

/**
 * The first-run setup flow. [step] is the page to show (0, 1 or 2); the shell reads it before the
 * tabs mount and writes it back through [onAdvance], which resolves `false` when the step could not
 * be saved.
 */
export function OnboardingFlow({
  step,
  onAdvance,
}: {
  step: number;
  onAdvance: (next: number) => Promise<boolean>;
}) {
  const headingRef = useRef<Text>(null);
  const [saveError, setSaveError] = useState(false);
  // The WaniKani token form replaces its page in place; the flow owns the flag so Back can close it
  // before it means "the previous page".
  const [formOpen, setFormOpen] = useState(false);
  const pending = useRef(step);

  const write = useCallback(
    (next: number) => {
      pending.current = next;
      setSaveError(false);
      void onAdvance(next).then((saved) => {
        if (!saved) setSaveError(true);
      });
    },
    [onAdvance],
  );

  const retry = useCallback(() => {
    setSaveError(false);
    void onAdvance(pending.current).then((saved) => {
      if (!saved) setSaveError(true);
    });
  }, [onAdvance]);

  // A new page starts fresh: the WaniKani form closes, and the heading is focused and the step is
  // announced — the progress segments are decorative, so this is how a screen reader hears where the
  // reader is.
  useEffect(() => {
    setFormOpen(false);
    const name = PAGES[step]?.name ?? "";
    const node = findNodeHandle(headingRef.current);
    if (node != null) AccessibilityInfo.setAccessibilityFocus(node);
    AccessibilityInfo.announceForAccessibility(`Step ${step + 1} of 3. ${name}`);
  }, [step]);

  // Android Back: it closes the WaniKani form first, goes back a page on pages 2 and 3, and on the
  // first page is left to the platform — which leaves the app without finishing the flow.
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (formOpen) {
        setFormOpen(false);
        return true;
      }
      if (step === 0) {
        return false;
      }
      write(step - 1);
      return true;
    });
    return () => subscription.remove();
  }, [step, formOpen, write]);

  const shared: PageProps = {
    step,
    onAdvance: write,
    onBack: step > 0 ? () => write(step - 1) : null,
    saveError,
    onRetry: retry,
    headingRef,
  };

  if (step === 0) {
    return <LookupPage {...shared} />;
  }
  if (step === 1) {
    return <AnkiPage {...shared} />;
  }
  return (
    <WaniKaniPage
      {...shared}
      formOpen={formOpen}
      onOpenForm={() => setFormOpen(true)}
      onCloseForm={() => setFormOpen(false)}
    />
  );
}

/** The artwork's full width, and the width the chrome caps it at in the middle mode. */
const ARTWORK_WIDTH = 340;
const ARTWORK_CAPPED_WIDTH = 248;

/** How the page's hero is drawn for the current window: full size, capped, or left out entirely. */
type ArtworkMode = "hidden" | "capped" | "normal";

/**
 * The artwork mode for a window, in the brief's order: a large text size or a short viewport drops
 * the artwork entirely; the next step down caps its width. [usableHeight] is the scroller's visible
 * height less the bottom safe-area allowance it pads with.
 */
function artworkModeFor(fontScale: number, usableHeight: number): ArtworkMode {
  if (fontScale >= 1.6 || usableHeight < 360) return "hidden";
  if (fontScale >= 1.3 || usableHeight < 560) return "capped";
  return "normal";
}

/**
 * One page's chrome: the way back, the progress row, the artwork, the scrolling content column, and
 * the actions.
 *
 * The artwork sits above the heading at the brief's rhythm — 8 dp under the progress row and 16 dp
 * above the heading — and scales from the column's own width, never from the system font scale. It
 * is left out entirely on a short window or at 160% text, so it never crowds the setup content.
 *
 * The actions live at the end of the page's own scroller rather than in a second pinned one, so at a
 * short height or a large text size the primary and the Skip are reached by scrolling the same page —
 * never a footer that a second scroller has hidden.
 */
function PageChrome({
  step,
  artwork: Artwork,
  heading,
  explanation,
  status,
  reassurance,
  primary,
  outlined,
  skipLabel,
  skipAccessibilityLabel,
  onSkip,
  onBack,
  saveError,
  onRetry,
  headingRef,
}: {
  step: number;
  artwork: React.ComponentType<{width: number}>;
  heading: string;
  explanation: string;
  status: React.ReactNode;
  reassurance: string;
  primary: Action | null;
  outlined: Action | null;
  skipLabel: string;
  skipAccessibilityLabel: string;
  onSkip: () => void;
  onBack: (() => void) | null;
  saveError: boolean;
  onRetry: () => void;
  headingRef: React.RefObject<Text | null>;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width, height, fontScale} = useWindowDimensions();
  const inset = contentInsetFor(width);
  // The scroller's own visible height, measured; the window stands in until the first layout, so a
  // page decides its artwork mode from its first frame.
  const [scrollerHeight, setScrollerHeight] = useState<number | null>(null);
  const usableHeight = (scrollerHeight ?? height) - insets.bottom;
  const mode = artworkModeFor(fontScale, usableHeight);
  const innerColumnWidth = Math.min(width, MAX_CONTENT_WIDTH) - 2 * inset;
  const artworkWidth = Math.min(
    innerColumnWidth,
    mode === "capped" ? ARTWORK_CAPPED_WIDTH : ARTWORK_WIDTH,
  );

  return (
    <SafeAreaView
      style={{flex: 1, backgroundColor: colors.background}}
      edges={["top", "left", "right"]}
    >
      {/* The back row keeps its height on the first page too, where it has no chevron, so the
          progress row never moves as the reader goes back and forward. */}
      <View style={{height: 48, justifyContent: "center", paddingHorizontal: inset}}>
        {onBack != null && (
          <IconButton
            name="arrow_back"
            accessibilityLabel="Back"
            onPress={onBack}
            color={colors.onSurface}
          />
        )}
      </View>

      <View
        accessible
        accessibilityLabel={`Step ${step + 1} of 3`}
        style={[
          CONTENT_COLUMN,
          {
            flexDirection: "row",
            alignItems: "center",
            gap: spacing.m,
            paddingHorizontal: inset,
            paddingTop: spacing.xs,
            paddingBottom: spacing.l,
          },
        ]}
      >
        <View
          style={{flex: 1, flexDirection: "row", gap: spacing.s}}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          {[0, 1, 2].map((index) => (
            <View
              key={index}
              style={{
                flex: 1,
                height: 4,
                borderRadius: 2,
                backgroundColor: index === step ? colors.accent : colors.outlineVariant,
              }}
            />
          ))}
        </View>
        <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
          {`Step ${step + 1} of 3`}
        </Text>
      </View>

      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        onLayout={(event) => {
          const next = event.nativeEvent.layout.height;
          setScrollerHeight((current) => (current === next ? current : next));
        }}
        contentContainerStyle={[
          CONTENT_COLUMN,
          {
            flexGrow: 1,
            justifyContent: "space-between",
            paddingHorizontal: inset,
            paddingBottom: insets.bottom + spacing.xl,
          },
        ]}
      >
        <View>
          {mode !== "hidden" && (
            <View
              pointerEvents="none"
              accessible={false}
              accessibilityElementsHidden
              importantForAccessibility="no-hide-descendants"
              style={{alignItems: "center", marginTop: spacing.s, marginBottom: spacing.l}}
            >
              <Artwork width={artworkWidth} />
            </View>
          )}
          <Text
            ref={headingRef}
            accessibilityRole="header"
            style={[typeScale.headlineSmall, {color: colors.onSurface}]}
          >
            {heading}
          </Text>
          <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, marginTop: spacing.m}]}>
            {explanation}
          </Text>
          {status != null && <View style={{marginTop: spacing.l}}>{status}</View>}
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.m}]}>
            {reassurance}
          </Text>
        </View>

        <View style={{gap: spacing.s, marginTop: spacing.xxl}}>
          {saveError && (
            <Text
              accessibilityLiveRegion="polite"
              style={[typeScale.bodySmall, {color: colors.error}]}
            >
              Couldn't save setup progress. Try again.
            </Text>
          )}
          {saveError ? (
            <Button label="Try again" onPress={onRetry} fullWidth />
          ) : (
            primary != null && (
              <Button
                label={primary.label}
                onPress={primary.onPress}
                disabled={primary.disabled}
                fullWidth
              />
            )
          )}
          {outlined != null && (
            <Button
              variant="outlined"
              label={outlined.label}
              onPress={outlined.onPress}
              disabled={outlined.disabled}
              fullWidth
            />
          )}
          <Button
            variant="text"
            label={skipLabel}
            accessibilityLabel={skipAccessibilityLabel}
            onPress={onSkip}
            fullWidth
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

type LookupState = "checking" | "on" | "off" | "failed";

/** Page 1: the accessibility grant that lookup mode is, read afresh on every arrival. */
function LookupPage({step, onAdvance, onBack, saveError, onRetry, headingRef}: PageProps) {
  const {colors} = useTheme();
  const {request, dialog} = useLookupModeRequest();
  const [state, setState] = useState<LookupState>("checking");
  // Whether the app has come back to the foreground while this page was up: only then does an "off"
  // read mean "you returned without enabling it", rather than the first look at the page.
  const [returned, setReturned] = useState(false);
  const readSeq = useRef(0);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setState("checking");
    void GatenAppInfo.isLookupModeActive()
      .then((active) => {
        if (seq === readSeq.current) setState(active ? "on" : "off");
      })
      .catch(() => {
        if (seq === readSeq.current) setState("failed");
      });
  }, []);

  // The grant is given in system Settings, so it is read on arrival and again every time the app
  // comes back to the foreground; a return is never treated as success.
  useEffect(() => {
    read(true);
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        setReturned(true);
        read(false);
      }
    });
    return () => subscription.remove();
  }, [read]);

  const primary: Action =
    state === "checking"
      ? {label: "Checking…", onPress: () => {}, disabled: true}
      : state === "on"
        ? {label: "Continue", onPress: () => onAdvance(step + 1)}
        : state === "failed"
          ? {label: "Try again", onPress: () => read(true)}
          : {
              label: "Turn on lookup mode",
              onPress: () => request(),
            };

  return (
    <>
      <PageChrome
        step={step}
        artwork={LookupArtwork}
        heading="Look up Japanese on your screen"
        explanation="Look up Japanese text while you read in other apps. Gaten needs Android's accessibility permission to start screen lookups."
        status={
          state === "checking" ? null : (
            <View style={{gap: spacing.s}}>
              {state === "on" && (
                <>
                  <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
                    Lookup mode is on.
                  </Text>
                  <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
                    To start a lookup, turn on Floating button or add the Quick Settings tile in
                    Screen lookup settings.
                  </Text>
                </>
              )}
              {state === "off" && returned && (
                <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
                  Lookup mode is still off.
                </Text>
              )}
              {state === "failed" && (
                <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
                  Couldn't check lookup mode.
                </Text>
              )}
            </View>
          )
        }
        reassurance="You can change this later in Settings → Screen lookup."
        primary={primary}
        outlined={null}
        skipLabel="Skip lookup mode"
        skipAccessibilityLabel="Skip lookup mode setup"
        onSkip={() => onAdvance(step + 1)}
        onBack={onBack}
        saveError={saveError}
        onRetry={onRetry}
        headingRef={headingRef}
      />
      {dialog}
    </>
  );
}

type AnkiFailure = "NOT_INSTALLED" | "PERMISSION" | "NOT_SET_UP" | "PROVIDER";

type AnkiPageState =
  | {kind: "checking"}
  | {kind: "notInstalled"}
  | {kind: "needsPermission"}
  | {kind: "notSetUp"}
  | {kind: "denied"}
  | {kind: "failure"}
  | {kind: "connected"; empty: boolean};

/** The `code` a bridge rejection carried, or "" when it carried none. */
function errorCode(error: unknown): string {
  if (typeof error === "object" && error != null && "code" in error) {
    return typeof error.code === "string" ? error.code : "";
  }
  return "";
}

/** The AnkiDroid failure a bridge rejection carried, without any of the provider's internals. */
function ankiFailureOf(error: unknown): AnkiFailure {
  const named = /^ANKI_([A-Z_]+)$/.exec(errorCode(error))?.[1];
  if (named === "NOT_INSTALLED" || named === "PERMISSION" || named === "NOT_SET_UP") {
    return named;
  }
  return "PROVIDER";
}

/** Opens AnkiDroid itself, for the collection it has not set up or the failure a read hit. */
function openAnkiDroid() {
  void GatenAnki.openAnkiDroid().catch(() => {});
}

/** Page 2: AnkiDroid installed, allowed and with a collection to read — done in place. */
function AnkiPage({step, onAdvance, onBack, saveError, onRetry, headingRef}: PageProps) {
  const {colors} = useTheme();
  const [state, setState] = useState<AnkiPageState>({kind: "checking"});
  const readSeq = useRef(0);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setState({kind: "checking"});
    void (async () => {
      try {
        const status = await GatenAnki.status();
        if (seq !== readSeq.current) return;
        if (status === "notInstalled") {
          setState({kind: "notInstalled"});
          return;
        }
        if (status === "needsPermission") {
          setState({kind: "needsPermission"});
          return;
        }
        const collection = await GatenAnki.collection();
        if (seq !== readSeq.current) return;
        setState({kind: "connected", empty: collection.decks.length === 0});
      } catch (error) {
        if (seq !== readSeq.current) return;
        switch (ankiFailureOf(error)) {
          case "NOT_INSTALLED":
            setState({kind: "notInstalled"});
            return;
          case "PERMISSION":
            setState({kind: "needsPermission"});
            return;
          case "NOT_SET_UP":
            setState({kind: "notSetUp"});
            return;
          default:
            setState({kind: "failure"});
        }
      }
    })();
  }, []);

  // The store, the permission dialog and AnkiDroid's own collection all change outside this page, so
  // the status is read on arrival and again on every foreground return.
  useEffect(() => {
    read(true);
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") read(false);
    });
    return () => subscription.remove();
  }, [read]);

  const grant = () => {
    void GatenAnki.requestPermission()
      .then((granted) => {
        if (granted) read(true);
        else setState({kind: "denied"});
      })
      .catch(() => setState({kind: "denied"}));
  };

  let primary: Action | null = null;
  let outlined: Action | null = null;
  let status: React.ReactNode = null;

  switch (state.kind) {
    case "checking":
      primary = {label: "Checking AnkiDroid…", onPress: () => {}, disabled: true};
      break;
    case "notInstalled":
      primary = {label: "Download AnkiDroid", onPress: () => void downloadAnkiDroid()};
      break;
    case "needsPermission":
      primary = {label: "Allow AnkiDroid access", onPress: grant};
      break;
    case "notSetUp":
      outlined = {label: "Open AnkiDroid", onPress: openAnkiDroid};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            AnkiDroid has not set up its collection yet. Open AnkiDroid once and come back.
          </Text>
        </View>
      );
      break;
    case "denied":
      primary = {label: "Try again", onPress: () => read(true)};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            AnkiDroid access wasn't allowed. You can try again or skip this step.
          </Text>
        </View>
      );
      break;
    case "failure":
      primary = {label: "Try again", onPress: () => read(true)};
      outlined = {label: "Open AnkiDroid", onPress: openAnkiDroid};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            Couldn't read AnkiDroid. Open AnkiDroid, then try again.
          </Text>
        </View>
      );
      break;
    case "connected":
      primary = {label: "Continue", onPress: () => onAdvance(step + 1)};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
            AnkiDroid is connected.
          </Text>
          {state.empty && (
            <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
              Create or import a deck in AnkiDroid when you're ready.
            </Text>
          )}
        </View>
      );
      break;
  }

  return (
    <PageChrome
      step={step}
      artwork={AnkiArtwork}
      heading="Study with AnkiDroid"
      explanation="Review your AnkiDroid decks in Gaten and save words as cards. AnkiDroid needs to be installed and allow Gaten access."
      status={status}
      reassurance="You can choose study decks and an export deck later in Settings → Anki cards."
      primary={primary}
      outlined={outlined}
      skipLabel="Skip Anki"
      skipAccessibilityLabel="Skip Anki setup"
      onSkip={() => onAdvance(step + 1)}
      onBack={onBack}
      saveError={saveError}
      onRetry={onRetry}
      headingRef={headingRef}
    />
  );
}

/** Every WaniKani failure the bridge names, so a code from the wire is validated, not trusted. */
const WANIKANI_FAILURES: readonly WaniKaniFailure[] = [
  "OFFLINE",
  "TIMEOUT",
  "RATE_LIMITED",
  "SERVER",
  "TOO_LARGE",
  "MALFORMED",
  "STORAGE",
];

/** The WaniKani failure a bridge rejection carried; a bridge-level error is the network's own. */
function waniKaniFailureOf(error: unknown): WaniKaniFailure {
  const named = /^WK_([A-Z_]+)$/.exec(errorCode(error))?.[1];
  return WANIKANI_FAILURES.find((candidate) => candidate === named) ?? "SERVER";
}

/**
 * Page 3: connect a WaniKani account with a personal access token. The connection screen is the
 * app's own — the token is checked with WaniKani before it is stored — but onboarding owns the
 * outcome and stays here: a working token shows the connected state and a "Get started", never the
 * review hub or a study queue.
 */
function WaniKaniPage({
  step,
  onAdvance,
  onBack,
  saveError,
  onRetry,
  headingRef,
  formOpen,
  onOpenForm,
  onCloseForm,
}: PageProps & {
  formOpen: boolean;
  onOpenForm: () => void;
  onCloseForm: () => void;
}) {
  const {colors} = useTheme();
  // Null while the stored token is being read; true or false once it is known.
  const [connected, setConnected] = useState<boolean | null>(null);

  useEffect(() => {
    let live = true;
    void GatenWaniKani.isConfigured()
      .then((configured) => {
        if (live) setConnected(configured);
      })
      .catch(() => {
        // A read that fails is treated as "not connected": asking for a token is the recoverable
        // direction, and a stored token that works is accepted without asking again.
        if (live) setConnected(false);
      });
    return () => {
      live = false;
    };
  }, []);

  const connect = useCallback(
    async (token: string): Promise<WaniKaniConnectOutcome> => {
      try {
        const result = await GatenWaniKani.connect(token);
        if (result.status === "invalidToken") {
          return {status: "invalidToken"};
        }
        // The token is stored by the native call; onboarding stays here and shows it connected.
        setConnected(true);
        onCloseForm();
        return {status: "connected"};
      } catch (error) {
        return {status: "failed", failure: waniKaniFailureOf(error)};
      }
    },
    [onCloseForm],
  );

  if (formOpen) {
    return (
      <WaniKaniConnect
        rejected={false}
        busy={false}
        onBack={onCloseForm}
        onConnect={connect}
      />
    );
  }

  const primary: Action =
    connected == null
      ? {label: "Checking…", onPress: () => {}, disabled: true}
      : connected
        ? {label: "Get started", onPress: () => onAdvance(3)}
        : {label: "Connect WaniKani", onPress: onOpenForm};

  return (
    <PageChrome
      step={step}
      artwork={WaniKaniArtwork}
      heading="Connect WaniKani"
      explanation="Bring your WaniKani lessons and reviews into Gaten. Connect with a personal access token from your WaniKani account."
      status={
        connected ? (
          <View style={{gap: spacing.s}}>
            <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
              WaniKani is connected.
            </Text>
          </View>
        ) : null
      }
      reassurance="You can connect or disconnect later in Settings → WaniKani."
      primary={primary}
      outlined={null}
      skipLabel="Skip WaniKani"
      skipAccessibilityLabel="Skip WaniKani setup and finish"
      onSkip={() => onAdvance(3)}
      onBack={onBack}
      saveError={saveError}
      onRetry={onRetry}
      headingRef={headingRef}
    />
  );
}
