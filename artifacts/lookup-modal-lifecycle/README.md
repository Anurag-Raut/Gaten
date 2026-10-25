# Lookup modal lifecycle regression

| Item | Result |
| --- | --- |
| Bug | Circling Japanese text completed recognition but left a frozen, dimmed screen with no modal. |
| Cause | The service started a React surface while the shared React host was paused; Fabric mounting remained paused. |
| Fix | Keep the host resumed while either the main Activity or a lookup-sheet session needs it. |
| Verification | Debug build passed; 11 lifecycle tests passed; cold and background lookup, reselect, and dismissal checked on the emulator. |

## Evidence

- [Before](before.png): baseline commit `4953665`, after MainActivity was backgrounded by Chrome. Circle recognition completed in 518 ms and JS logged `Running "GatenLookupSheet"`, but the modal never painted.
- [After](after.png): the patched app was installed while Chrome stayed foreground. Without opening MainActivity in the new process, the same circle opened the dictionary modal (recognition completed in 417 ms).

Device: `Gaten_Lookup_Review`, Android emulator, x86_64, 1080 × 2340. The page is a local fixture containing 猫が好きです。 and 日本語を読む。; it makes no dictionary requests over the network.

## Reproduction and checks

1. Show Japanese text in another app, with Gaten accessibility lookup enabled.
2. Trigger lookup with the floating button or `com.gaten.action.TRIGGER_LOOKUP` broadcast.
3. Draw a circle around the text. The dictionary modal should appear after recognition.
4. Choose **Select again**, draw another circle, and confirm another modal opens.
5. Close the modal, open and background Gaten, then repeat the lookup. The modal should still open.

The baseline failed step 3. The patched app passed a service-only start, a lookup after opening/backgrounding MainActivity, reselecting text, and closing the overlay. The main screen also rendered again after the service-only lookup. No AndroidRuntime crash was observed in these flows. Physical-device verification is still outstanding.

Build: `ANDROID_HOME=/home/Qui/Android/Sdk ./gradlew :app:assembleDebug`

Focused tests: `ANDROID_HOME=/home/Qui/Android/Sdk ./gradlew :app:testDebugUnitTest --tests com.gaten.app.rn.ReactHostLifecyclePolicyTest`

The initial build/test run passed all 11 tests; the final APK was rebuilt after the Activity destroy callback was moved after `super.onDestroy()` and was installed for the emulator checks above.

The agent-device UI automation session was closed before exercising lookup because its UiAutomation connection suppressed Gaten's accessibility service. ADB touch input and screenshots were used for this service-owned window.

## Changed files

- `app/src/main/java/com/gaten/app/MainActivity.kt`: report Activity lifecycle changes after React's own delegate.
- `app/src/main/java/com/gaten/app/ui/lookup/LookupSheetHost.kt`: acquire/release host ownership for each sheet session; stop is idempotent.
- `app/src/main/java/com/gaten/app/rn/ReactHostLifecycle.kt`: resume/pause the existing shared host; no second runtime.
- `app/src/main/java/com/gaten/app/rn/ReactHostLifecyclePolicy.kt`: Activity and sheet ownership accounting, including stale Activity release protection.
- `app/src/test/java/com/gaten/app/rn/ReactHostLifecyclePolicyTest.kt`: 11 lifecycle regression tests.
- This evidence directory: report and before/after screenshots.
