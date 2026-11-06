#!/bin/sh
#
# Gaten — capture the lookup overlay in landscape, adb only.
#
#   sh capture-overlay.sh <serial> <absolute-path-to-after-apk> [tapX tapY]
#
# WHY THIS IS NOT AN agent-device RUN
# ----------------------------------
# agent-device drives Android through a UiAutomation instrumentation that obtains UiAutomation with
# `Instrumentation.getUiAutomation()` — flags 0. Per the platform docs for
# `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` (API 24, value 1): "UiAutomation suppresses
# accessibility services by default. This flag specifies that existing accessibility services should
# continue to run, and that new ones may start." Without the flag, Gaten's accessibility service stays
# in `Enabled services` but never reaches `Bound services`, so the floating trigger never appears and
# no lookup can be started. agent-device exposes no flag for this. The repo's device rule therefore
# allows the adb fallback here, and this is the route every earlier overlay still in this repository
# was taken with (see docs/pr-evidence/anki-footer/README.md, "Capture steps" step 2).
#
# SCOPE: this is the one workflow the tool cannot cover. It is not a general adb workaround.
# Run it with NO agent-device session open on the device — a session suppresses the service again.
#
set -eu

SERIAL="${1:?usage: capture-overlay.sh <serial> <absolute-apk-path> [tapX tapY]}"
APK="${2:?usage: capture-overlay.sh <serial> <absolute-apk-path> [tapX tapY]}"
TAP_X="${3:-}"
TAP_Y="${4:-}"
D="adb -s $SERIAL"
OUT="$(cd "$(dirname "$0")" && pwd)"
SCRATCH=/tmp/gaten-overlay-capture
EXPECTED_APK_SHA=86e5d19a9d46ad843ebc351d69520ac32ba953cf395a4d8028e7ac18b63164ca
SERVICE=com.gaten/com.gaten.app.GatenAccessibilityService
# LookupTriggerReceiver.ACTION_TRIGGER_LOOKUP, the canonical name for the installed com.gaten package.
# The legacy `com.gaten.app.action.TRIGGER_LOOKUP` (what artifacts/lookup-redesign used, when the
# applicationId was com.gaten.app) is still handled by the same service receiver. Either works; the
# service must be connected either way, because the receiver is registered by the service.
TRIGGER=com.gaten.action.TRIGGER_LOOKUP
PREFS=/data/data/com.gaten/shared_prefs/com.gaten_preferences.xml
SETTINGS=/data/data/com.gaten/shared_prefs/gaten_settings.xml
DEVBUNDLE=/data/data/com.gaten/files/BridgelessReactNativeDevBundle.js

mkdir -p "$SCRATCH"

# ---------------------------------------------------------------- 0. snapshot, BEFORE any mutation
$D shell settings get secure enabled_accessibility_services > "$SCRATCH/accessibility_services.before"
$D shell settings get secure accessibility_enabled          > "$SCRATCH/accessibility_enabled.before"
$D shell settings get system accelerometer_rotation         > "$SCRATCH/accelerometer_rotation.before"
$D shell settings get system user_rotation                  > "$SCRATCH/user_rotation.before"
$D shell settings get secure ui_night_mode                  > "$SCRATCH/ui_night_mode.before"
$D shell settings get system font_scale                     > "$SCRATCH/font_scale.before"
# `exec-out` merges the DEVICE's stderr into stdout, so a failed read writes "cat: … No such file"
# into the snapshot file instead of nothing. Each snapshot is therefore validated before it is kept.
$D exec-out run-as com.gaten cat "$PREFS" > "$SCRATCH/com.gaten_preferences.before" 2>/dev/null || true
grep -q "<map" "$SCRATCH/com.gaten_preferences.before" 2>/dev/null || rm -f "$SCRATCH/com.gaten_preferences.before"
$D exec-out run-as com.gaten cat "$SETTINGS" > "$SCRATCH/gaten_settings.before" 2>/dev/null || true
grep -q "<map" "$SCRATCH/gaten_settings.before" 2>/dev/null || rm -f "$SCRATCH/gaten_settings.before"
# the RN dev-bundle cache wins over the packaged bundle when present, so it is set aside, not deleted
$D exec-out run-as com.gaten cat "$DEVBUNDLE" > "$SCRATCH/dev-bundle.before" 2>/dev/null || true
if [ "$(head -c 4 "$SCRATCH/dev-bundle.before" 2>/dev/null)" = "cat:" ]; then
    rm -f "$SCRATCH/dev-bundle.before"
