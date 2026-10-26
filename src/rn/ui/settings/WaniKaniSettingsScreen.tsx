/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani page: the connection's own state, the daily lesson limit, the token, and the way
 * back out — all committed by the one Save at the foot of the page.
 *
 * The screen holds no token of its own beyond the field being typed — a reload clears it instead
 * of a secret lingering, and it is emptied again the moment a token is accepted. Save writes the
 * lesson limit, which is local, and then checks and stores a token if the field holds one; a blank
 * field is never sent to WaniKani, so a limit can be saved while disconnected and an untouched
 * token is never replaced or erased. Connecting verifies before it stores, so a token on the device
 * is always one WaniKani has accepted; a rejected or unreachable token is only reported, and the
 * device is left exactly as it was. Disconnect is the reverse, takes the cache with it, and stays
 * the one explicit action that ends a connection.
 *
 * Nothing here reports a success in prose: a save that worked shows through the button's own
 * Saving… → Save round-trip, and only failures take ink under the field they belong to.
 */
import React, {useEffect, useState} from "react";
import {KeyboardAvoidingView, ScrollView, Text, View, useWindowDimensions} from "react-native";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {Button, Icon, IconButton} from "../components";
import {reviewStore} from "../review/review-store";
import {resetWaniKaniStats} from "../analytics/useWaniKaniStats";
import {FieldLabel, SettingsGroup, TextField} from "./settings-components";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";

/** WaniKani's own level count; an account granted all of them needs no note about what is covered. */
const WANIKANI_LEVELS = 60;

/** Where a token is created, opened in the browser from this screen. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

/**
 * The daily lesson goal's range, mirroring `WaniKaniLearningPreferences` so the field can say what is
 * wrong before a round trip. The device still decides: the bridge and the preference layer refuse
 * anything outside it.
 */
const MIN_LESSON_LIMIT = 1;
const MAX_LESSON_LIMIT = 20;

/**
 * What connecting buys the reader: the one line the plainly disconnected state carries. It says
 * only what Gaten reads — WaniKani's own pages and the account's study progress — because nothing
 * here is written back to the account.
 */
const CONNECTION_HINT = "Connect to read WaniKani's kanji pages and your study progress.";

/** The sentence each failure string shows in "Could not connect to WaniKani: …". */
export const FAILURE_LABELS: Record<WaniKaniFailure, string> = {
  OFFLINE: "no connection",
  TIMEOUT: "the request timed out",
  RATE_LIMITED: "WaniKani is limiting requests just now",
  SERVER: "WaniKani reported a server error",
  TOO_LARGE: "the answer was too large to keep",
  MALFORMED: "WaniKani sent something Gaten could not read",
  STORAGE: "this device could not save the answer",
};

/** The bridge rejects a failed request as `WK_<FAILURE>`; recover the failure it named. */
function failureFromError(error: unknown): WaniKaniFailure | null {
  if (typeof error !== "object" || error == null || !("code" in error)) return null;
  const code = error.code;
  if (typeof code !== "string" || !code.startsWith("WK_")) return null;
  const named = code.slice("WK_".length);
  return (Object.keys(FAILURE_LABELS) as WaniKaniFailure[]).find(
    (candidate) => candidate === named
  ) ?? null;
}

