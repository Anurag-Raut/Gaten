/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The app shell: three tabs, each with its own persistent stack, the custom bottom bar, the theme
 * from the bridge, and the one piece of cross-tab coordination the Compose shell owned — the settings
 * page an outside launch asked for.
 *
 * A page opened from inside the app never crosses a tab: it is pushed onto the stack of the tab the
 * reader is on, so the move is the platform's own push and Back returns them to the card or the word
 * they left. Only an outside launch selects the Settings tab for a page.
 *
 * Studying is one of those pushed pages, not a tab: Home's two service cards ask the review store for
 * a step and push the flow onto Home's own stack.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {
  Animated,
  BackHandler,
  DeviceEventEmitter,
  StatusBar,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {
  NavigationContainer,
  StackActions,
  useNavigationContainerRef,
  type CompositeScreenProps,
  type NavigationRoute,
  type ParamListBase,
  type Theme as NavigationTheme,
} from "@react-navigation/native";
import {createMaterialTopTabNavigator} from "@react-navigation/material-top-tabs";
import type {
  MaterialTopTabBarProps,
  MaterialTopTabScreenProps,
} from "@react-navigation/material-top-tabs";
import {createNativeStackNavigator} from "@react-navigation/native-stack";
import type {
  NativeStackNavigationOptions,
  NativeStackScreenProps,
} from "@react-navigation/native-stack";
import {useReducedMotion} from "react-native-reanimated";
import {useTheme} from "./theme";
import {APP_FONT, spacing, typeScale} from "./tokens";
import {Button} from "./components";
import {BottomBar} from "./BottomBar";
import {HomeScreen} from "./HomeScreen";
import {AnalyticsScreen} from "./analytics/AnalyticsScreen";
import {
  SearchKanjiScreen,
  SearchRadicalScreen,
  SearchWordScreen,
} from "./SearchScreen";
import {ReviewScreen} from "./ReviewScreen";
import {SettingsTab, SettingsPageScreen} from "./SettingsScreen";
import {
  SETTINGS_PAGES,
  freshSettingsInstance,
  openSettingsPage,
  type AnalyticsStackParamList,
  type HomeStackParamList,
  type RootTabParamList,
  type SettingsPageName,
  type SettingsPageParams,
  type SettingsStackParamList,
} from "./navigation";
import * as ShellBridge from "../native/GatenShell";
import * as GatenAppInfo from "../native/GatenAppInfo";
import {primeOnboardingStep, resetOnboardingStepPrime} from "./boot";
import {HandoffReadinessProvider, useHandoffReadiness} from "./handoff";
import {OnboardingFlow} from "./onboarding/OnboardingFlow";
import {STEP_SCENE_MS} from "./onboarding/onboarding-motion";
import {reviewStore} from "./review/review-store";
import * as searchStore from "./search/searchStore";

/** The Home root's props: its own stack plus the tab level above it, for the review flow's page. */
type HomeRouteProps<RouteName extends keyof HomeStackParamList> =
  CompositeScreenProps<
    NativeStackScreenProps<HomeStackParamList, RouteName>,
    MaterialTopTabScreenProps<RootTabParamList>
  >;

type HomeStackNavigation = NativeStackScreenProps<
  HomeStackParamList,
  keyof HomeStackParamList
>["navigation"];

/** The top of a stack — the page a StackRouter's `push` would land above. */
function topRoute(navigation: HomeStackNavigation) {
  const state = navigation.getState();
  return state.routes[state.index];
}

/**
 * Opens the review flow above Home: `ask` moves the store to the step this page is to draw, and then
 * the page is pushed. A second tap while the flow is already on top must not stack a second copy of
 * it — nor read the collection a second time — which is the same guard the word route uses.
 */
function openReview(navigation: HomeStackNavigation, ask: () => void): void {
  if (topRoute(navigation).name === "Review") {
    return;
  }
  ask();
  navigation.push("Review");
}

/**
 * The kanji reference, opened or toggled from a word: tapping the open one closes it (no tick —
 * only opening something new ticks), a different kanji replaces the one on top, and a chain never
 * exceeds one kanji plus one radical.
 */
