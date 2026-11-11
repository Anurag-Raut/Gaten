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
import {
  AccessibilityInfo,
  findNodeHandle,
  Keyboard,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from "react-native";
import {KeyboardAvoidingView} from "react-native-keyboard-controller";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenPlatform from "../../native/GatenPlatform";
import {FieldLabel, TextField} from "../settings/settings-components";
import {FAILURE_LABELS} from "../settings/WaniKaniSettingsScreen";
import {CONTENT_COLUMN, contentInsetFor, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {ScreenHeader} from "../ScreenHeader";
import {
  ActionFooter,
  BrandTile,
  FilledAction,
  StateBlock,
  StateChip,
} from "./review-ui";
import {ProviderSetupHelp} from "./provider-setup-help";
import type {WaniKaniConnectOutcome as Outcome} from "./review-store";

/** Where a token is created, opened in the browser from this screen. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

export function WaniKaniConnect({
  rejected,
  busy,
  embedded = false,
  active = true,
  onBack,
  onConnect,
}: {
  /** True when WaniKani refused the token that is stored, rather than none being stored at all. */
  rejected: boolean;
  busy: boolean;
  /**
   * True when the screen already provides the frame — the onboarding flow's WaniKani page, which
   * recedes its scene to a small anchor above this form and owns the way back. The form then drops
   * its own header and its top safe-area padding, and moves the setup help down beside the token
   * field, so the shell's back row is the only way back and nothing is said twice.
   */
  embedded?: boolean;
  /**
   * False while the embedded form is mounted but hidden behind the flow's own screen: it then gives
   * the keyboard and the reader's accessibility focus back, and takes accessibility focus on its own
   * token field again when it is shown. The full-screen callers never change it, so nothing here
   * runs for them.
   */
  active?: boolean;
  onBack: () => void;
  onConnect: (token: string) => Promise<Outcome>;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const [footerHeight, setFooterHeight] = useState(0);
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
  const tokenField = useRef<TextInput>(null);

  // The one thing the setup help's "Open token form" means on this screen.
  const openTokenForm = () => {
    scrollRef.current?.scrollToEnd({animated: true});
    tokenField.current?.focus();
  };

  // Once the keyboard settles, scroll the token field and its link into view.
  useEffect(() => {
    const sub = Keyboard.addListener("keyboardDidShow", () => {
      scrollRef.current?.scrollToEnd({animated: true});
    });
    return () => sub.remove();
  }, []);

  // The embedded form is mounted once and then hidden, so it must not hold the keyboard or the
  // reader's focus while it sits behind the flow's screen. Shown, it takes accessibility focus onto
  // its own token field — accessibility focus only, so a sighted reader's keyboard stays down until
  // they tap the field.
  const wasActive = useRef(false);
  useEffect(() => {
    if (!embedded || wasActive.current === active) return;
    wasActive.current = active;
    if (active) {
      const node = findNodeHandle(tokenField.current);
      if (node != null) AccessibilityInfo.setAccessibilityFocus(node);
      return;
    }
    Keyboard.dismiss();
  }, [embedded, active]);

  return (
    <View
      style={[
        styles.screen,
        // Embedded, this form is a panel over the flow's own content, so it takes the flow column's
        // centred width cap: its body and its absolute footer share that one column. The full-screen
        // callers keep the full-width frame they have always had.
        embedded && CONTENT_COLUMN,
        {backgroundColor: colors.background, paddingTop: embedded ? 0 : insets.top},
      ]}
    >
      {/* The KeyboardAvoidingView (the project's keyboard library, as the review and lesson screens
          use) wraps only the header and the form, so the token field lifts clear of the keyboard. */}
      <KeyboardAvoidingView style={styles.screen} behavior="padding">
        {/* The reader who has not connected yet is the one who needs the steps, so the setup help
            stands here as well as on the hub. Its "Open token form" is this screen's own field:
            the help closes, the field comes into view and the caret goes into it, and whatever the
            reader had already typed stays where it is. Embedded, the host owns the header and the
            help moves down beside the token field instead. */}
        {!embedded && (
          <ScreenHeader
            title="WaniKani"
            onBack={onBack}
            trailing={<ProviderSetupHelp provider="wanikani" onOpenTokenForm={openTokenForm} />}
          />
        )}
        <ScrollView
          ref={scrollRef}
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          style={styles.screen}
          contentContainerStyle={[
            styles.body,
            {paddingTop: embedded ? spacing.l : 48},
            {paddingBottom: footerHeight + spacing.xl},
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
              inputRef={tokenField}
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
            <View style={styles.linkRow}>
              <Text
                accessibilityRole="link"
                onPress={() => {
                  void GatenPlatform.openUrl(WANIKANI_TOKEN_URL).catch(() => {});
                }}
                style={[typeScale.bodySmall, styles.link, {color: colors.accent, flex: 1}]}
              >
                Create a token on wanikani.com
              </Text>
              {embedded && <ProviderSetupHelp provider="wanikani" onOpenTokenForm={openTokenForm} />}
            </View>
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
      </KeyboardAvoidingView>
      {/* The floating action stays beneath the keyboard; Done also connects. */}
      <ActionFooter bottomInset={insets.bottom} onHeightChange={setFooterHeight}>
        <FilledAction
          label="Connect WaniKani"
          disabled={pending || token.trim() === ""}
          onPress={connect}
        />
      </ActionFooter>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {paddingTop: 48, paddingBottom: spacing.xl, flexGrow: 1},
  field: {marginTop: 40},
  linkRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  link: {marginTop: spacing.m},
  message: {marginTop: spacing.m},
});
