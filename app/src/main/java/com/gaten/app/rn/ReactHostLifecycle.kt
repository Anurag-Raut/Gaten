/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.common.LifecycleState

/**
 * Keeps the application's single [ReactHost] running for the two owners of React UI in this process:
 * the main Activity and the accessibility service's lookup sheet.
 *
 * A `ReactSurface.start()` into a host that is not resumed queues its mount items behind Fabric's
 * paused frame callback, so the RN tree never attaches. The sheet opens over a service window while
 * the Activity is paused (or was never started), so without this it stays invisible behind the
 * frozen capture. The host runs while **either** owner needs it and pauses only when both are gone;
 * routing both through one policy makes the resume/pause order irrelevant.
 *
 * Main thread only, like the lifecycle it mirrors.
 */
object ReactHostLifecycle {

    private val policy = ReactHostLifecyclePolicy()

    /** The shared host, application-scoped; set by whichever owner touches one first. */
    private var host: ReactHost? = null

    /** The resumed Activity, so a resume carries valid activity/back ownership; null when paused. */
    private var activity: Activity? = null

    /** From [com.gaten.app.MainActivity.onResume]; `super` has already resumed the host. */
    fun onActivityResumed(resumed: Activity) {
        host = (resumed.application as? ReactApplication)?.reactHost ?: host
        activity = resumed
        policy.onActivityResumed(resumed)
        sync()
    }

    /**
     * From [com.gaten.app.MainActivity.onPause] and `onDestroy`, after React's delegate has paused
     * the host. A stale call from an Activity another one has replaced is ignored by the policy, so
     * it cannot drop the new owner. While a sheet is open this re-resumes the host with no owner.
     */
    fun onActivityPaused(paused: Activity) {
        if (!policy.onActivityPaused(paused)) return
        activity = null
        sync()
    }

    /** A sheet is opening; called before `surface.start()` so even a cold start has the host running. */
    fun onSheetSessionOpened(reactHost: ReactHost) {
        host = reactHost
        policy.onOverlaySessionOpened()
        sync()
    }

    /** A sheet stopped and its surface is gone; pauses once the last owner leaves. */
    fun onSheetSessionClosed() {
        policy.onOverlaySessionClosed()
        sync()
    }

    /**
     * The single transition: the policy only asks for a change that is needed, so interleaved
     * resume/pause events cannot double-drive the host. The resumed Activity is handed to
     * `onHostResume` when there is one, and null for an overlay that outlives it.
     */
    private fun sync() {
        val reactHost = host ?: return
        when (policy.action(reactHost.lifecycleState == LifecycleState.RESUMED)) {
            HostLifecycleAction.Resume -> reactHost.onHostResume(activity)
            HostLifecycleAction.Pause -> reactHost.onHostPause()
            HostLifecycleAction.None -> Unit
        }
    }
}
