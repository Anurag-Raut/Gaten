/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The app shell: four tabs, each with its own persistent stack, the custom bottom bar, the theme
 * from the bridge, and the two pieces of cross-tab coordination the Compose shell owned — the
 * settings page an outside launch asked for, and whether a review session currently holds the
 * Review tab.
 *
 * A settings page opened from inside the app never crosses a tab: it is pushed onto the stack of the
 * tab the reader is on, so the move is the platform's own push and Back returns them to the card or
 * the word they left. Only an outside launch selects the Settings tab for a page.
 */
import React, {useEffect, useMemo, useState} from "react";
import {
  DeviceEventEmitter,
  useColorScheme,
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
import {BottomBar} from "./BottomBar";
import {HomeScreen} from "./HomeScreen";
import {AnalyticsScreen} from "./analytics/AnalyticsScreen";
import {
  SearchKanjiScreen,
  SearchRadicalScreen,
  SearchWordScreen,
} from "./SearchScreen";
import {ReviewTab} from "./ReviewScreen";
import {SettingsTab, SettingsPageScreen} from "./SettingsScreen";
import {SessionContext} from "./session";
import {
  SETTINGS_PAGES,
  freshSettingsInstance,
  openSettingsPage,
  type AnalyticsStackParamList,
  type HomeStackParamList,
  type ReviewStackParamList,
  type RootTabParamList,
  type SettingsPageName,
  type SettingsPageParams,
  type SettingsStackParamList,
} from "./navigation";
import * as ShellBridge from "../native/GatenShell";
import * as Haptics from "../native/GatenHaptics";
import {reviewStore} from "./review/review-store";

/** The Home root's props: its own stack plus the tab level above it, for the Review tab's card. */
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
  Haptics.play("tick");
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
  Haptics.play("tick");
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
  return (
    <HomeScreen
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
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
      // The Review tab's own ground — the picker — wherever its stack was left.
      onOpenReview={() =>
        navigation.navigate("Review", {screen: "ReviewTab", pop: true})
      }
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
      onOpenSettingsPage={(page) => openSettingsPage(navigation, page)}
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
 * The four tabs are one horizontal pager — `@react-navigation/material-top-tabs`, its bar at the
 * bottom — so a tab change is the page itself sliding across at the gesture's own pace, exactly as
 * a page moves inside a tab. The pager is the library's, its `position` value is what the bar's
 * indicator is read from, and nothing about the move is animated by hand.
 *
 * Two things on it are gated, both as screen options so they follow the focused tab:
 *
 * - `swipeEnabled` only while that tab is on its own ground. A page pushed inside a tab (a word, a
 *   kanji, a settings page) or a running review session owns the screen whole, and a swipe there
 *   would be fighting the page's own horizontal gestures — the kanji strip, a card's own scrollers.
 * - `animationEnabled` off when the system asks for reduced motion, so a tap lands on the spot
 *   instead of sliding there. A swipe is the reader's own gesture and stays available.
 *
 * `lazy` keeps a tab that has never been opened from mounting, as the bottom tab navigator did.
 */
const Tab = createMaterialTopTabNavigator<RootTabParamList>();

const HomeStack = createNativeStackNavigator<HomeStackParamList>();
const AnalyticsStack = createNativeStackNavigator<AnalyticsStackParamList>();
const ReviewStack = createNativeStackNavigator<ReviewStackParamList>();
const SettingsStack = createNativeStackNavigator<SettingsStackParamList>();

function HomeTabStack() {
  return (
    <HomeStack.Navigator screenOptions={STACK_SCREEN_OPTIONS}>
      <HomeStack.Screen name="HomeTab" component={HomeRoute} />
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

function ReviewTabStack() {
  return (
    <ReviewStack.Navigator screenOptions={STACK_SCREEN_OPTIONS}>
      <ReviewStack.Screen name="ReviewTab" component={ReviewTab} />
    </ReviewStack.Navigator>
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
 * (a word, a settings page) must not be swiped away, and a live review session owns the screen
 * outright.
 *
 * Applied through `navigation.setOptions` rather than declared in the navigator's `screenOptions`
 * alone, because the declarative gate did not hold: on the moto g35 a horizontal drag on the pushed
 * word detail moved the pager to the next tab. `setOptions` is the documented way to change a mounted
 * screen's options, and the state it is computed from is the one the bar already hides itself with —
 * which is demonstrably live.
 */
function SwipeGate({
  state,
  descriptors,
  sessionActive,
}: Pick<MaterialTopTabBarProps, "state" | "descriptors"> & {
  sessionActive: boolean;
}) {
  const focused = state.routes[state.index];
  const depth = focused?.state?.index ?? 0;
  // The focused tab screen's own navigation prop: `setOptions` is what changes a mounted screen's
  // options, and it targets exactly this route.
  const setOptions = descriptors[focused?.key ?? ""]?.navigation.setOptions;
  useEffect(() => {
    setOptions?.({swipeEnabled: !sessionActive && depth === 0});
  }, [setOptions, sessionActive, depth]);
  return null;
}

export function AppShell() {
  const {colors, mode} = useTheme();
  const systemScheme = useColorScheme();
  const navigationRef = useNavigationContainerRef<RootTabParamList>();
  const [sessionActive, setSessionActive] = useState(
    reviewStore.sessionActive(),
  );
  // The pager's own first guess at its size, so the bar's indicator is already at the right tab on
  // the first frame — the navigator measures itself a frame later. Only the width matters; the bar's
  // height comes from its own constant and the scene takes the rest.
  const {width} = useWindowDimensions();

  useEffect(() => {
    const subscription = reviewStore.onSessionActive(setSessionActive);
    return () => subscription.remove();
  }, []);

  // The settings page an outside launch asked for: matched against the pages Settings itself
  // knows, a match selects the Settings tab and pushes that page — onto the Settings stack's own
  // state when it is already mounted, so a page the reader left open is stacked above rather than
  // swapped for the new one — and every request, matched or not, is consumed exactly once.
  useEffect(() => {
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
  }, [navigationRef]);

  const navigationTheme = useMemo<NavigationTheme>(() => {
    const dark =
      mode === "Dark" || (mode === "System" && systemScheme === "dark");
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
        regular: {fontFamily: "sans-serif", fontWeight: "400"},
        medium: {fontFamily: "sans-serif", fontWeight: "500"},
        bold: {fontFamily: "sans-serif", fontWeight: "600"},
        heavy: {fontFamily: "sans-serif", fontWeight: "600"},
      },
    };
  }, [colors, mode, systemScheme]);

  const session = useMemo(() => ({sessionActive}), [sessionActive]);
  // A tap on a tab slides the pager to it (that is what `animationEnabled` means to the library);
  // with reduced motion on it lands on the spot, as every other animation in the app does.
  const reducedMotion = useReducedMotion();

  return (
    <SessionContext.Provider value={session}>
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
                sessionActive={sessionActive}
              />
              <BottomBar {...props} />
            </>
          )}
          // `route.state` is the tab's own stack (the pager keeps one per tab); the typed route for a
          // tab whose params are `undefined` does not declare it, which is why the route here is
          // `NavigationRoute`.
          screenOptions={({route}: {route: NavigationRoute<ParamListBase, string>}) => ({
            headerShown: false,
            lazy: true,
            animationEnabled: !reducedMotion,
            swipeEnabled: !sessionActive && (route.state?.index ?? 0) === 0,
          })}
        >
          <Tab.Screen name="Home" component={HomeTabStack} />
          <Tab.Screen name="Analytics" component={AnalyticsTabStack} />
          <Tab.Screen name="Review" component={ReviewTabStack} />
          <Tab.Screen name="Settings" component={SettingsTabStack} />
        </Tab.Navigator>
      </NavigationContainer>
    </SessionContext.Provider>
  );
}
