#!/usr/bin/env python3
"""Capture the lookup-workflow stills on a connected emulator.

This is the tool that produced `before/` and `after/` in this directory: it drives the app over
adb, freezes a real screen through the accessibility overlay, draws a lasso with `input swipe` and
saves a PNG per state. It reads nothing from the repo and writes only into the directory it is
given, so the same script produced both sides of the comparison.

    python3 artifacts/lookup-redesign/capture_stills.py after            # -> after/*.png
    python3 artifacts/lookup-redesign/capture_stills.py before --tag v1  # -> before/*-v1.png

Assumes: one device attached, the debug APK installed, the app's accessibility service enabled
(the script enables it), and the app's own Screen lookup page reachable at the pixel coordinates
below (1080x2340 @ 440 dpi, the Pixel_Safe AVD).
"""

import argparse
import os
import re
import subprocess
import sys
import threading
import time

DEVICE = "emulator-5554"
TRIGGER = "com.gaten.app.action.TRIGGER_LOOKUP"
SERVICE = "com.gaten.app/com.gaten.app.GatenAccessibilityService"

# Coordinates in device pixels, measured on the 1080x2340 / 440 dpi Pixel_Safe AVD.
HOME_TAB = (356, 2187)
SETTINGS_TAB = (786, 2187)
RECENT_CAT_ROW = (300, 1632)  # Home -> Recently looked up -> 猫 -> word detail
CAT_HEADWORD_LASSO = (95, 375, 360, 615)  # a diagonal across 猫 + ねこ・ネコ
EMPTY_AREA = (540, 1300)  # a tap with no text box within the tolerance


def adb(*args, binary=False):
    out = subprocess.run(["adb", "-s", DEVICE, *args], capture_output=True).stdout
    return out if binary else out.decode(errors="replace")


def shell(command):
    return adb("shell", command)


def shot(directory, name, tag):
    path = os.path.join(directory, f"{name}{tag}.png")
    with open(path, "wb") as handle:
        handle.write(adb("exec-out", "screencap", "-p", binary=True))
    return path


def enable_service():
    enabled = shell("settings get secure enabled_accessibility_services").strip()
    parts = [p for p in enabled.split(":") if p]
    if SERVICE not in parts:
        parts.append(SERVICE)
        shell(f"settings put secure enabled_accessibility_services '{':'.join(parts)}'")
        shell("settings put secure accessibility_enabled 1")
        time.sleep(3)


def trigger():
    shell(f"am broadcast -a {TRIGGER} -p com.gaten.app")


def lasso(box, mid_shot=None, duration=1400):
    """Draw a lasso with one swipe, optionally capturing the trail mid-drag."""
    x1, y1, x2, y2 = box
    thread = threading.Thread(
        target=lambda: shell(f"input swipe {x1} {y1} {x2} {y2} {duration}")
    )
    thread.start()
    if mid_shot is not None:
        time.sleep(duration / 2000)
        mid_shot()
    thread.join()


def night_mode(dark):
    shell(f"settings put secure ui_night_mode {2 if dark else 1}")
    time.sleep(2)


def font_scale(scale):
    shell(f"settings put system font_scale {scale}")
    time.sleep(2)


def rotation(landscape):
    shell(f"settings put system accelerometer_rotation 0")
    shell(f"settings put system user_rotation {1 if landscape else 0}")
    time.sleep(3)


def main():
    global DEVICE
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", help="output directory, e.g. after")
    parser.add_argument("--tag", default="", help="suffix for every file name")
    parser.add_argument("--device", default=DEVICE)
    args = parser.parse_args()
    DEVICE = args.device
    directory = os.path.join(os.path.dirname(os.path.abspath(__file__)), args.directory)
    os.makedirs(directory, exist_ok=True)
    tag = f"-{args.tag}" if args.tag else ""

    enable_service()
    shell("am start -n com.gaten.app/.MainActivity")
    time.sleep(5)
    shot(directory, "home", tag)

    # A real source with Japanese on it: the app's own word detail for 猫.
    shell(f"input tap {RECENT_CAT_ROW[0]} {RECENT_CAT_ROW[1]}")
    time.sleep(3)
    shot(directory, "word-detail", tag)

    trigger()
    time.sleep(1.0)
    shot(directory, "overlay-scan-pill", tag)
    time.sleep(2.0)
    lasso(CAT_HEADWORD_LASSO, mid_shot=lambda: shot(directory, "overlay-lasso", tag))
    time.sleep(2.0)
    shot(directory, "overlay-sheet", tag)

    # Dark theme, same source, same sheet.
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    night_mode(True)
    trigger()
    time.sleep(3.0)
    lasso(CAT_HEADWORD_LASSO)
    time.sleep(2.0)
    shot(directory, "overlay-sheet-dark", tag)

    # A tap with no text under it: the notice, and the overlay staying open.
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    trigger()
    time.sleep(3.0)
    shell(f"input tap {EMPTY_AREA[0]} {EMPTY_AREA[1]}")
    time.sleep(0.7)
    shot(directory, "overlay-tap-miss", tag)

    # Landscape: the sheet docks to a side edge.
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    night_mode(False)
    rotation(True)
    trigger()
    time.sleep(3.0)
    lasso(CAT_HEADWORD_LASSO)
    time.sleep(2.0)
    shot(directory, "overlay-sheet-landscape", tag)
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    rotation(False)

    print("\n".join(sorted(os.listdir(directory))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