export function WaniKaniSettingsScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const horizontalInset = width < 360 ? 16 : 22;
  const [token, setToken] = useState("");
  const [connected, setConnected] = useState<boolean | null>(null);
  const [changingToken, setChangingToken] = useState(false);
  const [busy, setBusy] = useState(false);
  // The last answer from WaniKani, and the outcome of a disconnect: null while nothing was
  // disconnected, true when the token and the whole cache went, false when part of it did not.
  const [answer, setAnswer] = useState<GatenWaniKani.WaniKaniConnectionResult | null>(null);
  /** A rejected or unreachable request, kept apart from the two answers above. */
  const [failure, setFailure] = useState<WaniKaniFailure | null>(null);
  const [cleared, setCleared] = useState<boolean | null>(null);
  const [dailyLimitInput, setDailyLimitInput] = useState("");
  const [preferenceBusy, setPreferenceBusy] = useState(false);
  // Only a failure is kept: a save that worked shows through the button's own Saving… → Save
  // round-trip, so the page never grows a success line it would then have to clear.
  const [preferenceError, setPreferenceError] = useState<string | null>(null);
  const [preferenceLoading, setPreferenceLoading] = useState(true);
  const [preferenceLoadFailed, setPreferenceLoadFailed] = useState(false);
  const [preferenceRefresh, setPreferenceRefresh] = useState(0);

  // The token's state is storage, not memory: read it on every arrival.
  useEffect(() => {
    let cancelled = false;
    void GatenWaniKani.isConfigured().then((v) => {
      if (!cancelled) setConnected(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setPreferenceLoading(true);
    setPreferenceLoadFailed(false);
    void GatenWaniKani.getLearningPreferences()
      .then((preferences) => {
        if (cancelled) return;
        setDailyLimitInput(String(preferences.dailyLimit));
        setPreferenceError(null);
      })
      .catch(() => {
        if (!cancelled) {
          setPreferenceLoadFailed(true);
          setPreferenceError("Could not load this setting.");
        }
      })
      .finally(() => {
        if (!cancelled) setPreferenceLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [preferenceRefresh]);

  /**
   * The page's one action. It commits both drafts in the order they can safely be committed: the
   * lesson limit is local and goes first, then a non-blank token is checked with WaniKani and
   * stored. A blank field is never sent, so this saves a limit (or re-saves it) while disconnected
   * and can never erase or replace a token the reader did not touch; Disconnect stays the separate,
   * explicit way out. The limit is only written once the stored value was actually read, so an
   * unknown current value is never written over with an assumption.
   */
  const save = () => {
    const value = dailyLimitInput.trim();
    const limit = Number(value);
    const tokenDraft = token.trim();
    const writeLimit = !preferenceLoading && !preferenceLoadFailed;
    if (
      writeLimit &&
      (!/^\d+$/.test(value) || limit < MIN_LESSON_LIMIT || limit > MAX_LESSON_LIMIT)
    ) {
      setPreferenceError("Use 1–20.");
      return;
    }
    if (writeLimit) {
      setPreferenceBusy(true);
      setPreferenceError(null);
      void GatenWaniKani.setDailyLessonLimit(limit)
        .then((preferences) => {
          setDailyLimitInput(String(preferences.dailyLimit));
        })
        .catch(() => setPreferenceError("Could not save the daily lesson setting. Try again."))
        .finally(() => setPreferenceBusy(false));
    }
    if (tokenDraft !== "") {
      connect(tokenDraft);
    }
  };

  // Two kinds of answer, kept apart because each belongs beside the thing it is about: a rejected or
  // unreachable token is a problem with the field (reported in the Token section, in the error's own
  // ink), while connected — or disconnected, and what was cleared — is a fact about the connection,
  // shown with the state it just changed. Every sentence the screen had is still here, whole.
  let tokenError: string | null = null;
  let connectionNote: string | null = null;
  if (answer == null) {
    if (failure != null) {
      tokenError = `Could not connect to WaniKani: ${FAILURE_LABELS[failure]}`;
    } else if (cleared != null) {
      connectionNote =
        cleared === true
          ? "Disconnected. The saved token and every cached page were removed."
          : "Some of the saved token or cached pages could not be removed. Disconnect again.";
    }
  } else if (answer.status === "connected") {
    connectionNote =
      answer.maxLevelGranted < WANIKANI_LEVELS
        ? `Connected. Your account covers WaniKani's first ${answer.maxLevelGranted} levels; kanji beyond them are shown as outside your subscription.`
        : "Connected. Gaten can read WaniKani's reference content.";
  } else {
    tokenError = "WaniKani rejected that token. Check it was copied in full.";
  }

  /** Checks the typed token with WaniKani and stores it; the field is emptied once it is accepted. */
  const connect = (tokenDraft: string) => {
    setBusy(true);
    setCleared(null);
    setFailure(null);
    void GatenWaniKani.connect(tokenDraft)
      .then((result) => {
        if (result.status === "connected") {
          setToken("");
          setChangingToken(false);
          reviewStore.resetWaniKaniAccount();
        }
        // The token that just connected may belong to another account, so anything the dashboard is
        // still holding — numbers, and a rate-limit wait they came with — is dropped before it can
        // be read again. Nothing of the old account is shown for the new one.
        if (result.status === "connected") resetWaniKaniStats();
        // Read back rather than assumed: a storage failure clears what was there, so an answer
        // that is not Connected can leave the device without a token.
        return GatenWaniKani.isConfigured().then((configured) => {
          setConnected(configured);
          setAnswer(result);
          if (!configured) reviewStore.resetWaniKaniAccount();
        });
      })
      .catch((error) => {
        setFailure(failureFromError(error));
        setAnswer(null);
      })
      .finally(() => setBusy(false));
  };

  const recheck = () => {
    setBusy(true);
    setCleared(null);
    setFailure(null);
    void GatenWaniKani.reconnect()
      .then((result) => {
        return GatenWaniKani.isConfigured().then((configured) => {
          setConnected(configured);
          setAnswer(result);
          if (!configured) reviewStore.resetWaniKaniAccount();
        });
      })
      .catch((error) => {
        setFailure(failureFromError(error));
        setAnswer(null);
      })
      .finally(() => setBusy(false));
  };

  const disconnect = () => {
    setBusy(true);
    setAnswer(null);
    void GatenWaniKani.disconnect()
      .then((fullyCleared) => {
        // False when the token, or part of the cache, could not be cleared: the screen says so,
        // and what is left is read back rather than assumed gone.
        setCleared(fullyCleared);
        return GatenWaniKani.isConfigured().then((configured) => {
          setConnected(configured);
          setToken("");
          // No token, no account: the dashboard must not keep showing the numbers of the account
          // that was just disconnected, nor wait out a rate limit it left behind. Checked against
          // the read-back rather than the disconnect's own answer, so a partial clear that did take
          // the token clears the state too, and one that did not leaves it alone.
          if (!configured) {
            resetWaniKaniStats();
            reviewStore.resetWaniKaniAccount();
          }
        });
      })
      .catch(() => {
        setCleared(false);
      })
      .finally(() => setBusy(false));
  };

  const isKnownConnected = connected === true;
  const showTokenField = !isKnownConnected || changingToken;
  // One busy state for the whole form: both drafts are committed by the one Save below, so the
  // button and both fields say "something is in flight" the same way.
  const saving = busy || preferenceBusy;
  const connectionState =
    connected == null
      ? "Checking connection…"
      : !isKnownConnected
        ? "Not connected"
        : answer != null && answer.status === "connected" && answer.maxLevelGranted < WANIKANI_LEVELS
          ? `Connected · Levels 1–${answer.maxLevelGranted}`
          : "Connected";
  // The one line under the state: the reader's own last answer when there is one, otherwise — while
  // the account is plainly not connected — the sentence that says what connecting buys them.
  // Nothing is shown while a check is in flight, or when a working connection has nothing new to
  // report. A partial disconnect is the one thing here drawn in the error's ink, because it asks
  // for the action that finishes the job.
  const connectionDetail = connectionNote ?? (connected === false ? CONNECTION_HINT : null);
  const connectionDetailFailed = connectionNote != null && cleared === false;
  const openTokenPage = () => {
    void GatenPlatform.openUrl(WANIKANI_TOKEN_URL);
  };

  // The keyboard is turned into layout here, as it is on the search and Anki screens: from
  // targetSdk 35 on the window is displayed edge-to-edge and is not resized for the IME, so the
  // token field would otherwise sit under it. `padding` because the scroller below is `flex: 1`,
  // and no `keyboardVerticalOffset` because this view is the scene's top child.
  return (
    <KeyboardAvoidingView behavior="padding" style={{flex: 1, backgroundColor: colors.background}}>
      <View
        style={{
          // 64 dp below the status bar, as the Kotlin's TopAppBar is — see SettingsPageScaffold.
          minHeight: 64 + insets.top,
          flexDirection: "row",
          alignItems: "center",
          paddingTop: insets.top,
        }}
      >
        {/* The Kotlin bar's navigation icon: this page is pushed over the tab, so the bar it hides
            leaves the arrow as the way back. */}
        <IconButton name="arrow_back" accessibilityLabel="Back" onPress={onClose} color={colors.onSurface} />
        <Text style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1}]} numberOfLines={1}>
          WaniKani
        </Text>
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        // With the keyboard up, a tap on Save, Disconnect or a row is the row's own.
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{
          maxWidth: 560,
          width: "100%",
          alignSelf: "center",
          paddingLeft: insets.left + horizontalInset,
          paddingRight: insets.right + horizontalInset,
          paddingTop: spacing.s,
          paddingBottom: spacing.xl + insets.bottom,
        }}
      >
        <SettingsGroup title="Connection" style={{marginTop: spacing.s}}>
          {/* The state as a compact composition rather than a line on its own: the glyph carries the
              state, the name carries the service, and one sentence under them says what connecting
              buys the reader. A missing token is a setup state, not a failure, so the disconnected
              ink stays neutral and only a working connection takes the success tone. */}
          <View style={{paddingVertical: spacing.m, flexDirection: "row", alignItems: "center"}}>
            <Icon
              name={isKnownConnected ? "check_circle" : "cloud_off"}
              size={24}
              color={isKnownConnected ? colors.success : colors.iconSecondary}
            />
            <View style={{flex: 1, minWidth: 0, marginStart: spacing.m}}>
              <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>WaniKani</Text>
              <Text
                style={[
                  typeScale.labelMedium,
                  {
                    color: isKnownConnected ? colors.success : colors.onSurfaceVariant,
                    marginTop: spacing.xs,
                  },
                ]}
              >
                {connectionState}
              </Text>
              {connectionDetail != null && (
                <Text
                  accessibilityLiveRegion="polite"
                  style={[
                    typeScale.bodySmall,
                    {
                      color: connectionDetailFailed ? colors.error : colors.onSurfaceVariant,
                      marginTop: spacing.xs,
                    },
                  ]}
                >
                  {connectionDetail}
                </Text>
              )}
            </View>
          </View>
        </SettingsGroup>

        <SettingsGroup title="Lessons" style={{marginTop: spacing.l}}>
          <View style={{paddingVertical: spacing.m}}>
            <FieldLabel label="New per day" />
            <TextField
              value={dailyLimitInput}
              onChangeText={(value) => {
                setDailyLimitInput(value);
                setPreferenceError(null);
              }}
              keyboardType="number-pad"
              editable={!saving && !preferenceLoading && !preferenceLoadFailed}
              accessibilityLabel="New per day. A whole number from 1 to 20."
            />
            {preferenceLoadFailed && (
              <View style={{alignSelf: "flex-start", marginTop: spacing.xs}}>
                <Button
                  variant="text"
                  label="Retry"
                  disabled={preferenceLoading}
                  onPress={() => setPreferenceRefresh((value) => value + 1)}
                />
              </View>
            )}
            {preferenceError != null && (
              <Text
                accessibilityLiveRegion="polite"
                style={[typeScale.bodySmall, {color: colors.error, marginTop: spacing.xs}]}
              >
                {preferenceError}
              </Text>
            )}
          </View>
        </SettingsGroup>

        {isKnownConnected && !changingToken && (
          <View style={{alignSelf: "flex-start", marginTop: spacing.l}}>
            <Button variant="text" label="Change token" disabled={saving} onPress={() => setChangingToken(true)} />
          </View>
        )}

        {showTokenField && (
          <SettingsGroup title="Token" style={{marginTop: spacing.l}}>
            <View style={{paddingVertical: spacing.m}}>
              {/* The section heading is this field's own label, so the box does not repeat it; the
                  accessible name keeps the field named for a screen reader, where the placeholder
                  disappears the moment anything is typed. */}
              <TextField
                value={token}
                onChangeText={setToken}
                placeholder="Paste your token"
                secureTextEntry
                autoCapitalize="none"
                autoCorrect={false}
                editable={!saving}
                accessibilityLabel="Personal access token"
              />
              <View style={{alignSelf: "flex-start", marginTop: spacing.xs}}>
                <Button variant="text" label="Get a token" onPress={openTokenPage} />
              </View>
              {tokenError != null && (
                <Text
                  accessibilityLiveRegion="polite"
                  style={[typeScale.bodySmall, {color: colors.error, marginTop: spacing.xs}]}
                >
                  {tokenError}
                </Text>
              )}
            </View>
          </SettingsGroup>
        )}

        {(isKnownConnected || cleared === false) && (
          <View style={{marginTop: spacing.xl, flexDirection: "row", flexWrap: "wrap", gap: spacing.s}}>
            <Button
              variant="text"
              label="Recheck"
              disabled={saving || !isKnownConnected}
              onPress={recheck}
            />
            <Button variant="text" label="Disconnect" disabled={saving} onPress={disconnect} />
          </View>
        )}

        {/* The page's one action, at the end of the form it commits: the lesson limit always, and a
            token whenever the field holds one. Disconnect is deliberately not this button — it stays
            the explicit text action above, so a blank field can never end a connection. */}
        <View style={{marginTop: spacing.xl}}>
          <Button
            label={preferenceBusy ? "Saving…" : busy ? "Checking…" : "Save"}
            disabled={saving || preferenceLoading || (preferenceLoadFailed && token.trim().length === 0)}
            fullWidth
            onPress={save}
          />
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