function openKanji(navigation: HomeStackNavigation, character: string): void {
  const top = topRoute(navigation);
  const params = top.params;
  if (
    top.name === "Kanji" &&
    params !== undefined &&
    "character" in params &&
    params.character === character
  ) {
    navigation.goBack();
    return;
  }
  // No tick here: the tile the reader tapped is a control of its own and plays one (PressableScale).
  if (top.name === "Kanji" || top.name === "Radical") {
    navigation.replace("Kanji", {character});
  } else {
    navigation.push("Kanji", {character});
  }
}

/** The radical reference, with the same toggle semantics as the kanji's. */
function openRadical(navigation: HomeStackNavigation, id: number): void {
  const top = topRoute(navigation);
  const params = top.params;
  if (
    top.name === "Radical" &&
    params !== undefined &&
    "id" in params &&
    params.id === id
  ) {
    navigation.goBack();
    return;
  }
  // Same as the kanji: the radical row the reader tapped plays its own tick.
  if (top.name === "Radical" || top.name === "Kanji") {
    navigation.replace("Radical", {id});
  } else {
    navigation.push("Radical", {id});
  }
}

/**
 * Runs a chip's own tap only while its page is still the focused one. A push takes the platform's
 * slide to move the page out from under the finger, but the stack's top is the *new* page the
 * instant the state commits — so without this, a second tap in that window is read by the page it
 * just opened as a tap on the open reference and pops it straight back out, leaving the reader on
 * the results. Focus flips on the state change, not on the animation, so this is deterministic.
 */
function whenFocused(navigation: HomeStackNavigation, run: () => void): void {
  if (navigation.isFocused()) {
    run();
  }
}

/**
 * The Home tab's root: the dashboard with its own search on it. The pages it starts — a word, the
 * kanji and radical references, a settings page — are Home's own stack, so Back is one step out of
 * whatever the reader opened rather than a jump to another tab.
 */
function HomeRoute({navigation}: HomeRouteProps<"HomeTab">) {
  const handoff = useHandoffReadiness();
  return (
    <HomeScreen
      // HomeScreen is drawn by this route rather than registered as a screen, so React Navigation
      // never hands it a navigation of its own: the lookup mark's page and the review flow's two
      // screens are reached through Home's stack only because it is passed down here.
      navigation={navigation}
      // The completed setup's handoff waits for Home's real first layout before it reveals it.
      onReady={handoff?.reportHomeReady}
      onOpenWord={(entryId) => {
        // A second tap on the same row must not stack a second copy of the page it just opened —
        // the same guard the reference chips use for their own toggle.
        const top = topRoute(navigation);
        const params = top.params;
        if (
          top.name === "Word" &&
          params !== undefined &&
          "entryId" in params &&
          params.entryId === entryId
        ) {
          return;
        }
        navigation.push("Word", {entryId});
      }}
      // The review flow's two service screens, each opened on its own step: Home's Anki card asks the
      // store for the Anki step and then pushes the flow, so the reader lands on the deck list (or on
      // the AnkiDroid state that stops it) rather than on a page that has to be tapped again.
      onOpenAnki={() => openReview(navigation, () => reviewStore.openAnki())}
      onOpenWaniKani={() => openReview(navigation, () => reviewStore.openWaniKani())}
    />
  );
}

/**
 * The review flow, on Home's own stack: AnkiDroid's deck list and cards, or WaniKani's connection
 * screen and queue. Which of the two it is — and which step of it — is the store's own state, set
 * before this page was pushed; Back pops the page, which is the whole of leaving a step.
 */
function ReviewRoute({navigation}: NativeStackScreenProps<HomeStackParamList, "Review">) {
  return (
    <ReviewScreen
      onExit={() => navigation.goBack()}
      // The review flow's own way into WaniKani's settings, on Home's stack: connecting a token
      // from an open reference page returns to the card, not to another tab's hub.
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
    />
  );
}

function HomeWordRoute({
  route,
  navigation,
}: NativeStackScreenProps<HomeStackParamList, "Word">) {
  return (
    <SearchWordScreen
      entryId={route.params.entryId}
      onBack={() => navigation.goBack()}
      onOpenKanji={(character) =>
        whenFocused(navigation, () => openKanji(navigation, character))
      }
    />
  );
}

