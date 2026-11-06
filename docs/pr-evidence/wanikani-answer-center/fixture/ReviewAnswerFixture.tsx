/**
 * Gaten — evidence fixture for the WaniKani review answer field. NOT part of the app.
 *
 * Mounts the *production* `WaniKaniReview` with one synthetic session, inside the provider tree
 * `App.js` uses, so the placeholder and the typed answer are drawn by the real screen and the real
 * `AnswerInput`. Nothing else is faked: the entry is this fixture's own state and every callback the
 * screen takes is a no-op, so no store, no review queue, no account and no request are reachable
 * from here.
 *
 * The two-button bar at the bottom is harness chrome. It switches the single subject between its
 * meaning prompt and its reading prompt: a real queue asks one part at a time, so it would only ask
 * the reading once the meaning has been answered, and this fixture has no store to answer through.
 */
import React, {useMemo, useState} from "react";
import {Pressable, StyleSheet, Text, View} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import {SafeAreaProvider} from "react-native-safe-area-context";
import {KeyboardProvider} from "react-native-keyboard-controller";
import {ThemeProvider, useTheme} from "../src/rn/ui/theme";
import {WaniKaniReview} from "../src/rn/ui/review/WaniKaniReview";
import type {WaniKaniReviewItem} from "../src/rn/native/GatenWaniKani";
import type {WaniKaniSession} from "../src/rn/ui/review/review-store";

/** The one subject: the vocabulary the report's screenshot shows, so the prompt matches it. */
const SUBJECT: WaniKaniReviewItem = {
  subjectId: 9001,
  type: "vocabulary",
  characters: "待合",
  meanings: ["waiting place", "rendezvous", "meeting place"],
  primaryMeaning: "waiting place",
  readings: ["まちあい"],
  primaryReading: "まちあい",
  level: 14,
  meaningMnemonic: "",
  readingMnemonic: "",
  contextSentences: [],
};

type Part = "meaning" | "reading";

/**
 * A session stopped on one prompt of that subject, holding whatever the fixture has typed.
 *
 * Built as a local and returned rather than handed back as a literal: `reviewedEntry` is not a field
 * of `WaniKaniSession` on this base — the review-retry branch adds it — and an extra property is
 * assignable on a local object, where the same property in a returned literal would be rejected.
 */
function sessionFor(part: Part, entry: string): WaniKaniSession {
  const session = {
    items: [SUBJECT],
    order: [{index: 0, part}],
    cursor: 0,
    totalParts: 1,
    doneParts: 0,
    index: 0,
    part,
    entry,
    entryRefused: false,
    incorrectMeaning: 0,
    incorrectReading: 0,
    wrong: [{meaning: 0, reading: 0}],
    feedback: null,
    retrying: false,
    phase: "answering" as const,
    failure: null,
    authRejected: false,
    // The review-retry branch's own field, carried so this fixture keeps building once that branch
    // merges; today's screen does not read it.
    reviewedEntry: "",
  };
  return session;
}

function Stage() {
  const {colors} = useTheme();
  const [part, setPart] = useState<Part>("meaning");
  const [entry, setEntry] = useState("");
  const session = useMemo(() => sessionFor(part, entry), [part, entry]);

  return (
    <View style={styles.root}>
      <WaniKaniReview
        session={session}
        busy={false}
        onBack={() => {}}
        onEntry={setEntry}
        onCheck={() => {}}
        onContinue={() => {}}
        onRetrySubmit={() => {}}
      />
      <View
        style={[
          styles.harness,
          {backgroundColor: colors.surface, borderTopColor: colors.outlineVariant},
        ]}
      >
        {(["meaning", "reading"] as Part[]).map((key) => (
          <Pressable
            key={key}
            accessibilityRole="button"
            onPress={() => {
              setPart(key);
              setEntry("");
            }}
            style={styles.harnessButton}
          >
            <Text
              style={[
                styles.harnessLabel,
                {color: part === key ? colors.accent : colors.onSurfaceVariant},
              ]}
            >
              {`fixture: ${key}`}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

export function ReviewAnswerFixture() {
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <KeyboardProvider statusBarTranslucent navigationBarTranslucent>
          <ThemeProvider>
            <Stage />
          </ThemeProvider>
        </KeyboardProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  harness: {flexDirection: "row", borderTopWidth: 1},
  harnessButton: {flex: 1, minHeight: 44, alignItems: "center", justifyContent: "center"},
  harnessLabel: {fontSize: 12, fontWeight: "700"},
});
