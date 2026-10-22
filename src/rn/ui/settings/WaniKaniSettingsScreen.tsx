/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani token: what connecting does, the token itself, and the way back out.
 *
 * The screen holds no token of its own beyond the field being typed — a reload clears it instead
 * of a secret lingering, and it is emptied again the moment a token is accepted. Connecting
 * verifies before it stores, so a token on the device is always one WaniKani has accepted; a
 * rejected or unreachable token is only reported, and the device is left exactly as it was.
 * Disconnect is the reverse and takes the cache with it.
 */
import React, {useEffect, useState} from "react";
import {KeyboardAvoidingView, ScrollView, Text, View} from "react-native";
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {Button, IconButton} from "../components";
import {reviewStore} from "../review/review-store";
import {resetWaniKaniStats} from "../analytics/useWaniKaniStats";
import {FieldLabel, SettingsTile, TextField} from "./settings-components";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";

/** WaniKani's own level count; an account granted all of them needs no note about what is covered. */
const WANIKANI_LEVELS = 60;

/** Where a token is created, opened in the browser from this screen. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

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

/** One section of the page, on its own card, with the page's ground between the cards. */
function WaniKaniCard({children}: {children: React.ReactNode}) {
  return (
    <SettingsTile>
      <View style={{padding: spacing.xl}}>{children}</View>
    </SettingsTile>
  );
}

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
  const [learnedToday, setLearnedToday] = useState<number | null>(null);
  const [preferenceBusy, setPreferenceBusy] = useState(false);
  const [preferenceMessage, setPreferenceMessage] = useState<string | null>(null);
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
        setDailyLimitInput(preferences.dailyLimit?.toString() ?? "");
        setLearnedToday(preferences.learnedToday);
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

  const saveDailyLimit = () => {
    const value = dailyLimitInput.trim();
    const limit = value === "" ? null : Number(value);
    if (limit !== null && (!/^\d+$/.test(value) || !Number.isInteger(limit) || limit < 1 || limit > 1000)) {
      setPreferenceError("Use 1–1000, or leave blank.");
      setPreferenceMessage(null);
      return;
    }
    setPreferenceBusy(true);
    setPreferenceError(null);
    setPreferenceMessage(null);
    void GatenWaniKani.setDailyLessonLimit(limit)
      .then((preferences) => {
        setDailyLimitInput(preferences.dailyLimit?.toString() ?? "");
        setLearnedToday(preferences.learnedToday);
        setPreferenceMessage("Saved.");
      })
      .catch(() => setPreferenceError("Could not save the daily lesson setting. Try again."))
      .finally(() => setPreferenceBusy(false));
  };

  let message: string | null = null;
  if (answer == null) {
    if (failure != null) {
      message = `Could not connect to WaniKani: ${FAILURE_LABELS[failure]}`;
    } else if (cleared != null) {
      message =
        cleared === true
          ? "Disconnected. The saved token and every cached page were removed."
          : "Some of the saved token or cached pages could not be removed. Disconnect again.";
    }
  } else if (answer.status === "connected") {
    message =
      answer.maxLevelGranted < WANIKANI_LEVELS
        ? `Connected. Your account covers WaniKani's first ${answer.maxLevelGranted} levels; kanji beyond them are shown as outside your subscription.`
        : "Connected. Gaten can read WaniKani's reference content.";
  } else {
    message = "WaniKani rejected that token. Check it was copied in full.";
  }

  const connect = () => {
    setBusy(true);
    setCleared(null);
    setFailure(null);
    void GatenWaniKani.connect(token)
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
        // With the keyboard up, a tap on Connect, Disconnect or a row is the row's own.
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{
          maxWidth: 840,
          width: "100%",
          alignSelf: "center",
          paddingHorizontal: spacing.l,
          paddingTop: spacing.xl,
          paddingBottom: spacing.l + insets.bottom,
        }}
      >
        <WaniKaniCard>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>
            {connected == null
              ? "Checking connection…"
              : isKnownConnected
                ? answer?.status === "connected" && answer.maxLevelGranted < WANIKANI_LEVELS
                  ? `Connected · Levels 1–${answer.maxLevelGranted}`
                  : "Connected"
                : "Not connected"}
          </Text>
        </WaniKaniCard>

        <View style={{marginTop: spacing.l}}>
          <WaniKaniCard>
            <FieldLabel label="New cards per day" />
            <TextField
              value={dailyLimitInput}
              onChangeText={(value) => {
                setDailyLimitInput(value);
                setPreferenceError(null);
                setPreferenceMessage(null);
              }}
              placeholder="Unlimited"
              keyboardType="number-pad"
              editable={!preferenceBusy && !preferenceLoading && !preferenceLoadFailed}
              accessibilityLabel="New cards per day. Leave blank for unlimited."
            />
            <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
              Leave blank for unlimited.
            </Text>
            {learnedToday != null && (
              <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>
                Learned today: {learnedToday} · This device
              </Text>
            )}
            <View style={{alignSelf: "flex-start", marginTop: spacing.xs}}>
              <Button
                variant="text"
                label={preferenceBusy ? "Saving…" : "Save"}
                disabled={preferenceBusy || preferenceLoading || preferenceLoadFailed}
                onPress={saveDailyLimit}
              />
            </View>
            {preferenceLoadFailed && (
              <View style={{alignSelf: "flex-start"}}>
                <Button
                  variant="text"
                  label="Retry"
                  disabled={preferenceLoading}
                  onPress={() => setPreferenceRefresh((value) => value + 1)}
                />
              </View>
            )}
            {(preferenceError != null || preferenceMessage != null) && (
              <Text
                accessibilityLiveRegion="polite"
                style={[typeScale.bodySmall, {color: preferenceError ? colors.error : colors.onSurfaceVariant, marginTop: spacing.xs}]}
              >
                {preferenceError ?? preferenceMessage}
              </Text>
            )}
          </WaniKaniCard>
        </View>

        {isKnownConnected && !changingToken && (
          <View style={{marginTop: spacing.s}}>
            <Button variant="text" label="Change token" disabled={busy} onPress={() => setChangingToken(true)} />
          </View>
        )}

        {showTokenField && <View style={{marginTop: spacing.l}}>
          <WaniKaniCard>
            <FieldLabel label="Personal access token" />
            <TextField
              value={token}
              onChangeText={setToken}
              placeholder="Paste your token"
              secureTextEntry
              autoCapitalize="none"
              autoCorrect={false}
              editable={!busy}
            />
            <View style={{alignSelf: "flex-start", marginTop: spacing.xs}}>
              <Button variant="text" label="Get a token" onPress={openTokenPage} />
            </View>
          </WaniKaniCard>
        </View>}

        {showTokenField && <View style={{marginTop: spacing.l}}>
          <Button
            label={busy ? "Checking…" : "Connect"}
            disabled={busy || token.trim().length === 0}
            fullWidth
            onPress={connect}
          />
        </View>}

        {(isKnownConnected || cleared === false) && (
          <View style={{marginTop: spacing.s, flexDirection: "row", gap: spacing.s}}>
            <View style={{flex: 1}}>
              <Button
                variant="text"
                label="Recheck connection"
                disabled={busy || !isKnownConnected}
                onPress={recheck}
              />
            </View>
            <View style={{flex: 1}}>
              <Button variant="text" label="Disconnect" disabled={busy} onPress={disconnect} />
            </View>
          </View>
        )}

        {message != null && (
          <Text style={[typeScale.bodyMedium, {color: colors.onSurface, marginTop: spacing.l}]}>
            {message}
          </Text>
        )}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
