/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
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

/**
 * The two answers' own touch: the one cue in the app a reader feels rather than reads. A waveform
 * rather than a [HapticFeedbackConstants] tick, so a right answer and a wrong one are told apart by
 * rhythm and strength and not by one pulse's length alone. Every entry plays once (`repeat = -1`).
 */
enum class FeedbackPattern(
    internal val timings: LongArray,
    internal val amplitudes: IntArray
) {
    /** One short, light pulse: the answer landed. */
    Right(longArrayOf(0, 35), intArrayOf(0, 90)),

    /** Two heavier pulses with a gap between them: the answer did not. */
    Wrong(longArrayOf(0, 45, 70, 45), intArrayOf(0, 200, 0, 200)),
}

/** The moments in the app that carry a sound cue of their own. */
enum class FeedbackSound {
    Added,
    Done,
    AnswerRight,
    AnswerWrong,
    RateAgain,
    RateHard,
    RateGood,
    RateEasy,
    Match,
}

/**
 * What an action gives back: a haptic tick on the phone's own terms, the answers' own vibration
 * patterns ([pattern]), and — only when the reader has turned sounds on — one of its short cues.
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

    /**
     * Plays one of the answers' own patterns. This is the one path that does not go through
     * [View.performHapticFeedback], and that call's property still has to hold: a reader who turned
     * haptics off feels nothing. [Vibrator] honours no such flag, so the switch that call respected
     * is read here instead — the same [Settings.System.HAPTIC_FEEDBACK_ENABLED] the system uses —
     * and a device with no vibrator is left alone. The waveform itself is API 26+, so every level
     * this app supports can draw it.
     *
     * What this plays leaves no trace on the screen, so each request is logged at debug level with
     * the decision it ended in: a capture can then say whether the cue was asked for and whether the
     * platform was given it, which is as much as any device other than the reader's hand can tell.
     */
    fun pattern(kind: FeedbackPattern) {
        if (released) return
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
            Log.d(TAG, "Feedback pattern $kind skipped: haptics are off")
            return
        }
        val vibrator = vibrator()
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.d(TAG, "Feedback pattern $kind skipped: no vibrator")
            return
        }
        val wave = VibrationEffect.createWaveform(kind.timings, kind.amplitudes, NO_REPEAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(wave, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            // Below 33 there is no VibrationAttributes to describe the cue with; the deprecated
            // overload attributes it to this app's own sonification instead.
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                wave,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        Log.d(TAG, "Feedback pattern $kind played")
    }

    /**
     * The device's own vibrator: the manager's default one from API 31, where a device may have
     * several, and the service itself on 29–30, where there is no [VibratorManager] to ask. Null when
     * the device has none at all.
     */
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
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
     * Builds the pool and sets every cue loading; it is usable straight away, with the cues landing
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
        load(pool, FeedbackSound.AnswerRight, R.raw.feedback_answer_right)
        load(pool, FeedbackSound.AnswerWrong, R.raw.feedback_answer_wrong)
        load(pool, FeedbackSound.RateAgain, R.raw.feedback_rate_again)
        load(pool, FeedbackSound.RateHard, R.raw.feedback_rate_hard)
        load(pool, FeedbackSound.RateGood, R.raw.feedback_rate_good)
        load(pool, FeedbackSound.RateEasy, R.raw.feedback_rate_easy)
        load(pool, FeedbackSound.Match, R.raw.feedback_match)
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
        /** The waveform plays through once; the platform spells "no repeat" as -1. */
        const val NO_REPEAT = -1
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
