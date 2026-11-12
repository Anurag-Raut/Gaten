/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot return to Gaten: fresh enough to be the hand-off the reader just started, never a
 * stale flag that hijacks an unrelated later enable.
 */
class AccessibilityReturnTest {

    private val markedAt = 1_700_000_000_000L

    @Test
    fun `a fresh mark returns`() {
        assertTrue(
            AccessibilityReturn.shouldReturn(
                armed = true,
                markedAtMs = markedAt,
                nowMs = markedAt + 1_000
            )
        )
    }

    @Test
    fun `the window is ten minutes`() {
        assertTrue(
            AccessibilityReturn.shouldReturn(
                armed = true,
                markedAtMs = markedAt,
                nowMs = markedAt + AccessibilityReturn.EXPIRY_MS
            )
        )
        assertFalse(
            AccessibilityReturn.shouldReturn(
                armed = true,
                markedAtMs = markedAt,
                nowMs = markedAt + AccessibilityReturn.EXPIRY_MS + 1
            )
        )
    }

    @Test
    fun `an unarmed request never returns`() {
        assertFalse(
            AccessibilityReturn.shouldReturn(
                armed = false,
                markedAtMs = markedAt,
                nowMs = markedAt + 1_000
            )
        )
    }

    @Test
    fun `a missing timestamp never returns`() {
        assertFalse(
            AccessibilityReturn.shouldReturn(
                armed = true,
                markedAtMs = 0L,
                nowMs = markedAt
            )
        )
    }

    @Test
    fun `a clock that moved backwards does not return`() {
        assertFalse(
            AccessibilityReturn.shouldReturn(
                armed = true,
                markedAtMs = markedAt,
                nowMs = markedAt - 1
            )
        )
    }
}
