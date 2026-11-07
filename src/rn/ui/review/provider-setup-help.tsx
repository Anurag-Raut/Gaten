/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * How each provider is set up and operated, behind the hubs' quiet question mark: the provider's own
 * mark, the ordered steps with the control each one names, and the one hand-off the reader may need —
 * AnkiDroid's download page, AnkiDroid itself, or WaniKani's token page.
 *
 * The same help stands on the hub, on the screen that opens when a provider is not connected, and on
 * the AnkiDroid blocked screen, because the reader who has not connected yet is the one who needs the
 * steps. The words are the app's own states and controls — "AnkiDroid is not installed" and "Download
 * AnkiDroid", "AnkiDroid access is needed" and "Allow AnkiDroid access", the deck picker's "Clear for
 * now" — so nothing here names a control the app does not draw. The two counting dialogs beside the
 * metrics stay where they are: they explain what a figure counts, this explains what to press.
 *
 * Opening the dialog reads nothing but AnkiDroid's own installation state (`status`, a passive read):
 * no permission is requested, no queue is refreshed and nothing is submitted.
 */
import React, {useEffect, useState} from "react";
import {StyleSheet, Text, View} from "react-native";
import {Button} from "../components";
import {HelpAction} from "../settings/settings-components";
import {spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenPlatform from "../../native/GatenPlatform";
import {downloadAnkiDroid} from "../anki/anki-actions";
import {BrandTile} from "./review-ui";

/** Where a token is created; the same page the connect screen's own link opens. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

type Provider = "anki" | "wanikani";

interface SetupStep {
  /** What the step does, in the reader's words. */
  title: string;
  /** The controls to press and what to expect, named as the app names them. */
  instruction: string;
}

const ANKI_STEPS: SetupStep[] = [
  {
    title: "Install AnkiDroid",
    instruction:
      "If Gaten shows ‘AnkiDroid is not installed’, choose ‘Download AnkiDroid’.",
  },
  {
    title: "Set up your collection",
    instruction:
      "Open AnkiDroid and finish its setup. Create or import a deck there, then return to Gaten. " +
      "If Gaten shows ‘AnkiDroid is not set up’, choose ‘Open AnkiDroid’.",
  },
  {
    title: "Allow Gaten access",
    instruction:
      "When Gaten shows ‘AnkiDroid access is needed’, choose ‘Allow AnkiDroid access’ and approve " +
      "the Android permission request.",
  },
  {
    title: "Choose a deck",
    instruction:
      "On ‘Choose a deck’, open a deck with cards due. ‘Clear for now’ means that deck has no work " +
      "available.",
  },
  {
    title: "Choose which decks appear",
    instruction:
      "Open Settings → Anki cards → Study decks, choose your decks, then press ‘Save’. ‘Include all " +
      "decks’ includes every deck. The Export → Deck setting controls where saved words go; it does " +
      "not choose your study decks.",
  },
];

const WANIKANI_STEPS: SetupStep[] = [
  {
    title: "Create a personal access token",
    instruction:
      "On ‘Connect WaniKani’, choose ‘Create a token on wanikani.com’. Sign in on WaniKani’s website " +
      "and create a token.",
  },
  {
    title: "Allow lessons and reviews",
    instruction:
      "Keep read access enabled. Allow ‘assignments:start’ for lessons and ‘reviews:create’ for " +
      "reviews. Copy the complete token.",
  },
  {
    title: "Connect in Gaten",
    instruction:
      "Return to Gaten, paste it into ‘Personal access token’, then choose ‘Connect WaniKani’.",
  },
  {
    title: "Choose your study",
    instruction:
      "Open ‘Reviews’ for due reviews or ‘Lessons’ for new lessons. ‘Refresh’ checks availability " +
      "again. Studying here updates your WaniKani account.",
  },
  {
    title: "Change your connection or lesson limit",
    instruction:
      "Open Settings → WaniKani. Use ‘Get a token’ when connecting there, paste your token, and press " +
      "‘Save’. Under Lessons, ‘New per day’ accepts 1–20. Use ‘Change token’ to replace a connection " +
      "or ‘Disconnect’ to remove it.",
  },
];

/** The one failure a reader is likely to meet, and where the app explains the rest. */
const WANIKANI_RECOVERY =
  "If you see ‘WaniKani rejected that token. Check it was copied in full.’, copy the complete token " +
  "again. Connection failures explain whether you need internet or should wait before trying again.";

/** The ordered steps, each numbered in the accent and read as title, then instruction. */
function StepList({steps}: {steps: SetupStep[]}) {
  const {colors} = useTheme();
  return (
    <View style={styles.steps}>
      {steps.map((step, index) => (
        <View key={step.title} style={styles.step}>
          <Text style={[styles.stepNumber, {color: colors.accent}]}>{index + 1}</Text>
          <View style={styles.stepBody}>
            <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{step.title}</Text>
            <Text style={[styles.stepText, {color: colors.onSurfaceVariant}]}>
              {step.instruction}
            </Text>
          </View>
        </View>
      ))}
    </View>
  );
}

