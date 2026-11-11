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
 * The shared React host runs while either the main Activity or a lookup sheet session needs it.
 *
 * These are the orderings that produced the frozen-overlay bug: a sheet opened with the Activity
 * paused (or never started) must resume the host, an Activity pausing or being destroyed under a
 * visible sheet must not pause it, and the host must pause only when the last owner leaves.
 */
class ReactHostLifecyclePolicyTest {

    private val activity = Any()

    @Test
    fun `cold service-only sheet resumes the host from before-create`() {
        val policy = ReactHostLifecyclePolicy()

        policy.onOverlaySessionOpened()

        assertTrue("the sheet is the only owner, so the host is needed", policy.hostNeeded)
        assertEquals(HostLifecycleAction.Resume, policy.action(hostResumed = false))
    }

    @Test
    fun `a sheet closing with no Activity left pauses the host`() {
        val policy = ReactHostLifecyclePolicy()
        policy.onOverlaySessionOpened()

        policy.onOverlaySessionClosed()

        assertFalse(policy.hostNeeded)
        assertEquals(HostLifecycleAction.Pause, policy.action(hostResumed = true))
    }

    @Test
    fun `an Activity pause under an open sheet does not pause the host`() {
        val policy = ReactHostLifecyclePolicy()
        policy.onActivityResumed(activity)
        policy.onOverlaySessionOpened()

        // MainActivity.onPause: React's delegate paused the host, then the owner state changes.
        policy.onActivityPaused(activity)

        assertTrue("the sheet keeps needing it", policy.hostNeeded)
        assertEquals(HostLifecycleAction.Resume, policy.action(hostResumed = false))
    }

    @Test
    fun `an Activity destroy under an open sheet does not pause the host`() {
        val policy = ReactHostLifecyclePolicy()
        policy.onActivityResumed(activity)
        policy.onOverlaySessionOpened()
        policy.onActivityPaused(activity) // onPause, host resumed again for the sheet

        // onDestroy repeats the Activity's release; the sheet is still open.
        policy.onActivityPaused(activity)

        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = true))
    }

    @Test
    fun `a stale Activity pause cannot drop a newer owner`() {
        val older = Any()
        val newer = Any()
        val policy = ReactHostLifecyclePolicy()
        policy.onActivityResumed(older)
        policy.onActivityResumed(newer)

        assertFalse("the older Activity no longer owns the host", policy.onActivityPaused(older))
        assertTrue("the newer Activity still does", policy.hostNeeded)

        assertTrue(policy.onActivityPaused(newer))
        assertFalse(policy.hostNeeded)
    }

    @Test
    fun `a sheet closing over a resumed Activity leaves the host running`() {
        val policy = ReactHostLifecyclePolicy()
        policy.onActivityResumed(activity)
        policy.onOverlaySessionOpened()

        policy.onOverlaySessionClosed()

        assertTrue("the Activity still needs it", policy.hostNeeded)
        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = true))
    }

    @Test
    fun `the last of the two owners leaving pauses the host`() {
        val policy = ReactHostLifecyclePolicy()
        policy.onActivityResumed(activity)
        policy.onOverlaySessionOpened()

        policy.onOverlaySessionClosed()
        policy.onActivityPaused(activity)

        assertFalse(policy.hostNeeded)
        assertEquals(HostLifecycleAction.Pause, policy.action(hostResumed = true))
    }

    @Test
    fun `an Activity resumed with no sheet resumes a paused host and does nothing when already running`() {
        val policy = ReactHostLifecyclePolicy()

        policy.onActivityResumed(activity)
        assertEquals(HostLifecycleAction.Resume, policy.action(hostResumed = false))
        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = true))
    }

    @Test
    fun `action is idempotent for repeated matching states`() {
        val policy = ReactHostLifecyclePolicy()

        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = false))
        policy.onActivityResumed(activity)
        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = true))
    }

    @Test
    fun `overlapping sheet sessions only release when the last one closes`() {
        val policy = ReactHostLifecyclePolicy()

        policy.onOverlaySessionOpened()
        policy.onOverlaySessionOpened()
        policy.onOverlaySessionClosed()

        assertTrue("a newer session still owns the host", policy.hostNeeded)
        policy.onOverlaySessionClosed()
        assertFalse(policy.hostNeeded)
    }

    @Test
    fun `closing without an open session cannot make the count negative`() {
        val policy = ReactHostLifecyclePolicy()

        policy.onOverlaySessionClosed()

        assertFalse(policy.hostNeeded)
        assertEquals(HostLifecycleAction.None, policy.action(hostResumed = false))
    }
}
