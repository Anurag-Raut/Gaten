/**
 * Gaten — evidence fixture root (not part of the app; see README.md).
 *
 * Mounts the production [WaniKaniReview] over the production [reviewStore]. Only the WaniKani
 * submission bridge is synthetic (`submitStub.ts`), so the states photographed here are the store's
 * own: `submitting` set by `submitWaniKani`, the accepted hand-off through `advanceWaniKani` to the
 * next prompt, and the failed hand-off to `submitFailed`. No store member is replaced and no phase
 * is written by this file.
 *
 * The session is synthetic — two named fixture subjects and fixture-only meanings that are never
 * sent — because a real review cannot be submitted without moving a real account's SRS schedule. The
 * fixture installs it in the store's retained-review slot, so `startReviews()` publishes that
 * session and reads no queue. The fixture bar shows the synthetic bridge target and pacing in every
 * capture.
 */
import React, {useEffect, useRef, useState} from "react";
import {Pressable, StyleSheet, Text, View} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import {SafeAreaProvider} from "react-native-safe-area-context";
import {KeyboardProvider} from "react-native-keyboard-controller";
import {ThemeProvider} from "../../../../src/rn/ui/theme";
import {WaniKaniReview} from "../../../../src/rn/ui/review/WaniKaniReview";
import {
  reviewStore,
  type WaniKaniSession,
} from "../../../../src/rn/ui/review/review-store";
import type {WaniKaniReviewItem} from "../../../../src/rn/native/GatenWaniKani";
import {
  configureOutcomes,
  submitStubState,
  type SubmitOutcome,
} from "./submitStub";

/**
 * Two synthetic kanji-shaped subjects, one prompt each. The ids and meanings are fixture labels that
 * are never sent, so no still can be read as a real review of real content.
 */
const FIRST: WaniKaniReviewItem = {
  subjectId: 9001,
  type: "kanji",
  characters: "仮",
  meanings: ["fixture one"],
  primaryMeaning: "fixture one",
  readings: [],
  primaryReading: null,
  level: 1,
  meaningMnemonic: "Synthetic fixture subject; not WaniKani content.",
  readingMnemonic: "",
  contextSentences: [],
};

const SECOND: WaniKaniReviewItem = {
  subjectId: 9002,
  type: "kanji",
  characters: "語",
  meanings: ["fixture two"],
  primaryMeaning: "fixture two",
  readings: [],
  primaryReading: null,
  level: 1,
  meaningMnemonic: "Synthetic fixture subject; not WaniKani content.",
  readingMnemonic: "",
  contextSentences: [],
};

/**
 * The production session shape plus the retry branch's upcoming `reviewedEntry` field. The local
 * intersection keeps this fixture valid against both the current and the forward interface, and the
 * value is empty because no wrong answer is part of this capture.
 */
type FixtureSession = WaniKaniSession & {reviewedEntry: string};

/** The production session shape, at the first of two meaning prompts. */
function makeSession(): FixtureSession {
  return {
    items: [FIRST, SECOND],
    order: [
      {index: 0, part: "meaning"},
      {index: 1, part: "meaning"},
    ],
    cursor: 0,
    totalParts: 2,
    doneParts: 0,
    index: 0,
    part: "meaning",
    entry: "",
    reviewedEntry: "",
    entryRefused: false,
    incorrectMeaning: 0,
    incorrectReading: 0,
    wrong: [
      {meaning: 0, reading: 0},
      {meaning: 0, reading: 0},
    ],
    feedback: null,
    retrying: false,
    phase: "answering",
    failure: null,
    authRejected: false,
  };
}

/**
 * The retained-session slot `startReviews()` publishes. It is private in TypeScript only; this is
 * the same door the app's own store uses from its hub. No other member is reached.
 */
type StoreInternals = {pausedReview: WaniKaniSession | null};
const storeInternals = reviewStore as unknown as StoreInternals;

const ACCEPT_THEN_FAIL: SubmitOutcome[] = ["accepted", "failed"];
const FAIL_FIRST: SubmitOutcome[] = ["failed"];

export function ReviewFixture() {
  const [state, setState] = useState(() => reviewStore.getState());
  // A new session at the same prompt would keep the screen's own revealed/scroll state; a remount
  // is the fixture's reset, not an app transition.
  const [generation, setGeneration] = useState(0);
  const seeded = useRef(false);

  useEffect(() => {
    const subscription = reviewStore.subscribe(setState);
    return () => subscription.remove();
  }, []);

  const install = (outcomes: SubmitOutcome[]) => {
    configureOutcomes(outcomes);
    storeInternals.pausedReview = makeSession();
    reviewStore.startReviews();
    setGeneration((value) => value + 1);
  };

  useEffect(() => {
    if (seeded.current) return;
    seeded.current = true;
    install(ACCEPT_THEN_FAIL);
    // `install` only seeds the fixture's retained session; the store transitions after this are
    // its own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const step = state.step;
  const stub = submitStubState();
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <KeyboardProvider statusBarTranslucent navigationBarTranslucent>
          <ThemeProvider>
            <View style={styles.chrome}>
              <Text style={styles.chromeTitle}>
                fixture · synthetic submit {stub.delayMs} ms · bridge: {stub.target} · call{" "}
                {stub.nextCall}: {stub.nextOutcome}
              </Text>
              <View style={styles.chromeRow}>
                <Pressable
                  accessibilityRole="button"
                  onPress={() => install(ACCEPT_THEN_FAIL)}
                  style={styles.chromeButton}
                >
                  <Text style={styles.chromeText}>reset: accept → fail</Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  onPress={() => install(FAIL_FIRST)}
                  style={styles.chromeButton}
                >
                  <Text style={styles.chromeText}>reset: fail first</Text>
                </Pressable>
              </View>
            </View>
            <View style={styles.screen}>
              {step.kind === "wkReview" ? (
                <WaniKaniReview
                  key={`fixture-session-${generation}`}
                  session={step.session}
                  busy={state.busy}
                  onBack={() => {}}
                  onEntry={(text) => reviewStore.wkEntry(text)}
                  onCheck={() => reviewStore.wkCheck()}
                  onContinue={() => reviewStore.wkContinue()}
                  onRetrySubmit={() => reviewStore.wkRetrySubmit()}
                />
              ) : (
                <View style={styles.fallback}>
                  <Text style={styles.fallbackText}>
                    fixture: no review session — the retained session did not publish
                  </Text>
                </View>
              )}
            </View>
          </ThemeProvider>
        </KeyboardProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  screen: {flex: 1},
  chrome: {
    paddingTop: 28,
    paddingHorizontal: 8,
    paddingBottom: 6,
    backgroundColor: "#101014",
    gap: 4,
  },
  chromeTitle: {color: "#c8ccd4", fontSize: 11, fontFamily: "monospace"},
  chromeRow: {flexDirection: "row", gap: 6},
  chromeButton: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
    backgroundColor: "#22242c",
  },
  chromeText: {color: "#c8ccd4", fontSize: 11},
  fallback: {flex: 1, alignItems: "center", justifyContent: "center", padding: 24},
  fallbackText: {color: "#f5c2c7", textAlign: "center"},
});
