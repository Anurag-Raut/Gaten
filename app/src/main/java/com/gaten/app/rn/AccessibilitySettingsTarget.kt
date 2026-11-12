/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

/**
 * Where "Turn on lookup mode" sends the reader: Gaten's own accessibility-service page where the OS
 * has one, otherwise the accessibility-service list with Gaten's row highlighted.
 *
 * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` is a hidden system API rather than a public
 * constant, so its action string is written out here. It has existed since Android 10 (API 29); on
 * older releases the list screen is the only target. The plan is pure so a JVM test can pin the
 * choice and the exact intent contract without a device.
 */
internal data class AccessibilitySettingsPlan(
    val action: String,
    /** The flattened Gaten service, as the details page and the list highlight both expect it. */
    val componentName: String,
    /** True when the list screen should scroll to and highlight Gaten's row. */
    val highlightComponent: Boolean
)

internal object AccessibilitySettingsTarget {
    /** Public `Settings.ACTION_ACCESSIBILITY_SETTINGS`. */
    const val ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"

    /** Hidden `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`, available from Android 10. */
    const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** The hidden `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` appeared in API 29. */
    const val DETAILS_MIN_API = 29

    /** Settings' list-screen key naming the row to scroll to and highlight. */
    const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"

    /** Settings' list-screen extra carrying the fragment arguments bundle. */
    const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /**
     * The intent plan for [sdkInt] and the flattened [componentName]: the service's own details page
     * on API 29+, otherwise the service list with that row highlighted.
     */
    fun plan(sdkInt: Int, componentName: String): AccessibilitySettingsPlan =
        if (sdkInt >= DETAILS_MIN_API) {
            AccessibilitySettingsPlan(
                action = ACTION_ACCESSIBILITY_DETAILS_SETTINGS,
                componentName = componentName,
                highlightComponent = false
            )
        } else {
            AccessibilitySettingsPlan(
                action = ACTION_ACCESSIBILITY_SETTINGS,
                componentName = componentName,
                highlightComponent = true
            )
        }
}
