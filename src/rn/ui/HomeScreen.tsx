/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Home: the way into a search, the state of the deck Gaten reviews, and the state of screen
 * lookup — the Compose HomeDashboard, ported.
 *
 * Everything here is read, never kept: the deck, the counts, screen access and the bubble switch
 * are re-read whenever the screen is focused or the app is resumed, because AnkiDroid schedules on
 * its own and both platform grants can change while Gaten is away. No read raises a dialog —
 * asking is left to the review screen.
 */
import React, {useCallback, useMemo, useRef, useState} from "react";
import {
  AppState,
  FlatList,
  KeyboardAvoidingView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from "react-native";
import {SafeAreaView} from "react-native-safe-area-context";
import {
  CompositeScreenProps,
  useFocusEffect,
} from "@react-navigation/native";
import type {
  MaterialTopTabScreenProps,
} from "@react-navigation/material-top-tabs";
import type {NativeStackScreenProps} from "@react-navigation/native-stack";
import {useTheme} from "./theme";
import {
  MAX_CONTENT_WIDTH,
  TWO_COLUMN_WIDTH,
  shape,
  spacing,
  typeScale,
} from "./tokens";
import {PressableScale} from "./PressableScale";
import {Button, Card, Icon, IconBadge, IconButton} from "./components";
import {
  SEARCH_LIST_CONTENT,
  SearchField,
  SearchRowItem,
  SearchRowSeparator,
  searchRows,
  type SearchRow,
} from "./search/searchParts";
import * as store from "./search/searchStore";
import {SearchNotice} from "./search/SearchScreen";
import {
  freshSettingsInstance,
  type HomeStackParamList,
  type RootTabParamList,
  type SettingsPageName,
} from "./navigation";
import * as Anki from "../native/GatenAnki";
import * as AppInfo from "../native/GatenAppInfo";
import * as Haptics from "../native/GatenHaptics";
import * as Trigger from "../native/GatenTrigger";

type HomeScreenProps = Partial<
  CompositeScreenProps<
    NativeStackScreenProps<HomeStackParamList, "HomeTab">,
    MaterialTopTabScreenProps<RootTabParamList>
  >
> & {
  /**
   * Where the screen's own actions go. The Home stack wires all three (its `Word` route, the Review
   * tab's root, and a settings page on Home's own stack); a caller that passes none leaves the
   * screen's own navigation as the fallback.
   */
  onOpenWord?: (entryId: number) => void;
  onOpenReview?: () => void;
  onOpenSettingsPage?: (page: SettingsPageName) => void;
};

/** What Home can say about the deck Gaten exports into (HomeState.kt's DeckStatus). */
type DeckStatus =
  | {kind: "notRead"}
  | {kind: "ankiDroidMissing"}
  | {kind: "permissionNeeded"}
  | {kind: "deckMissing"; configuredName: string}
  | {
      kind: "counts";
      deckName: string;
      newCount: number;
      learnCount: number;
      reviewCount: number;
    }
  | {kind: "unreadable"; failure: string};

/** Why a read failed, in the reader's words — strings_navigation.xml's dash_anki_failure_* texts. */
const FAILURE_LABELS: Record<string, string> = {
  NOT_INSTALLED: "AnkiDroid is not installed",
  PERMISSION: "access has not been granted",
  NOT_SET_UP: "AnkiDroid has no collection folder yet",
  NOT_FOUND: "the deck is no longer there",
  PROVIDER: "AnkiDroid could not open the collection",
  MALFORMED: "AnkiDroid answered something Gaten could not read",
};

/**
 * The configured deck's own counts, or the reason there are none (HomeState.kt's readDeckStatus):
 * a read that never asks for anything, matched by name exactly first and case-folded second.
 */
async function readDeckStatus(): Promise<DeckStatus> {
  const status = await Anki.status();
  if (status === "notInstalled") {
    return {kind: "ankiDroidMissing"};
  }
  if (status === "needsPermission") {
    return {kind: "permissionNeeded"};
  }
  const configuredName = await Anki.getConfiguredDeckName();
  try {
    const decks = await Anki.decks();
    const folded = configuredName.toLowerCase();
    const deck =
      decks.find((candidate) => candidate.name === configuredName) ??
      decks.find((candidate) => candidate.name.toLowerCase() === folded);
    if (!deck) {
      return {kind: "deckMissing", configuredName};
    }
    return {
      kind: "counts",
      deckName: deck.name,
      newCount: deck.newCount,
      learnCount: deck.learnCount,
      reviewCount: deck.reviewCount,
    };
  } catch (error) {
    // The bridge rejects with the Kotlin failure's own name as the code: "ANKI_PERMISSION" etc.
    let code = "";
    if (
      error &&
      typeof error === "object" &&
      "code" in error &&
      typeof error.code === "string"
    ) {
      code = error.code.replace(/^ANKI_/, "");
    }
    return {
      kind: "unreadable",
      failure: FAILURE_LABELS[code] ?? FAILURE_LABELS.PROVIDER,
    };
  }
}

export function HomeScreen({
  navigation,
  onOpenWord,
  onOpenReview,
  onOpenSettingsPage,
}: HomeScreenProps) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();

  const [screenAccess, setScreenAccess] = useState(false);
  const [floatingButton, setFloatingButton] = useState(false);
  const [deckStatus, setDeckStatus] = useState<DeckStatus>({kind: "notRead"});
  const [reads, setReads] = useState(0);

  const refresh = useCallback(() => {
    setReads((current) => current + 1);
  }, []);

  useFocusEffect(
    useCallback(() => {
      let live = true;
      const readAll = async () => {
        // The two platform reads first, then the deck: one failed read must not mask the others.
        try {
          const [access, bubble] = await Promise.all([
            AppInfo.isScreenAccessEnabled(),
            Trigger.getFloatingButtonEnabled(),
          ]);
          if (live) {
            setScreenAccess(access);
            setFloatingButton(bubble);
          }
        } catch {
          // The next resume reads again; a missed platform state is shown stale, not wrong.
        }
        const status = await readDeckStatus();
        if (live) {
          setDeckStatus(status);
        }
      };
      readAll();
      // The Activity's ON_RESUME: the app may come back from the background with all of this
      // changed, and a navigation focus alone does not fire for that.
      const subscription = AppState.addEventListener("change", (state) => {
        if (state === "active") {
          readAll();
        }
      });
      return () => {
        live = false;
        subscription.remove();
      };
    }, [reads]),
  );

  // The inline search: the query, the rows and the reading position all belong to the store — the
  // same one the Search tab reads — so a pushed word, a return from another tab and a remount all
  // come back to exactly what the reader left.
  const searchState = store.useSearchState();
  const rows = useMemo(
    () => searchRows(searchState),
    [searchState.hits, searchState.hitsQuery, searchState.query],
  );
  // The field shows exactly what the reader typed; what counts as a search is the store's own
  // question — whitespace-free and normalized — so a stray space never hides the dashboard over an
  // empty screen the store does not consider a search at all.
  const searching = store.normalizeQuery(searchState.query).length > 0;
  const searchInput = useRef<TextInput>(null);
  const list = useRef<FlatList<SearchRow>>(null);
  const restored = useRef(false);

  /**
   * Puts the list back where the reader left it, once, on the first layout that has content: the
   * position belongs to the store (`searchScroll`), and a remount — a tab switch that unmounts Home,
   * a return from elsewhere — would otherwise start at the top while the store still remembers the
   * place. A push on this stack keeps the screen mounted, so nothing here runs on a Back: coming back
   * from a word never sends the list to the top.
   */
  const restoreScroll = () => {
    if (restored.current) {
      return;
    }
    restored.current = true;
    if (store.searchScroll.offset > 0) {
      list.current?.scrollToOffset({offset: store.searchScroll.offset, animated: false});
    }
  };

  const openRow = useCallback(
    (entryId: number) => {
      // The reader has chosen a word: the field gives the keyboard back and the word opens over
      // this screen, on Home's own stack.
      searchInput.current?.blur();
      if (onOpenWord != null) {
        onOpenWord(entryId);
        return;
      }
      navigation?.navigate("Word", {entryId});
    },
    [navigation, onOpenWord],
  );

  /**
   * What a search with no rows to show says about itself: the same states, wording and actions the
   * Search tab shows, drawn from the same components so the two surfaces cannot drift.
   */
  const searchStateNotice =
    searchState.status === "unsupported" ? (
      <SearchNotice
        icon="translate"
        title="Search in Japanese"
        body="Type kanji or kana, or a reading in romaji such as neko or tokyo."
      />
    ) : searchState.status === "failed" ? (
      <SearchNotice
        icon="error"
        title="The dictionary could not be read"
        body={searchState.error ?? "The dictionary could not be opened."}
        action={{label: "Try again", onPress: store.retrySearch}}
      />
    ) : searchState.status === "searching" ? (
      <SearchNotice spin title="Searching the dictionary…" />
    ) : searchState.status === "results" ? (
      <SearchNotice
        icon="search"
        title={`No matches for ${searchState.query}`}
        body="Check the spelling, or try a shorter word. A dictionary form matches best — 食べる rather than 食べた."
      />
    ) : null;

  const openReview = () => {
    if (onOpenReview != null) {
      onOpenReview();
      return;
    }
    navigation?.navigate("Review", {screen: "ReviewTab", pop: true});
  };

  const openSettings = (page: SettingsPageName) => {
    if (onOpenSettingsPage != null) {
      onOpenSettingsPage(page);
      return;
    }
    navigation?.navigate("Settings", {
      screen: "SettingsPage",
      params: {page, instance: freshSettingsInstance()},
    });
  };

  const toggleFloating = (enabled: boolean) => {
    // Only a change the reader made ticks; the page's own navigation never does.
    Haptics.play("tick");
    setFloatingButton(enabled);
    // The native side stores the flag and sends the floating-button-changed broadcast itself.
    Trigger.setFloatingButtonEnabled(enabled);
  };

  return (
    <SafeAreaView style={styles.root} edges={["top", "left", "right"]}>
      {/*
        The field takes the cursor only when the reader taps it: nothing here focuses it — not the
        mount, not a tab switch, not a return from a word.

        `KeyboardAvoidingView` is the same answer the search surface uses: the window is edge-to-edge
        and not resized for the IME, so the inset has to become layout. `padding` (not `height`)
        because the list below is the flex scroller — the padding shrinks its box without a second
        measurement.
      */}
      <KeyboardAvoidingView behavior="padding" style={styles.root}>
        <SearchField
          inputRef={searchInput}
          query={searchState.query}
          queryLimited={searchState.queryLimited}
          onChangeQuery={store.onQueryChange}
          onSearch={() => {
            searchInput.current?.blur();
            store.searchNow();
          }}
        />
        {/*
          One scroller on this screen, and it is the virtualized list: while the query is empty the
          dashboard is its header, and while it is not, the results are its rows. That is also why
          the cards are a header rather than a second scroll surface — a `FlatList` inside a plain
          `ScrollView` breaks windowing, which React Native warns about in as many words.
        */}
        <FlatList<SearchRow>
          ref={list}
          data={rows}
          keyExtractor={(row) => row.key}
          renderItem={({item}) => <SearchRowItem row={item} onOpen={openRow} />}
          ItemSeparatorComponent={SearchRowSeparator}
          contentContainerStyle={SEARCH_LIST_CONTENT}
          ListHeaderComponent={
            searching ? null : (
              <View style={styles.column}>
                {/* The product, said once: what Gaten is, above everything it does. */}
                <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
                  Gaten
                </Text>
                <Text
                  style={[
                    typeScale.bodyMedium,
                    styles.subtitle,
                    {color: colors.onSurfaceVariant},
                  ]}
                >
                  Your Japanese reading toolkit
                </Text>

                <View
                  style={[
                    styles.pair,
                    {flexDirection: width >= TWO_COLUMN_WIDTH ? "row" : "column"},
                  ]}
                >
                  <View style={styles.pairFirst}>
                    <ReviewCard
                      status={deckStatus}
                      onOpenReview={openReview}
                      onOpenCardSettings={() => openSettings("anki")}
                      onRefresh={refresh}
                    />
                  </View>
                  <View style={styles.pairSecond}>
                    <LookupCard
                      screenAccess={screenAccess}
                      floatingButton={floatingButton}
                      onToggleFloating={toggleFloating}
                      onSetup={() => openSettings("lookup")}
                    />
                  </View>
                </View>
              </View>
            )
          }
          ListEmptyComponent={searching ? searchStateNotice : null}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          onContentSizeChange={restoreScroll}
          onScroll={(event) => {
            // The reading position is the store's, so it belongs to the query rather than to this
            // list instance, and a remount comes back to the place the reader left.
            store.searchScroll.offset = event.nativeEvent.contentOffset.y;
          }}
          scrollEventThrottle={16}
        />
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

/**
 * The deck Gaten reviews: the configured deck's own counts, or the honest sentence for the state it
 * is in instead. The accent's strength carries the state — a filled badge and accent figures when
 * there is work, the quiet container when there is not — so the card never has to raise its voice.
 */
function ReviewCard({
  status,
  onOpenReview,
  onOpenCardSettings,
  onRefresh,
}: {
  status: DeckStatus;
  onOpenReview: () => void;
  onOpenCardSettings: () => void;
  onRefresh: () => void;
}) {
  const {colors} = useTheme();
  const counts = status.kind === "counts";
  const due = counts
    ? status.newCount + status.learnCount + status.reviewCount
    : 0;
  const working = due > 0;
  const figure = working ? colors.primary : colors.onSurface;
  return (
    <Card style={styles.card}>
      <View style={styles.cardHead}>
        <IconBadge
          name="library_books"
          background={working ? colors.primary : colors.primaryContainer}
          color={working ? colors.onPrimary : colors.onPrimaryContainer}
        />
        <View style={styles.cardHeadText}>
          <Text
            numberOfLines={1}
            ellipsizeMode="tail"
            style={[typeScale.titleMedium, {color: colors.onSurface}]}
          >
            {counts ? status.deckName : "Review"}
          </Text>
          <Text
            numberOfLines={1}
            ellipsizeMode="tail"
            style={[typeScale.bodySmall, styles.cardHeadLine, {color: colors.onSurfaceVariant}]}
          >
            {counts
              ? working
                ? `${due} ${due === 1 ? "card" : "cards"} due`
                : "Nothing due right now"
              : "Anki cards"}
          </Text>
        </View>
        <IconButton
          name="refresh"
          accessibilityLabel="Check AnkiDroid again"
          onPress={onRefresh}
          color={colors.onSurfaceVariant}
        />
      </View>

      {counts ? (
        <View style={styles.countsRow}>
          <CountTile count={status.newCount} label="New" figure={figure} />
          <CountTile count={status.learnCount} label="Learning" figure={figure} />
          <CountTile count={status.reviewCount} label="Review" figure={figure} />
        </View>
      ) : (
        <Text
          style={[
            typeScale.bodyMedium,
            styles.sentence,
            {color: colors.onSurfaceVariant},
          ]}
        >
          {statusSentence(status)}
        </Text>
      )}

      <View style={styles.actionsRow}>
        {status.kind === "ankiDroidMissing" ? (
          <Button label="Card settings" onPress={onOpenCardSettings} />
        ) : null}
        {status.kind === "permissionNeeded" ? (
          <Button label="Review" onPress={onOpenReview} />
        ) : null}
        {status.kind === "counts" ? (
          <>
            <Button label="Review" onPress={onOpenReview} />
            <Button variant="text" label="Card settings" onPress={onOpenCardSettings} />
          </>
        ) : null}
        {status.kind === "deckMissing" ? (
          <Button label="Choose deck" onPress={onOpenCardSettings} />
        ) : null}
        {status.kind === "unreadable" ? (
          <Button label="Try again" onPress={onRefresh} />
        ) : null}
      </View>
    </Card>
  );
}

/** One honest sentence for every state that has no counts. */
function statusSentence(status: DeckStatus): string {
  switch (status.kind) {
    case "notRead":
      return "Checking AnkiDroid…";
    case "ankiDroidMissing":
      return "AnkiDroid is not installed.";
    case "permissionNeeded":
      return "AnkiDroid has not given Gaten access yet.";
    case "deckMissing":
      return `No deck named “${status.configuredName}” in AnkiDroid.`;
    case "unreadable":
      return `Could not read AnkiDroid: ${status.failure}`;
    case "counts":
      return "";
  }
}

/** One of the deck's own counts: the figure first, its name quiet underneath. */
function CountTile({
  count,
  label,
  figure,
}: {
  count: number;
  label: string;
  figure: string;
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.countTile}>
      <Text style={[typeScale.headlineSmall, {color: figure}]}>{count}</Text>
      <Text
        style={[
          typeScale.labelSmall,
          styles.countLabel,
          {color: colors.onSurfaceVariant},
        ]}
      >
        {label}
      </Text>
    </View>
  );
}

/**
 * Screen lookup's state, and the one switch on this screen. It is the quiet card of the three — a
 * flat surface rather than a raised one — because it is where the reader sets something up, not
 * where the day's work is done.
 */
function LookupCard({
  screenAccess,
  floatingButton,
  onToggleFloating,
  onSetup,
}: {
  screenAccess: boolean;
  floatingButton: boolean;
  onToggleFloating: (enabled: boolean) => void;
  onSetup: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.quietCard,
        {backgroundColor: colors.surfaceVariant, borderRadius: shape.medium},
      ]}
    >
      <View style={styles.cardHead}>
        <IconBadge
          name="touch_app"
          background={screenAccess ? colors.primary : colors.errorContainer}
          color={screenAccess ? colors.onPrimary : colors.onErrorContainer}
        />
        <View style={styles.cardHeadText}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
            Screen lookup
          </Text>
          <Text
            style={[
              typeScale.bodySmall,
              styles.cardHeadLine,
              {color: colors.onSurfaceVariant},
            ]}
          >
            {screenAccess
              ? "On — circle any Japanese text on screen."
              : "Off — Gaten reads nothing from the screen."}
          </Text>
        </View>
      </View>

      <View style={styles.floatingRow}>
        <View style={styles.statusColumn}>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>
            Floating button
          </Text>
          <Text
            style={[
              typeScale.bodySmall,
              styles.cardHeadLine,
              {color: colors.onSurfaceVariant},
            ]}
          >
            {screenAccess
              ? "Tap the bubble on screen to look up."
              : "The bubble appears once screen access is on."}
          </Text>
        </View>
        <Switch
          value={floatingButton}
          onValueChange={onToggleFloating}
          accessibilityLabel="Floating button"
          trackColor={{
            false: colors.surfaceContainerHighest,
            true: colors.primary,
          }}
          thumbColor={floatingButton ? colors.onPrimary : colors.onSurfaceVariant}
        />
      </View>

      <PressableScale
        accessibilityRole="button"
        accessibilityLabel="Set up screen lookup"
        onPress={onSetup}
        style={styles.setupButton}
      >
        <Text style={[typeScale.labelLarge, {color: colors.primary}]}>
          Set up screen lookup
        </Text>
        <Icon name="keyboard_arrow_right" size={20} color={colors.primary} />
      </PressableScale>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  column: {
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
    // The horizontal gutters and the bottom of the page belong to the list's own content style, so
    // the dashboard header sits in exactly the column the rows do.
    paddingTop: spacing.xxl,
  },
  subtitle: {
    marginTop: spacing.xs,
  },
  pair: {
    marginTop: spacing.xl,
    gap: spacing.l,
  },
  pairFirst: {
    flex: 1,
  },
  pairSecond: {
    flex: 1,
  },
  card: {
    padding: spacing.xl,
  },
  quietCard: {
    padding: spacing.xl,
  },
  cardHead: {
    flexDirection: "row",
    alignItems: "center",
  },
  cardHeadText: {
    flex: 1,
    marginStart: spacing.m,
  },
  cardHeadLine: {
    marginTop: 2,
  },
  countsRow: {
    flexDirection: "row",
    marginTop: spacing.l,
    gap: spacing.l,
  },
  countTile: {
    flex: 1,
  },
  countLabel: {
    marginTop: 2,
  },
  sentence: {
    marginTop: spacing.m,
  },
  actionsRow: {
    flexDirection: "row",
    alignItems: "center",
    // Wraps rather than clips: at a large font scale the second action drops to its own line.
    flexWrap: "wrap",
    marginTop: spacing.l,
    gap: spacing.s,
  },
  statusColumn: {
    flex: 1,
  },
  floatingRow: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: spacing.l,
  },
  setupButton: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "flex-start",
    // A control's height, and no negative margin: the Kotlin button is indented by its own padding,
    // not pulled out to the card's text edge.
    minHeight: 48,
    paddingHorizontal: spacing.m,
    marginTop: spacing.xs,
    gap: spacing.xs,
  },
});
