/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.gaten.app.R

/** How firm a touch's feedback is: a step, a dictionary hit, or an action that landed. */
enum class FeedbackHaptic { Tick, Lookup, Confirm }

/** The two moments in the app that carry a cue of their own. */
enum class FeedbackSound { Added, Done }

/**
 * What an action gives back: a haptic tick on the phone's own terms, and — only when the reader has
 * turned sounds on — one of two short cues.
 *
 * One instance belongs to one composition and owns the single [SoundPool] behind it, built on the
 * first sound actually asked for: a reader who leaves sounds off never allocates one. Cues load
 * asynchronously, so the first request of a session is queued rather than dropped. Release the
 * instance through its composition ([rememberFeedback]); a released instance ignores what it is
 * told, so a callback that arrives after disposal cannot play.
 */
class Feedback internal constructor(
    private val view: View,
    private val context: Context
) {
    private var pool: SoundPool? = null
    private val idOf = HashMap<FeedbackSound, Int>()
    private val kindOf = HashMap<Int, FeedbackSound>()
    private val loaded = HashSet<FeedbackSound>()
    private var queued: FeedbackSound? = null
    private var released = false

    /**
     * One tick from the phone at the weight [kind] asks for. [View.performHapticFeedback] is what
     * respects the phone's own haptic settings — no `FLAG_IGNORE_GLOBAL_SETTING` variant is used,
     * so a reader who turned haptics off gets none of this.
     */
    fun haptic(kind: FeedbackHaptic) {
        if (released) return
        view.performHapticFeedback(
            when (kind) {
                FeedbackHaptic.Tick -> HapticFeedbackConstants.CONTEXT_CLICK
                FeedbackHaptic.Lookup -> HapticFeedbackConstants.CLOCK_TICK
                FeedbackHaptic.Confirm -> HapticFeedbackConstants.LONG_PRESS
            }
        )
    }

    /** Plays [kind]'s cue if the reader has sounds on. */
    fun sound(kind: FeedbackSound) {
        if (released || !FeedbackPreferences.soundsEnabled(context)) return
        val pool = pool ?: buildPool()
        if (kind !in loaded) {
            queued = kind
            return
        }
        play(pool, kind)
    }

    /** Closes the pool. Idempotent, and the composition's `onDispose` is the only caller. */
    fun release() {
        released = true
        queued = null
        loaded.clear()
        idOf.clear()
        kindOf.clear()
        pool?.release()
        pool = null
    }

    /**
     * Builds the pool and sets both cues loading; it is usable straight away, with the cues landing
     * in [loaded] as they arrive.
     *
     * The load callback plays a queued cue and re-reads the preference first: sounds can be turned
     * off while a file is still loading, and that choice is the reader's, not the request's.
     */
    private fun buildPool(): SoundPool {
        val pool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool.setOnLoadCompleteListener { _, soundId, status ->
            val kind = kindOf[soundId]
            if (released || kind == null) return@setOnLoadCompleteListener
            if (status != 0) {
                Log.w(TAG, "Feedback sound $kind did not load (status $status)")
                return@setOnLoadCompleteListener
            }
            loaded += kind
            if (queued == kind) {
                queued = null
                if (FeedbackPreferences.soundsEnabled(context)) play(pool, kind)
            }
        }
        load(pool, FeedbackSound.Added, R.raw.feedback_added)
        load(pool, FeedbackSound.Done, R.raw.feedback_done)
        this.pool = pool
        return pool
    }

    /** Starts one cue loading; a resource that cannot be opened is reported, never silently lost. */
    private fun load(pool: SoundPool, kind: FeedbackSound, res: Int) {
        val id = pool.load(context, res, 1)
        if (id == 0) {
            Log.w(TAG, "Feedback sound $kind could not be opened")
            return
        }
        idOf[kind] = id
        kindOf[id] = kind
    }

    /**
     * Starts a cue; a play the pool refuses — an exhausted stream budget, a cue not ready yet — is
     * reported rather than passed off as sound.
     */
    private fun play(pool: SoundPool, kind: FeedbackSound) {
        val id = idOf[kind] ?: return
        if (pool.play(id, VOLUME, VOLUME, PRIORITY, 0, 1f) == 0) {
            Log.w(TAG, "Feedback sound $kind did not play")
        }
    }

    private companion object {
        const val TAG = "Feedback"
        const val MAX_STREAMS = 2
        /** A cue, not an alert: it sits under the reader's own audio instead of over it. */
        const val VOLUME = 0.8f
        const val PRIORITY = 1
    }
}

/**
 * The composition's own [Feedback], released with it.
 *
 * Haptics come from the view that is on screen, so the tick and the tap that caused it belong to the
 * same window.
 */
@Composable
fun rememberFeedback(): Feedback {
    val view = LocalView.current
    val context = LocalContext.current.applicationContext
    val feedback = remember(view, context) { Feedback(view, context) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    return feedback
}

/**
 * Whether the reader wants sound with their actions — off until they say otherwise — kept in the
 * app's single settings file, with the theme and trigger choices.
 */
object FeedbackPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_SOUNDS = "feedback_sounds"

    fun soundsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SOUNDS, false)

    fun setSoundsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUNDS, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
