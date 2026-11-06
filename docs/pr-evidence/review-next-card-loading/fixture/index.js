/**
 * Evidence fixture entry — not part of the app and never imported by it.
 *
 * The React Native Gradle plugin resolves ENTRY_FILE first (PathUtils.kt), so this build needs no
 * production file change:
 *
 *   ENTRY_FILE=docs/pr-evidence/review-next-card-loading/fixture/index.js \
 *     ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64
 *
 * MainActivity mounts the component named "Gaten"; the fixture takes that name for this build.
 * The production lookup-sheet component is deliberately not registered.
 *
 * `require` is used in this order on purpose: the synthetic submit bridge is installed before the
 * fixture component (and therefore the review store) is loaded, so the store closes over the
 * patched bridge from its first evaluation.
 */
const {AppRegistry} = require("react-native");
const {installSubmitStub} = require("./submitStub");

const bridgeTarget = installSubmitStub();
if (bridgeTarget === "FAILED") {
  console.warn("Evidence fixture: the synthetic WaniKani submit bridge could not be installed");
}

const {ReviewFixture} = require("./ReviewFixture");

AppRegistry.registerComponent("Gaten", () => ReviewFixture);
