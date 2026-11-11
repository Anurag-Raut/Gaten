# External lookup trigger verification

Device: emulator-5554, Android 14 / API 34, x86_64.
Baseline source: origin/dev e533bd4c2df555bf1d0e764f1b544cf7c43efc71.
Baseline APK SHA256: e4d161a6892fd8ceb766e6c4d92a89a537e8eee849d4ad431861fe46e9aae6c4.
Fixed APK SHA256: 6347c8ff9fd5eb01e7554b3a23d1a2c008635a221fcbdcdb8a73c9d9d455241d.

Canonical action: com.gaten.action.TRIGGER_LOOKUP.
Legacy action: com.gaten.app.action.TRIGGER_LOOKUP.
Package: com.gaten.
Explicit component: com.gaten/com.gaten.app.LookupTriggerReceiver.

Baseline: legacy package-scoped and explicit-component triggers open/close; action-only legacy and all canonical forms do not start capture. A target package com.gaten.app also fails because it is not installed.

Fixed: both actions work as package-scoped, action-only and explicit-component broadcasts. Each valid broadcast was handled once; first send starts capture and second closes the overlay. The legacy explicit-component case was repeated three times successfully. fixed-matrix.txt preserves an INVALID TEST using a malformed component, followed by the corrected repeats; that invalid test is not an application failure. am result=0 alone is not recipient proof.

The recording demonstrates the fixed canonical package-scoped trigger opening the overlay over Chrome and the next trigger closing it. The two PNGs are frames extracted from that recording (open at 2 seconds and final closed frame); they are not live screenshots. workflow-log.txt records the accepted capture and toggle-close events for that recording.

agent-device v0.21.31 semantic captures can connect UiAutomation with flags=0 and dismiss the accessibility overlay. The final evidence uses agent-device recording without semantic capture while the overlay is visible. This is a testing-tool limitation, not a product-code fix.

Checks: tsc --noEmit passed; assembleDebug passed. lintDebug reports eight existing baseline errors; no new lint errors reported.

Not exercised: tile (separate known issue), screen-off/lock guards, service disable/re-enable lifecycle. No code changes followed the device run; subsequent changes only attach evidence.
