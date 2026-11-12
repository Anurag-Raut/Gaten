/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility-settings hand-off: API 29+ opens Gaten's own service page; older releases open
 * the list and ask Settings to highlight the same service there.
 */
class AccessibilitySettingsTargetTest {

    private val component = "com.gaten/com.gaten.app.GatenAccessibilityService"

    @Test
    fun `api 29 and newer open the service details page`() {
        for (sdk in listOf(29, 30, 34, 36)) {
            val plan = AccessibilitySettingsTarget.plan(sdk, component)
            assertEquals(AccessibilitySettingsTarget.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, plan.action)
            assertEquals(component, plan.componentName)
            assertFalse(plan.highlightComponent)
        }
    }

    @Test
    fun `older releases open the service list with a highlight`() {
        val plan = AccessibilitySettingsTarget.plan(28, component)
        assertEquals(AccessibilitySettingsTarget.ACTION_ACCESSIBILITY_SETTINGS, plan.action)
        assertEquals(component, plan.componentName)
        assertTrue(plan.highlightComponent)
    }

    /** The hidden action and Settings' list keys are strings, not constants in the public SDK. */
    @Test
    fun `the intent contract matches Settings`() {
        assertEquals(
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
            AccessibilitySettingsTarget.ACTION_ACCESSIBILITY_DETAILS_SETTINGS
        )
        assertEquals(
            "android.settings.ACCESSIBILITY_SETTINGS",
            AccessibilitySettingsTarget.ACTION_ACCESSIBILITY_SETTINGS
        )
        assertEquals(":settings:fragment_args_key", AccessibilitySettingsTarget.FRAGMENT_ARGS_KEY)
        assertEquals(":settings:show_fragment_args", AccessibilitySettingsTarget.SHOW_FRAGMENT_ARGS)
        assertEquals(29, AccessibilitySettingsTarget.DETAILS_MIN_API)
    }
}