function HomeKanjiRoute({
  route,
  navigation,
}: NativeStackScreenProps<HomeStackParamList, "Kanji">) {
  return (
    <SearchKanjiScreen
      character={route.params.character}
      onBack={() => navigation.goBack()}
      onOpenRadical={(id) => whenFocused(navigation, () => openRadical(navigation, id))}
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
    />
  );
}

function HomeRadicalRoute({
  route,
  navigation,
}: NativeStackScreenProps<HomeStackParamList, "Radical">) {
  return (
    <SearchRadicalScreen
      id={route.params.id}
      onBack={() => navigation.goBack()}
      onOpenRadical={(id) => whenFocused(navigation, () => openRadical(navigation, id))}
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
    />
  );
}

/**
 * The Analytics tab's root. Its cards may need a service configured, so it opens the settings page
 * on its own stack: connecting Anki or WaniKani from a card returns the reader to the dashboard.
 */
function AnalyticsRoute({
  navigation,
}: NativeStackScreenProps<AnalyticsStackParamList, "AnalyticsTab">) {
  return (
    <AnalyticsScreen
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
    />
  );
}

/**
 * A pushed page enters from the trailing edge. The native stack drives this through
 * react-native-screens' own Android anim resources on the platform animator, so a push costs no
 * JS frames; the duration and curve are the platform's (300 ms), which is as close as the
 * platform gets to the design's 380 ms emphasized slide. `animationDuration` is deliberately
 * absent: on Android it is accepted and ignored.
 */
const STACK_SCREEN_OPTIONS: NativeStackNavigationOptions = {
  headerShown: false,
  animation: "slide_from_right",
};

/**
 * The three tabs are one horizontal pager — `@react-navigation/material-top-tabs`, its bar at the
 * bottom — so a tab change is the page itself sliding across at the gesture's own pace, exactly as
 * a page moves inside a tab. The pager is the library's, its `position` value is what the bar's
 * indicator is read from, and nothing about the move is animated by hand.
 *
 * Two things on it are gated, both as screen options so they follow the focused tab:
 *
 * - `swipeEnabled` only while that tab is on its own ground. A page pushed inside a tab (a word, a
 *   kanji, a settings page, the review flow) owns the screen whole, and a swipe there would be
 *   fighting the page's own horizontal gestures — the kanji strip, a card's own scrollers.
 * - `animationEnabled` off when the system asks for reduced motion, so a tap lands on the spot
 *   instead of sliding there. A swipe is the reader's own gesture and stays available.
 *
 * Only the focused tab's scene is mounted to begin with (`lazy: true`, no preload distance): Home
 * while it is focused, and the other two deferred. With every scene eager, the commit that revealed
 * Home also mounted Analytics' and Settings' native stacks and first renders — so the reveal mounts
 * Home and the bar alone (and Home's own mount is moved off the reveal entirely: the shell mounts the
 * tabs beneath the flow one macrotask after the flow's last page appears, so the completed setup's
 * reveal is a commit and an [HANDOFF_FADE_MS] fade rather than a mount).
 *
 * The deferral is a head start, not a permanent state: the other two are mounted behind Home once it
 * and the bar are the visible screen ([TAB_WARMUP_DELAY_MS]). A scene mounted by its own selection
 * is an empty page for the frames its stack and first render take, which is the blank the reader saw
 * on their first tap; mounted while they are still on Home, the tap finds the page already drawn.
 * Each screen's data work is gated on focus, not on mount, so a mounted tab fetches nothing until it
 * is actually selected.
 */
const Tab = createMaterialTopTabNavigator<RootTabParamList>();

/** The completed setup's whole-page crossfade, in ms: inside the tap-to-Home budget. */
const HANDOFF_FADE_MS = 50;

/**
 * The warm-up of the two tabs Home is not, in ms after Home and the bar are the visible screen. Long
 * enough that the mounts land after the open's own work is done and well before the reader's first
 * tap; the two are [TAB_WARMUP_STEP_MS] apart so each mount is its own commit rather than one.
 */
const TAB_WARMUP_DELAY_MS = 220;
const TAB_WARMUP_STEP_MS = 80;

