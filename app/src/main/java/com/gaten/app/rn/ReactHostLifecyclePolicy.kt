/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

/** What the shared React host should do to match the two UI owners' current state. */
internal enum class HostLifecycleAction { Resume, Pause, None }

/**
 * The owner accounting behind [ReactHostLifecycle], kept free of Android so its orderings are
 * unit-tested directly. The host is needed while an Activity is resumed or any overlay session is
 * open; [action] is idempotent, so resume/pause events from either owner can arrive in any order.
 *
 * The Activity is identified by an opaque token (its instance in production), so a late `onPause`
 * or `onDestroy` from an Activity a newer one has already replaced is recognised and ignored
 * instead of dropping the new owner.
 */
internal class ReactHostLifecyclePolicy {

    private var activityOwner: Any? = null
    private var overlaySessions = 0

    /** True while some owner still needs the host running. */
    val hostNeeded: Boolean
        get() = activityOwner != null || overlaySessions > 0

    fun onActivityResumed(owner: Any) {
        activityOwner = owner
    }

    /**
     * Releases [owner]'s claim. False when a different, newer Activity owns the host, in which case
     * nothing changes.
     */
    fun onActivityPaused(owner: Any): Boolean {
        if (activityOwner != null && activityOwner !== owner) return false
        activityOwner = null
        return true
    }

    fun onOverlaySessionOpened() {
        overlaySessions++
    }

    fun onOverlaySessionClosed() {
        if (overlaySessions > 0) overlaySessions--
    }

    /** The one host transition that brings [hostResumed] in line with the owners' state. */
    fun action(hostResumed: Boolean): HostLifecycleAction = when {
        hostNeeded && !hostResumed -> HostLifecycleAction.Resume
        !hostNeeded && hostResumed -> HostLifecycleAction.Pause
        else -> HostLifecycleAction.None
    }
}
