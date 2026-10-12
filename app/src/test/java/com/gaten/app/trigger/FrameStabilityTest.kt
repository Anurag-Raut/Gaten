/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameStability]: whether two probe frames have stopped moving, and when the probe loop is out of
 * time. The pixels are opaque black unless a test says otherwise; the numbers are chosen against
 * [FrameStability.DEFAULT_THRESHOLD] of 2.0 per channel.
 */
class FrameStabilityTest {

    @Test
    fun theSamePixelTwiceDiffersByNothingAndIsStable() {
        val frame = filled(100, 0xFF102030.toInt())

        assertEquals(0f, FrameStability.meanAbsoluteDifference(frame, frame.copyOf()), 0f)
        assertTrue(FrameStability.isStable(FrameStability.meanAbsoluteDifference(frame, frame.copyOf())))
    }

    @Test
    fun oneLevelOfNoiseOnEveryChannelStaysStable() {
        // 0x0A 0x0B 0x0C -> 0x0B 0x0C 0x0D: +1 on every channel, a mean difference of 1.0.
        val a = filled(100, 0xFF0A0B0C.toInt())
        val b = filled(100, 0xFF0B0C0D.toInt())

        val diff = FrameStability.meanAbsoluteDifference(a, b)

        assertEquals(1f, diff, 0f)
        assertTrue(FrameStability.isStable(diff))
    }

    @Test
    fun aBlockOfPixelsThatMovedIsNotStable() {
        // 20 of the 100 pixels change by 100 on every channel: (20 * 300) / (100 * 3) = 20.0.
        val before = filled(100, 0xFF000000.toInt())
        val after = before.copyOf()
        for (i in 0 until 20) after[i] = 0xFF646464.toInt()

        val diff = FrameStability.meanAbsoluteDifference(before, after)

        assertEquals(20f, diff, 0f)
        assertFalse(FrameStability.isStable(diff))
    }

    @Test
    fun aDifferenceInAlphaAloneIsIgnored() {
        val opaque = filled(100, 0xFF102030.toInt())
        val translucent = filled(100, 0x80102030.toInt())

        assertEquals(0f, FrameStability.meanAbsoluteDifference(opaque, translucent), 0f)
        assertTrue(FrameStability.isStable(FrameStability.meanAbsoluteDifference(opaque, translucent)))
    }

    @Test
    fun framesOfDifferentSizesAreNeverStable() {
        val diff = FrameStability.meanAbsoluteDifference(filled(100, 0), filled(96, 0))

        assertEquals(Float.MAX_VALUE, diff, 0f)
        assertFalse(FrameStability.isStable(diff))
    }

    @Test
    fun emptyFramesAreNeverStable() {
        assertEquals(
            Float.MAX_VALUE,
            FrameStability.meanAbsoluteDifference(IntArray(0), IntArray(0)),
            0f
        )
        assertEquals(
            Float.MAX_VALUE,
            FrameStability.meanAbsoluteDifference(IntArray(0), filled(4, 0)),
            0f
        )
        assertFalse(FrameStability.isStable(Float.MAX_VALUE))
    }

    @Test
    fun aDifferenceIsJudgedAgainstTheThresholdItIsGiven() {
        assertFalse(FrameStability.isStable(3f))
        assertTrue(FrameStability.isStable(3f, threshold = 5f))
        // The threshold is a strict bound: exactly 2.0 is still moving.
        assertFalse(FrameStability.isStable(FrameStability.DEFAULT_THRESHOLD))
    }

    @Test
    fun theBudgetAllowsAProbeBeforeItsDeadlineOnly() {
        assertTrue(FrameStability.shouldProbeAgain(nowMs = 999L, deadlineMs = 1000L))
        assertFalse(FrameStability.shouldProbeAgain(nowMs = 1000L, deadlineMs = 1000L))
        assertFalse(FrameStability.shouldProbeAgain(nowMs = 1001L, deadlineMs = 1000L))
    }

    /** [count] pixels of one ARGB colour. */
    private fun filled(count: Int, argb: Int) = IntArray(count) { argb }
}