const HomeStack = createNativeStackNavigator<HomeStackParamList>();
const AnalyticsStack = createNativeStackNavigator<AnalyticsStackParamList>();
const SettingsStack = createNativeStackNavigator<SettingsStackParamList>();

function HomeTabStack() {
  return (
    <HomeStack.Navigator screenOptions={STACK_SCREEN_OPTIONS}>
      <HomeStack.Screen name="HomeTab" component={HomeRoute} />
      <HomeStack.Screen name="Review" component={ReviewRoute} />
      <HomeStack.Screen name="Word" component={HomeWordRoute} />
      <HomeStack.Screen name="Kanji" component={HomeKanjiRoute} />
      <HomeStack.Screen name="Radical" component={HomeRadicalRoute} />
      <HomeStack.Screen name="SettingsPage" component={SettingsPageRoute} />
    </HomeStack.Navigator>
  );
}

function AnalyticsTabStack() {
  return (
    <AnalyticsStack.Navigator screenOptions={STACK_SCREEN_OPTIONS}>
      <AnalyticsStack.Screen name="AnalyticsTab" component={AnalyticsRoute} />
      <AnalyticsStack.Screen name="SettingsPage" component={SettingsPageRoute} />
    </AnalyticsStack.Navigator>
  );
}

function SettingsTabStack() {
  return (
    <SettingsStack.Navigator screenOptions={STACK_SCREEN_OPTIONS}>
      <SettingsStack.Screen name="SettingsTab" component={SettingsTab} />
      <SettingsStack.Screen name="SettingsPage" component={SettingsPageRoute} />
    </SettingsStack.Navigator>
  );
}

/**
 * One settings page, on whichever stack pushed it — Home's, Analytics', or Settings' own. The page
 * itself is the same six screens the hub lists; only the stack it sits on changes, which is what
 * keeps Back on the tab the reader opened it from.
 */
function SettingsPageRoute({route}: {route: {params: SettingsPageParams}}) {
  return <SettingsPageScreen page={route.params.page} />;
}

/**
 * Keeps the pager still while the focused tab is not on its own ground: a page pushed inside a tab
 * (a word, a kanji, a settings page, the review flow) must not be swiped away.
 *
 * Applied through `navigation.setOptions` rather than declared in the navigator's `screenOptions`
 * alone, because the declarative gate did not hold: on the moto g35 a horizontal drag on the pushed
 * word detail moved the pager to the next tab. `setOptions` is the documented way to change a mounted
 * screen's options, and the depth it is computed from is the one the bar already hides itself with —
 * which is demonstrably live.
 */
function SwipeGate({
  state,
  descriptors,
}: Pick<MaterialTopTabBarProps, "state" | "descriptors">) {
  const focused = state.routes[state.index];
  const depth = focused?.state?.index ?? 0;
  // The focused tab screen's own navigation prop: `setOptions` is what changes a mounted screen's
  // options, and it targets exactly this route.
  const setOptions = descriptors[focused?.key ?? ""]?.navigation.setOptions;
  useEffect(() => {
    setOptions?.({swipeEnabled: depth === 0});
  }, [setOptions, depth]);
  return null;
}

