# Floating bubble: dead-tap root cause (W6, emulator-5554, API 34, 1080×2340 @440dpi)

Evidence for §3.5.1 of [PLAN.md](../PLAN.md). Screenshots in this folder are from the run described
here (`bubble-tap-opens-overlay.png`). All state was restored afterwards.

## Symptom as recorded

`current/emulator-findings.md` §6: taps at the bubble did nothing, the first one "fell through to the
list *under* the overlay", and the drawn circle sat "~145 px below the window's reported frame
origin".

## Measured cause

`dumpsys window windows`, before the fix:

```
mAttrs={(926,962)(wrapxwrap) gr=TOP START CENTER ty=2032
  fitTypes=STATUS_BARS NAVIGATION_BARS CAPTION_BAR
mAttrs.fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED
Frames: parent=[0,136][1080,2340] display=[0,136][1080,2340] frame=[926,1098][1058,1230]
```

* The window was laid out inside an **inset-adjusted parent frame whose origin is (0,136)** — the
  status-bar inset — because `LayoutParams` fits the system bars by default. `x`/`y` are offsets in
  that frame, so the requested `y=962` put the bubble **and its whole touch rectangle** at screen
  `y 1098–1230`: one status-bar height below the position the window reports, the stored position
  holds, and `displayMetrics`-based code computes.
* A tap aimed at the reported position `(926,962)` is therefore 136 px above the bubble and lands on
  whatever is behind — the recorded fall-through. (In landscape the same inset moves to the left
  edge: `parent=[136,0][2340,1080]`.)
* Tap at the real rect does work: `input tap 992 1164` → `Trigger accepted`, `Frozen frame on
  screen (+124 ms since the trigger)`.
* Second dead path, independent of coordinates: the tap test was capped at 250 ms, and a stationary
  press released after that did **nothing at all** — measured `input swipe 992 1164 992 1164 400`
  produced no log line, no trigger, no drag. (This device's own long-press timeout is 400 ms:
  `settings get secure long_press_timeout`.)

## Fix (`trigger/FloatingTriggerButton.kt`, plus one call in `GatenAccessibilityService.kt`)

1. The window no longer fits the system bars (`fitInsetsTypes = 0`, API 30+;
   `FLAG_LAYOUT_IN_SCREEN` below), so `x`/`y` are real display coordinates — the same space the
   stored position, the default position and `dumpsys` report in.
2. Every position taken in — restored, dragged, re-clamped — is clamped into this context's
   `displayMetrics` box, which stops at the system bars, so the bubble can never be stored where it
   cannot be seen or touched.
3. Re-clamp on configuration change (`onConfigurationChanged` → `reclampToDisplay`), because a
   stored position is not clipped by the window manager for the next drag.
4. The tap/drag split is movement only (the 8 dp slop); the timer that silently dropped slower
   presses is gone.

## Verified after the fix

| Check | Result |
|---|---|
| Window frame vs requested position | `mAttrs={(926,962)…}` → `Frames: parent=[0,0][1080,2340] frame=[926,962][1058,1094]` — they coincide |
| Tap at the drawn centre | `Trigger accepted`, `Starting the ordered capture pipeline`, `Overlay window added for a 1080x2340 capture`; screenshot `bubble-tap-opens-overlay.png` |
| 400 ms stationary press-release | `Trigger accepted` (previously silent) |
| 1500 ms stationary press-release | `Trigger accepted` |
| Drag to the bottom-right corner | frame `[948,2006][1080,2138]`, stored `(948, 2006)` = clamped, no trigger fired |
| Rotation (user_rotation 1) | re-clamped to `mAttrs={(948,882)}`, frame `[948,882][1080,1014]`, stored `(948, 882)`, tap there still opens the overlay |

## Not reproduced, and why it cannot be re-tested after the fact

The recorded "three taps at the bubble's measured centre produced no log line" was not reproducible in
the quiet session: taps at the real rect work. The recorded session was shared with two foreign
accessibility overlays (`com.akslabs.circletosearch`, `com.yomilens.app`) that also draw bubbles at
the right edge — one of them labelled "Gaten" too — and a foreign overlay window stacked over Gaten's
consumes the touch (`TYPE_ACCESSIBILITY_OVERLAY`, topmost window under the pointer wins). Their
windows were already gone by the time this session was quiet, so this contributor remains inferred,
not measured. Re-verification needs a session with those services off.
