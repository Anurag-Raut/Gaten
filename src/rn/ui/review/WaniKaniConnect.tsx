/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani connection, reached from Home's WaniKani card when no token is stored — or when
 * WaniKani refused the one that is.
 *
 * It is the same account flow the settings page owns, in the review workflow's own clothes: the
 * token is checked against WaniKani before it is stored, nothing is kept when it does not work, and a
 * token that works goes straight into the queue rather than back to a screen the reader has to tap
 * again. The field is emptied the moment a token is accepted, so a secret is not left in a text field
 * behind the review.
 *
 * There is no second way in: the prototype's button simply simulated a connection, and this one
 * authenticates, so the reader's own token is what the flow asks for.
 */
import React, {useEffect, useRef, useState} from "react";
import {Keyboard, ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {KeyboardAvoidingView} from "react-native-keyboard-controller";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenPlatform from "../../native/GatenPlatform";
import {FieldLabel, TextField} from "../settings/settings-components";
import {FAILURE_LABELS} from "../settings/WaniKaniSettingsScreen";
import {contentInsetFor, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {
  ActionFooter,
  BrandTile,
  FilledAction,
  StateBlock,
  StateChip,
  SubHeader,
} from "./review-ui";
import type {WaniKaniConnectOutcome as Outcome} from "./review-store";

/** Where a token is created, opened in the browser from this screen. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

export function WaniKaniConnect({
  rejected,
  busy,
  onBack,
  onConnect,
}: {
  /** True when WaniKani refused the token that is stored, rather than none being stored at all. */
  rejected: boolean;
  busy: boolean;
  onBack: () => void;
  onConnect: (token: string) => Promise<Outcome>;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = contentInsetFor(width);
  const [token, setToken] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);

  const connect = () => {
    setConnecting(true);
    setMessage(null);
    void onConnect(token.trim())
      .then((outcome: Outcome) => {
        switch (outcome.status) {
          case "connected":
            // The store has already forgotten the old account and read the new one — see
            // `connectWaniKani` — so there is nothing left to drop here; only the secret goes.
            setToken("");
            return;
          case "invalidToken":
            setMessage("WaniKani rejected that token. Check it was copied in full.");
            return;
          case "failed":
            setMessage(`Could not connect to WaniKani: ${FAILURE_LABELS[outcome.failure]}`);
            return;
          case "bridge":
            setMessage(`Could not connect to WaniKani: ${outcome.message}`);
        }
      })
      .finally(() => setConnecting(false));
  };

  const pending = busy || connecting;
  const scrollRef = useRef<ScrollView>(null);

  // The action bar is pinned under the form, so with the keyboard up the focused field can sit
  // partly behind it: once the keyboard has settled, the form is scrolled to its end so the field
  // and the link under it stay above the action, which the KeyboardAvoidingView has lifted into
  // view. A no-op when the form already fits.
  useEffect(() => {
    const sub = Keyboard.addListener("keyboardDidShow", () => {
      scrollRef.current?.scrollToEnd({animated: true});
    });
    return () => sub.remove();
  }, []);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      {/* The whole screen avoids the keyboard through the keyboard-controller library, as the
          review and lesson screens do: with the keyboard up the token field and the connect action
          both rise and stay visible above it, so the button is never hidden behind the IME. The IME
          action key is the same connect. */}
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <SubHeader title="WaniKani" onBack={onBack} />
        <ScrollView
          ref={scrollRef}
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={[
            styles.body,
            {paddingLeft: insets.left + contentInset, paddingRight: insets.right + contentInset},
          ]}
          keyboardShouldPersistTaps="handled"
        >
          <StateBlock
            mark={<BrandTile brand="wanikani" size={56} radius={15} />}
            title="Connect WaniKani"
            body={
              rejected
                ? "WaniKani refused the saved token. Connect again with a new one to review kanji and vocabulary here."
                : "Connect your account to review kanji and vocabulary here."
            }
          >
            <StateChip label={rejected ? "Token rejected" : "Not connected"} />
          </StateBlock>

          <View style={styles.field}>
            <FieldLabel label="Personal access token" />
            <TextField
              value={token}
              onChangeText={setToken}
              placeholder="Paste your token"
              secureTextEntry
              autoCapitalize="none"
              autoCorrect={false}
              editable={!pending}
              returnKeyType="go"
              accessibilityLabel="WaniKani personal access token"
              onSubmitEditing={() => {
                if (token.trim() !== "" && !pending) connect();
              }}
            />
            <Text
              accessibilityRole="link"
              onPress={() => {
                void GatenPlatform.openUrl(WANIKANI_TOKEN_URL).catch(() => {});
              }}
              style={[typeScale.bodySmall, styles.link, {color: colors.accent}]}
            >
              Create a token on wanikani.com
            </Text>
            {message != null && (
              <Text
                accessibilityLiveRegion="polite"
                style={[typeScale.bodySmall, styles.message, {color: colors.error}]}
              >
                {message}
              </Text>
            )}
          </View>
        </ScrollView>
        <ActionFooter bottomInset={insets.bottom}>
          <FilledAction
            label="Connect WaniKani"
            disabled={pending || token.trim() === ""}
            onPress={connect}
          />
        </ActionFooter>
      </KeyboardAvoidingView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {paddingTop: 48, paddingBottom: spacing.xl, flexGrow: 1},
  field: {marginTop: 40},
  link: {marginTop: spacing.m},
  message: {marginTop: spacing.m},
});