fi
if [ ! -s "$SCRATCH/dev-bundle.before" ]; then rm -f "$SCRATCH/dev-bundle.before"; fi
# the recorded dev-server host, so it can be merged back rather than the whole map replaced
HOST_BEFORE=$(sed -n 's#.*name="debug_http_host">\([^<]*\)</string>.*#\1#p' \
    "$SCRATCH/com.gaten_preferences.before" | head -1)
HOST_BEFORE=$(printf '%s' "$HOST_BEFORE" | tr -d '\r')

# ---------------------------------------------------------------- cleanup, installed before the first write
# Rewrites ONLY the debug_http_host key of the app's default preference map, leaving every other key
# (and every key the app writes while the capture runs) exactly as it is. "absent" removes the key.
set_host() {
    _src="$SCRATCH/prefs.work.xml"
    $D exec-out run-as com.gaten cat "$PREFS" > "$_src" 2>/dev/null || true
    # Never write an empty map over the real one: if the file cannot be read, leave it alone.
    if [ ! -s "$_src" ]; then
        echo "could not read $PREFS — left untouched"
        return 0
    fi
    if [ "$1" = absent ]; then
        sed '/name="debug_http_host"/d' "$_src" > "$_src.new"
    elif grep -q 'name="debug_http_host"' "$_src"; then
        sed 's#\(name="debug_http_host">\)[^<]*#\1'"$1"'#' "$_src" > "$_src.new"
    else
        sed 's#</map>#  <string name="debug_http_host">'"$1"'</string>\n</map>#' "$_src" > "$_src.new"
    fi
    $D push "$_src.new" /data/local/tmp/prefs.new.xml >/dev/null
    $D shell run-as com.gaten cp /data/local/tmp/prefs.new.xml "$PREFS"
}

# Restores every value recorded in step 0. Runs on success, failure and interrupt alike.
RESTORED=0
restore_state() {
    [ "$RESTORED" = 1 ] && return 0
    RESTORED=1
    set +e
    $D shell am force-stop com.gaten
    if [ "$(tr -d '\r' < "$SCRATCH/accessibility_services.before")" = null ]; then
        $D shell settings delete secure enabled_accessibility_services
    else
        $D shell settings put secure enabled_accessibility_services \
            "$(tr -d '\r' < "$SCRATCH/accessibility_services.before")"
    fi
    $D shell settings put secure accessibility_enabled  "$(tr -d '\r' < "$SCRATCH/accessibility_enabled.before")"
    $D shell settings put system accelerometer_rotation "$(tr -d '\r' < "$SCRATCH/accelerometer_rotation.before")"
    $D shell settings put system user_rotation          "$(tr -d '\r' < "$SCRATCH/user_rotation.before")"
    $D shell settings put secure ui_night_mode          "$(tr -d '\r' < "$SCRATCH/ui_night_mode.before")"
    $D shell settings put system font_scale             "$(tr -d '\r' < "$SCRATCH/font_scale.before")"
    if [ -n "$HOST_BEFORE" ]; then set_host "$HOST_BEFORE"; else set_host absent; fi
    if [ -s "$SCRATCH/gaten_settings.before" ]; then
        $D push "$SCRATCH/gaten_settings.before" /data/local/tmp/gaten_settings.before >/dev/null
        $D shell run-as com.gaten cp /data/local/tmp/gaten_settings.before "$SETTINGS"
    else
        $D shell run-as com.gaten rm -f "$SETTINGS"
    fi
    if [ -s "$SCRATCH/dev-bundle.before" ]; then
        $D push "$SCRATCH/dev-bundle.before" /data/local/tmp/dev-bundle.js >/dev/null
        $D shell run-as com.gaten cp /data/local/tmp/dev-bundle.js "$DEVBUNDLE"
        $D shell run-as com.gaten chmod 600 "$DEVBUNDLE"
    fi
    $D shell rm -f /data/local/tmp/prefs.new.xml /data/local/tmp/gaten_settings.before /data/local/tmp/dev-bundle.js
    echo "restored — compare against $SCRATCH/*.before"
}
# Cleanup belongs to EXIT alone; a signal ends the run so the EXIT trap does the restore once.
trap 'restore_state' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------- 1. install, and prove what is installed
$D shell am force-stop com.gaten
$D install -r "$APK"
INSTALLED=$($D shell pm path com.gaten | tr -d '\r' | sed 's/package://')
ONDEVICE=$($D shell sha256sum "$INSTALLED" | tr -d '\r' | cut -d' ' -f1)
[ "$ONDEVICE" = "$EXPECTED_APK_SHA" ] || { echo "installed APK is $ONDEVICE, expected $EXPECTED_APK_SHA"; exit 1; }

