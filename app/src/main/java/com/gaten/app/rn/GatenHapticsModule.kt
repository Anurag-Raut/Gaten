/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.ui.Feedback
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackPreferences
import com.gaten.app.ui.FeedbackSound

/**
 * The app's touch feedback, for the RN screens.
 *
 * [Feedback] plays its haptics through the view that is on screen — the tick and the tap belong to
 * the same window — so the module holds one instance per current activity and rebuilds it when the
 * activity changes, releasing the previous one. The cues behind the sounds live in
 * [FeedbackPreferences]'s `gaten_settings`, the same file every other pref is kept in, and a
 * reader who turned sounds off never allocates the pool, exactly as [Feedback] was written to
 * behave.
 *
 * [play]'s kinds are [FeedbackHaptic]'s and [FeedbackSound]'s names, lowercased: `tick`,
 * `lookup` and `confirm` are haptics; `added` and `done` are the two sound cues.
 */
class GatenHapticsModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** The feedback instance and the activity whose window its haptics come from. */
    private var feedback: Feedback? = null
    private var owner: Activity? = null

    /**
     * Plays [kind]: a haptic for `tick`, `lookup` or `confirm`, a sound cue for `added` or `done`
     * — the sound only when the reader has sounds on, which [Feedback] checks itself. Rejects with
     * `E_FEEDBACK_KIND` for a kind that is not one of the five, and with `E_NO_WINDOW` when there
     * is no activity window to feel the haptic through.
     */
    @ReactMethod
    fun play(kind: String, promise: Promise) {
        val activity: Activity? = getCurrentActivity()
        val window = activity?.window
        if (activity == null || window == null) {
            promise.reject(CODE_NO_WINDOW, "No window to deliver feedback through")
            return
        }
        val view: android.view.View = window.decorView
        val instance = feedback?.takeIf { owner === activity } ?: Feedback(
            view,
            reactApplicationContext
        ).also {
            feedback?.release()
            feedback = it
            owner = activity
        }
        when (kind.lowercase()) {
            "tick" -> instance.haptic(FeedbackHaptic.Tick)
            "lookup" -> instance.haptic(FeedbackHaptic.Lookup)
            "confirm" -> instance.haptic(FeedbackHaptic.Confirm)
            "added" -> instance.sound(FeedbackSound.Added)
            "done" -> instance.sound(FeedbackSound.Done)
            else -> {
                promise.reject(CODE_KIND, "Unknown feedback kind '$kind'")
                return
            }
        }
        promise.resolve(true)
    }

    /** Whether the reader wants sound with their actions. */
    @ReactMethod
    fun getSoundsEnabled(promise: Promise) {
        promise.resolve(FeedbackPreferences.soundsEnabled(reactApplicationContext))
    }

    /** Stores whether the reader wants sound with their actions. */
    @ReactMethod
    fun setSoundsEnabled(enabled: Boolean, promise: Promise) {
        FeedbackPreferences.setSoundsEnabled(reactApplicationContext, enabled)
        promise.resolve(enabled)
    }

    /** Releases the held instance; called when the React context is torn down. */
    override fun invalidate() {
        feedback?.release()
        feedback = null
        owner = null
    }

    companion object {
        const val NAME = "GatenHaptics"

        private const val CODE_KIND = "E_FEEDBACK_KIND"
        private const val CODE_NO_WINDOW = "E_NO_WINDOW"
    }
}
