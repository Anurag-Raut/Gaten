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
import React, {useState} from "react";
import {
  KeyboardAvoidingView,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenPlatform from "../../native/GatenPlatform";
import {resetWaniKaniStats} from "../analytics/useWaniKaniStats";
import {FieldLabel, TextField} from "../settings/settings-components";
import {FAILURE_LABELS} from "../settings/WaniKaniSettingsScreen";
import {spacing, typeScale} from "../tokens";
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
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
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
            // A different account may be behind this token, so anything the dashboard still holds is
            // dropped before it can be shown for the new one. The screen itself is replaced by the
            // queue the store has already entered.
            resetWaniKaniStats();
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

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        <View style={{paddingHorizontal: contentInset}}>
          <SubHeader title="WaniKani" onBack={onBack} />
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={[styles.body, {paddingHorizontal: contentInset}]}
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