# ---------------------------------------------------------------- 2. isolate the bundle from any foreign Metro
set_host 10.0.2.2:8089
$D shell run-as com.gaten rm -f "$DEVBUNDLE"
$D shell am start -n com.gaten/.app.MainActivity
sleep 8
# expect: "isMetroRunning(): Async result = false" and "Loading JS Bundle" — the packaged bundle
$D logcat -d | grep -E "isMetroRunning|Loading JS Bundle" | tail -3

# ---------------------------------------------------------------- 3. a landscape source screen
# Chrome, landscape, showing Japanese. The URL is percent-encoded so it is pure ASCII and needs no IME.
# If the emulator has no network, use an installed app that already shows Japanese (AnkiDroid with a
# Japanese card) instead of this launch.
# A running Chrome ignores the VIEW intent ("its current task has been brought to the front"), so it
# is stopped first — otherwise the frozen frame is whatever page was already open.
$D shell am force-stop com.android.chrome
sleep 1
$D shell am start -n com.android.chrome/com.google.android.apps.chrome.Main \
    -a android.intent.action.VIEW -d "https://ja.wikipedia.org/wiki/%E7%8C%AB"
sleep 10
$D shell settings put system accelerometer_rotation 0
$D shell settings put system user_rotation 1          # landscape
sleep 4
$D exec-out screencap -p > "$SCRATCH/landscape-source.png"
$D shell dumpsys window displays | grep -oE "mDisplayRotation=ROTATION_[0-9]+" | head -1   # ROTATION_90

# ---------------------------------------------------------------- 4. enable the service, and PROVE OURS bound
$D shell settings put secure enabled_accessibility_services "$SERVICE"
$D shell settings put secure accessibility_enabled 1
sleep 5
$D shell dumpsys accessibility | grep -E "Bound services|Enabled services"
# The service being enabled is not enough — the trigger is answered by a receiver the SERVICE
# registers, so it must be bound. Android's dump does not name the component on the Bound line, so
# this proves it for this run: the Enabled list is EXACTLY our component (nothing else can be bound
# for Gaten), and the Bound line carries our service's label.
A11Y=$($D shell dumpsys accessibility)
ENABLED_LIST=$(printf '%s\n' "$A11Y" | grep -m1 "Enabled services:" | sed 's/.*Enabled services:{{//; s/}}.*//')
BOUND_LINE=$(printf '%s\n' "$A11Y" | grep -m1 "Bound services:")
if [ "$ENABLED_LIST" != "com.gaten/com.gaten.app.GatenAccessibilityService" ]; then
    echo "enabled accessibility services are [$ENABLED_LIST], not exactly ours — do not trigger"
    exit 1
fi
if ! printf '%s' "$BOUND_LINE" | grep -q "label=Gaten"; then
    echo "our service is not bound: $BOUND_LINE"
    echo "do not trigger — check that no agent-device session is open on this device"
    exit 1
fi

# ---------------------------------------------------------------- 5. trigger, and capture
# The recording starts first so the overlay's arrival is inside it.
$D shell screenrecord --time-limit 15 /sdcard/gaten-overlay.mp4 &
RECORDER=$!
sleep 2
$D shell am broadcast -a "$TRIGGER" -p com.gaten
sleep 3
$D exec-out screencap -p > "$OUT/after-03-overlay-landscape.png"     # overlay shell, frozen frame
# after-04 is written ONLY when a real result sheet is raised: pass the tap coordinates of recognized
# Japanese read off after-03. Without them the frozen-frame still is the whole capture, and nothing is
# written under a name that would claim a sheet.
if [ -n "$TAP_X" ] && [ -n "$TAP_Y" ]; then
    $D shell input tap "$TAP_X" "$TAP_Y"
    sleep 3
    $D exec-out screencap -p > "$OUT/after-04-overlay-landscape-sheet.png"
else
    echo "no tap coordinates given — after-04 NOT written; inspect after-03, then rerun with X Y"
fi
wait "$RECORDER" || true
$D pull /sdcard/gaten-overlay.mp4 "$OUT/after-05-overlay-landscape.mp4"
$D shell rm -f /sdcard/gaten-overlay.mp4
# confirm the frame really is landscape (2340x1080), not a portrait window on a wide display
$D shell dumpsys window displays | grep -oE "mDisplayRotation=ROTATION_[0-9]+" | head -1
$D shell dumpsys window displays | grep -oE "mCurrentFocus=Window\{[^}]*\}" | head -1

# restore runs from the trap, on every exit path