/**
 * One page the help hands the reader off to: accent ink with an underline and link semantics, at the
 * same 48 dp target every control keeps, never the URL as its visible label. A hand-off that does not
 * land says so under the link rather than closing over a tap that did nothing.
 */
function HelpLink({label, url}: {label: string; url: string}) {
  const {colors} = useTheme();
  const [failed, setFailed] = useState(false);
  const open = () => {
    setFailed(false);
    void GatenPlatform.openUrl(url)
      .then((opened) => {
        if (!opened) setFailed(true);
      })
      .catch(() => setFailed(true));
  };
  return (
    <View style={styles.linkBlock}>
      <Text
        accessibilityRole="link"
        onPress={open}
        style={[styles.link, {color: colors.accent}]}
      >
        {label}
      </Text>
      {failed && (
        <Text
          accessibilityLiveRegion="polite"
          style={[styles.handoffError, {color: colors.error}]}
        >
          Couldn't open the page. Try again.
        </Text>
      )}
    </View>
  );
}

/** AnkiDroid's steps and its two hand-offs: the store page always, the app itself once it is here. */
function AnkiSetup() {
  const {colors} = useTheme();
  const [installed, setInstalled] = useState(false);
  const [openFailed, setOpenFailed] = useState(false);

  // AnkiDroid's installation state, read once per opening. A failed read hides the app hand-off
  // rather than claiming AnkiDroid is here; the download stays, which is the step that fixes it.
  useEffect(() => {
    let cancelled = false;
    void GatenAnki.status()
      .then((status) => {
        if (!cancelled) setInstalled(status !== "notInstalled");
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <View>
      <View style={styles.mark}>
        <BrandTile brand="anki" />
      </View>
      <StepList steps={ANKI_STEPS} />
      <View style={styles.actions}>
        <Button
          variant="tonal"
          fullWidth
          label="Download AnkiDroid"
          onPress={() => {
            void downloadAnkiDroid();
          }}
        />
        {installed && (
          <Button
            variant="outlined"
            fullWidth
            label="Open AnkiDroid"
            onPress={() => {
              setOpenFailed(false);
              void GatenAnki.openAnkiDroid()
                .then((opened) => {
                  if (!opened) setOpenFailed(true);
                })
                .catch(() => setOpenFailed(true));
            }}
          />
        )}
      </View>
      {openFailed && (
        <Text
          accessibilityLiveRegion="polite"
          style={[styles.handoffError, {color: colors.error}]}
        >
          Couldn't open AnkiDroid. Try again.
        </Text>
      )}
    </View>
  );
}

/** WaniKani's steps, the token page, and the one recovery the connection's own words promise. */
function WaniKaniSetup() {
  const {colors} = useTheme();
  return (
    <View>
      <View style={styles.mark}>
        <BrandTile brand="wanikani" />
      </View>
      <StepList steps={WANIKANI_STEPS} />
      <HelpLink label="Open WaniKani token settings" url={WANIKANI_TOKEN_URL} />
      <Text style={[styles.recovery, {color: colors.onSurfaceVariant}]}>{WANIKANI_RECOVERY}</Text>
    </View>
  );
}

/**
 * The hub headers' help: a question mark that opens the provider's setup steps. The trigger is the
 * same bare 48 dp help target the settings pages carry, and the dialog is the app's own `HelpDialog`
 * in its rolling form, so the reader scrolls the steps and closes with the pinned action.
 */
export function ProviderSetupHelp({provider}: {provider: Provider}) {
  const anki = provider === "anki";
  return (
    <HelpAction
      title={anki ? "Set up AnkiDroid" : "Set up WaniKani"}
      accessibilityLabel={anki ? "AnkiDroid setup help" : "WaniKani setup help"}
    >
      {anki ? <AnkiSetup /> : <WaniKaniSetup />}
    </HelpAction>
  );
}

const styles = StyleSheet.create({
  mark: {marginBottom: spacing.l, alignSelf: "flex-start"},
  // The steps, one 12 dp step apart, each numbered in the accent beside its own column.
  steps: {gap: spacing.m},
  step: {flexDirection: "row", gap: spacing.m},
  stepNumber: {...typeScale.titleMedium, width: 18},
  stepBody: {flex: 1, minWidth: 0},
  stepText: {...typeScale.bodyMedium, marginTop: spacing.xs},
  actions: {marginTop: spacing.l, gap: spacing.s},
  linkBlock: {marginTop: spacing.l},
  // The link's own 48 dp target: a 20 dp line inside 14 dp of padding, underlined so the colour is
  // never the only signal that it leads somewhere.
  link: {
    ...typeScale.bodyMedium,
    paddingVertical: 14,
    minHeight: 48,
    alignSelf: "flex-start",
    textDecorationLine: "underline",
  },
  handoffError: {...typeScale.bodySmall, marginTop: spacing.s},
  recovery: {...typeScale.bodySmall, marginTop: spacing.l},
});