export function AppShell() {
  const {colors, dark} = useTheme();
  const searchActive = searchStore.useSearchState().searchActive;
  const navigationRef = useNavigationContainerRef<RootTabParamList>();
  // The pager's own first guess at its size, so the bar's indicator is already at the right tab on
  // the first frame — the navigator measures itself a frame later. Only the width matters; the bar's
  // height comes from its own constant and the scene takes the rest.
  const {width} = useWindowDimensions();

  // The first-run setup step: read before the tabs mount, and written back as the reader moves
  // through the flow. While it is below 3 the flow is shown in place of the navigator, and once it
  // is 3 the tabs are the app. `null` means the read is still on its way, so nothing is shown on a
  // guess — and a read that fails offers a retry rather than opening the tabs over a setup the
  // reader may still owe.
  const [onboardingStep, setOnboardingStep] = useState<number | null>(null);
  const [stepReadFailed, setStepReadFailed] = useState(false);
  // The warm mount: the tabs are mounted beneath the flow once the flow reaches its last page, one
  // macrotask after that page appears, so the final tap finds Home and the bar already laid out.
  // The deferral keeps the mount off the transition onto that page (and the first frame is never
  // touched); a reader who completes before it lands still waits for readiness, never a blank Home.
  const [prewarm, setPrewarm] = useState(false);
  // The tabs mounted behind Home: 0 nothing yet, 1 Analytics, 2 Analytics and Settings. Counted in
  // steps rather than held as two flags because the second waits on the first, so the two mounts are
  // two commits.
  const [warmTabs, setWarmTabs] = useState(0);
  // The completed handoff: the tabs are mounted beneath the setup flow while Home and the bar
  // settle, and the flow is only removed once it has faded. [handoffFading] is the second half —
  // the fade has started, so the flow is inert and the tabs own the screen. With the warm mount the
  // tabs are normally already laid out by this point; this handoff still carries the case where the
  // reader completed before the mount landed.
  const [handoff, setHandoff] = useState(false);
  const [handoffFading, setHandoffFading] = useState(false);
  const [homeReady, setHomeReady] = useState(false);
  const [barReady, setBarReady] = useState(false);
  const overlayOpacity = useRef(new Animated.Value(1)).current;
  const overlayAnimation = useRef<Animated.CompositeAnimation | null>(null);
  const handoffLive = useRef(true);
  // Once the completed step is saved, the flow is still mounted for its own handoff: a late Back,
  // Skip or swipe write must not put an earlier step back on top of the one already saved.
  const completed = useRef(false);

  // Readiness as reported, and whether both had reported when the final advance was entered: the
  // fallback wait (and its extra frame) is needed only when one of them had not.
  const readyRef = useRef({home: false, bar: false});
  const prewarmedAtEntry = useRef(false);

  const reportHomeReady = useCallback(() => {
    readyRef.current.home = true;
    setHomeReady(true);
  }, []);
  const reportBarReady = useCallback(() => {
    readyRef.current.bar = true;
    setBarReady(true);
  }, []);
  const handoffReadiness = useMemo(
    () => ({reportHomeReady, reportBarReady}),
    [reportHomeReady, reportBarReady],
  );

  const readOnboardingStep = useCallback((fresh = false) => {
    setStepReadFailed(false);
    if (fresh) {
      resetOnboardingStepPrime();
    }
    void primeOnboardingStep()
      // Clamped to the four legal values, so a corrupt or older value can never draw a page that
      // does not exist: anything past the flow's last page opens the tabs.
      .then((step) => setOnboardingStep(Math.max(0, Math.min(3, step))))
      .catch(() => setStepReadFailed(true));
  }, []);

  useEffect(() => {
    readOnboardingStep();
  }, [readOnboardingStep]);

  // Writes the next step before the page behind it changes; `false` keeps the reader where they are
  // so the flow can offer its retry instead of silently moving on.
  const advanceOnboarding = useCallback(
    async (next: number): Promise<boolean> => {
      if (completed.current) {
        // The flow is only still on screen for the handoff fade. Answering `true` keeps it where it
        // is without writing: the completed step stands, and the write's own retry path is untouched.
        return true;
      }
      if (next >= 3) {
        // Whether Home and the bar had already reported: only then can the reveal skip its settling
        // frame. Read before the write, so it is the state the tap found.
        prewarmedAtEntry.current = readyRef.current.home && readyRef.current.bar;
      }
      try {
        await GatenAppInfo.setOnboardingStep(next);
        // The primed read now answers with the step from before this write; drop it so a later shell
        // remount under the same JS runtime reads the step just persisted instead of the old one. A
        // failed write keeps the primed read for the flow's own retry, which reads fresh.
        resetOnboardingStepPrime();
        if (next >= 3) {
          completed.current = true;
          setHandoff(true);
        }
        setOnboardingStep(next);
        return true;
      } catch {
        return false;
      }
    },
    [],
  );

  // Setup is done and the tabs are the app. [tabsMounted] can be true earlier — the warm mount
  // mounts the tabs beneath the flow's last page — so anything that must not act while the flow
  // owns the screen (the outside-launch settings request below) keys off this instead.
  const setupDone = onboardingStep !== null && onboardingStep >= 3;
  const tabsMounted = setupDone || prewarm;
  const flowMounted = onboardingStep !== null && (onboardingStep < 3 || handoff);

  // The warm mount: on the flow's last page, mount the tabs one macrotask later. The timer is
  // dropped if the step leaves that page first, and the completion path's readiness gate then
  // carries the handoff as it always did.
  useEffect(() => {
    // The flow's last page is step 2 (its pages are 0, 1, 2); only there is the tabs' mount moved
    // earlier. Any other step leaves the completion path to mount them itself.
    if (prewarm || onboardingStep !== 2) {
      return;
    }
    // The warm mount is a head start for the final tap, not part of the page's arrival: mounting
    // Home and the bar on the same commit as the scene's 340 ms handoff made the last page's
    // transition drop 150-250 ms frames on the phone. Let the scene land first, then mount behind
    // it — still well before the reader can reach the final action.
    const timer = setTimeout(() => setPrewarm(true), STEP_SCENE_MS + 120);
    return () => clearTimeout(timer);
  }, [onboardingStep, prewarm]);

  // The warm-up of the tabs Home is not: once the setup flow has gone and Home and the bar are the
  // screen, one tab per step. The reader is looking at Home throughout, so each scene mounts behind
  // it instead of on the frame their tap selects it — which is what used to show an empty page and
  // then the page itself. Nothing is skipped: a tap that beats the warm-up still mounts its tab.
  //
  // Mounting is all that happens early. React Navigation composes a child navigator's focus from its
  // parent's (NavigationProvider: a route is focused only while its navigator is), so a warmed scene
  // reads `useIsFocused()` as false until its tab is selected: the analytics entrance has not started
  // (its figures are still at 0, and the one-shot sweep is spent when the reader arrives), and the
  // reads gated on focus — the Anki snapshot, the settings hub's summaries — stay unasked.
  useEffect(() => {
    if (warmTabs >= 2 || !setupDone || handoff) {
      return;
    }
    const timer = setTimeout(
      () => setWarmTabs((step) => step + 1),
      warmTabs === 0 ? TAB_WARMUP_DELAY_MS : TAB_WARMUP_STEP_MS,
    );
    return () => clearTimeout(timer);
  }, [handoff, setupDone, warmTabs]);

  // The settings page an outside launch asked for: matched against the pages Settings itself
  // knows, a match selects the Settings tab and pushes that page — onto the Settings stack's own
  // state when it is already mounted, so a page the reader left open is stacked above rather than
  // swapped for the new one — and every request, matched or not, is consumed exactly once.
  //
  // It is read only once setup is done: while the setup flow owns the screen there is no navigator
  // to push into, and a request must not be consumed into nothing — nor push onto a warm-mounted
  // navigator the reader cannot see. The pending request waits in the bridge until then.
  useEffect(() => {
    if (!setupDone) {
      return;
    }
    let live = true;
    let handling = false;
    const handle = async (page: string | null) => {
      if (!live || handling || page === null) {
        return;
      }
      handling = true;
      const requested = SETTINGS_PAGES.find(
        (candidate) => candidate === page,
      );
      const container = navigationRef.current;
      if (requested !== undefined && container != null) {
        const root = container.getRootState();
        const settingsRoute = root?.routes.find(
          (route) => route.name === "Settings",
        );
        const stackKey = settingsRoute?.state?.key;
        const params = {
          page: requested,
          instance: freshSettingsInstance(),
        };
        if (root !== undefined && stackKey !== undefined) {
          container.dispatch({
            ...StackActions.push("SettingsPage", params),
            target: stackKey,
          });
          if (root.routes[root.index]?.name !== "Settings") {
            container.navigate("Settings");
          }
        } else {
          // The Settings tab has never been mounted: `initial: false` is what keeps the hub *under*
          // the page. Without it React Navigation makes the page the child stack's only route — the
          // hub is never created — and Back from the page has nothing to pop, so it bubbles out to
          // the tab policy instead of returning to the hub. With it the child starts on its hub and
          // the page arrives over it, exactly as it does when the tab is already mounted.
          container.navigate("Settings", {
            screen: "SettingsPage",
            params,
            initial: false,
          });
        }
      }
      await ShellBridge.consumeSettingsPageRequest();
      handling = false;
    };
    ShellBridge.getSettingsPageRequest()
      .then((pending) => handle(pending))
      .catch(() => {});
    const subscription = DeviceEventEmitter.addListener(
      "gaten.settingsPageRequest",
      (event: {page?: string} | undefined) => handle(event?.page ?? null),
    );
    return () => {
      live = false;
      subscription.remove();
    };
  }, [navigationRef, setupDone]);

  const navigationTheme = useMemo<NavigationTheme>(() => {
    return {
      dark,
      colors: {
        primary: colors.primary,
        background: colors.background,
        card: colors.surface,
        text: colors.onSurface,
        border: colors.outlineVariant,
        notification: colors.primary,
      },
      fonts: {
        regular: {fontFamily: APP_FONT, fontWeight: "400"},
        medium: {fontFamily: APP_FONT, fontWeight: "500"},
        bold: {fontFamily: APP_FONT, fontWeight: "700"},
        heavy: {fontFamily: APP_FONT, fontWeight: "700"},
      },
    };
  }, [colors, dark]);

  // A tap on a tab slides the pager to it (that is what `animationEnabled` means to the library);
  // with reduced motion on it lands on the spot, as every other animation in the app does.
  const reducedMotion = useReducedMotion();

  // The fade itself: one whole-overlay opacity, native driver, [HANDOFF_FADE_MS]. Reduced motion
  // drops the flow on the frame its replacement is ready, with no animation. The animation is
  // stopped and the state writes are dropped if the shell has already unmounted.
  const finishHandoff = useCallback(() => {
    if (handoffLive.current) {
      setHandoff(false);
      setHandoffFading(false);
    }
  }, []);

  const startHandoff = useCallback(() => {
    if (!handoffLive.current || handoffFading) {
      return;
    }
    if (reducedMotion) {
      finishHandoff();
      return;
    }
    setHandoffFading(true);
    overlayAnimation.current?.stop();
    const animation = Animated.timing(overlayOpacity, {
      toValue: 0,
      duration: HANDOFF_FADE_MS,
      useNativeDriver: true,
    });
    overlayAnimation.current = animation;
    animation.start(({finished}) => {
      overlayAnimation.current = null;
      if (finished) {
        finishHandoff();
      }
    });
  }, [finishHandoff, handoffFading, overlayOpacity, reducedMotion]);

  // The flow goes only once the frame it reveals is settled: Home has laid out with its insets and
  // the bottom bar has drawn its measured form. When the warm mount already satisfied both, the
  // frame the fade reveals is long committed and the fade starts in this commit; otherwise — the
  // reader completed before Home or the bar had reported — one more animation frame lets the frame
  // that just became ready commit, so the fade can never uncover a half-built Home.
  useEffect(() => {
    if (!handoff || handoffFading || !homeReady || !barReady) {
      return;
    }
    if (prewarmedAtEntry.current) {
      startHandoff();
      return;
    }
    let cancelled = false;
    const frame = requestAnimationFrame(() => {
      if (!cancelled) {
        startHandoff();
      }
    });
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
    };
  }, [barReady, handoff, handoffFading, homeReady, startHandoff]);

  // A completed flow is still mounted for the handoff: Back must belong to the shell, not to the
  // flow, until it has gone. Registered after the flow's own listener, so it is the one called.
  useEffect(() => {
    if (!handoff) {
      return;
    }
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => true);
    return () => subscription.remove();
  }, [handoff]);

  useEffect(() => {
    // The ref outlives an effect replay (StrictMode, Fast Refresh), so the setup restores it: the
    // cleanup still drops the flag on a real unmount, and the callbacks keep guarding on it.
    handoffLive.current = true;
    return () => {
      handoffLive.current = false;
      overlayAnimation.current?.stop();
    };
  }, []);

  // The setup flow owns the screen until the stored step reaches 3. The step is read first, so the
  // first frame is never the tabs over a flow the reader still owes; a read that failed offers a
  // retry rather than opening them.
  if (onboardingStep === null) {
    return (
      <View style={{flex: 1, backgroundColor: colors.background}}>
        <StatusBar barStyle={dark ? "light-content" : "dark-content"} />
        {stepReadFailed && (
          <View
            style={{
              flex: 1,
              alignItems: "center",
              justifyContent: "center",
              gap: spacing.l,
              paddingHorizontal: spacing.xl,
            }}
          >
            <Text
              style={[
                typeScale.bodyMedium,
                {color: colors.onSurfaceVariant, textAlign: "center"},
              ]}
            >
              Couldn't save setup progress. Try again.
            </Text>
            <Button label="Try again" onPress={() => readOnboardingStep(true)} />
          </View>
        )}
      </View>
    );
  }

  // The tabs are mounted beneath the flow from its last page and stay inert — to touch and to the
  // accessibility tree — the whole time the flow is on screen: the flow is the one thing the reader
  // can see and reach. Once the fade starts the two swap, the flow goes inert and the tabs take
  // over; with no flow at all they are live from their first frame.
  const underlayInert = (onboardingStep < 3 || handoff) && !handoffFading;

  return (
    <View style={{flex: 1, backgroundColor: colors.background}}>
      <StatusBar barStyle={dark ? "light-content" : "dark-content"} />
      {tabsMounted && (
        <HandoffReadinessProvider value={handoffReadiness}>
          <View
            style={{flex: 1}}
            pointerEvents={underlayInert ? "none" : "auto"}
            accessibilityElementsHidden={underlayInert}
            importantForAccessibility={underlayInert ? "no-hide-descendants" : "auto"}
          >
            <NavigationContainer ref={navigationRef} theme={navigationTheme}>
              <Tab.Navigator
                tabBarPosition="bottom"
                initialLayout={{width}}
                // The keyboard goes when the tab under the reader changes — by tap or by swipe — so a
                // Search field can never follow the reader onto another tab. That is the library's own
                // `auto`, and nothing in the shell asks for the field to be focused any more (the field
                // opens the keyboard only when the reader taps it), so there is no focus to preserve and no
                // reason to hold the dismissal back.
                keyboardDismissMode="auto"
                tabBar={(props) => (
                  <>
                    <SwipeGate
                      state={props.state}
                      descriptors={props.descriptors}
                    />
                    <BottomBar
                      {...props}
                      searchActive={
                        searchActive && props.state.routes[props.state.index]?.name === "Home"
                      }
                      onReady={reportBarReady}
                    />
                  </>
                )}
                // `route.state` is the tab's own stack (the pager keeps one per tab); the typed route for a
                // tab whose params are `undefined` does not declare it, which is why the route here is
                // `NavigationRoute`.
                screenOptions={({route}: {route: NavigationRoute<ParamListBase, string>}) => ({
                  headerShown: false,
                  lazy: true,
                  lazyPreloadDistance: 0,
                  animationEnabled: !reducedMotion,
                  swipeEnabled: (route.state?.index ?? 0) === 0,
                })}
              >
                <Tab.Screen name="Home" component={HomeTabStack} />
                {/* `lazy` is per screen: the shell's own `lazy: true` gates every scene, and the
                    warm-up clears it for one tab at a time (see [TAB_WARMUP_DELAY_MS]). */}
                <Tab.Screen
                  name="Analytics"
                  component={AnalyticsTabStack}
                  options={{lazy: warmTabs < 1}}
                />
                <Tab.Screen
                  name="Settings"
                  component={SettingsTabStack}
                  options={{lazy: warmTabs < 2}}
                />
              </Tab.Navigator>
            </NavigationContainer>
          </View>
        </HandoffReadinessProvider>
      )}
      {flowMounted && (
        <Animated.View
          style={[StyleSheet.absoluteFill, {opacity: overlayOpacity}]}
          // The flow stops taking touches the moment the completed step is saved, not when the fade
          // starts: a tap on the old Connect form after the final Skip must not open it.
          pointerEvents={handoff ? "none" : "auto"}
          accessibilityElementsHidden={handoffFading}
          importantForAccessibility={handoffFading ? "no-hide-descendants" : "auto"}
        >
          <View style={{flex: 1, backgroundColor: colors.background}}>
            <OnboardingFlow
              step={Math.min(onboardingStep, 2)}
              onAdvance={advanceOnboarding}
            />
          </View>
        </Animated.View>
      )}
    </View>
  );
}
