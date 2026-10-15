/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Gaten"

/**
 * True when the user has granted Gaten notification-listener access in system settings.
 *
 * That grant is what lets [MediaPauser] see the active media sessions; it is the same switch the
 * app offers on its home screen.
 */
fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * The state half of [PlaybackState], read once per session and kept instead of the controller's live
 * state: it is what [MediaPauser] decides on and what it restores by, so the decision and the restore
 * can never disagree. Android-free so the state machine can be unit-tested.
 */
internal data class MediaPlayback(val state: Int, val speed: Float)

/**
 * One media session, as far as pausing and resuming are concerned.
 *
 * [playbackState] must be read fresh and may throw: the session's process can be gone between the
 * moment the platform listed it and the moment it is used.
 */
internal interface MediaSessionHandle {
    val packageName: String
    val playbackState: MediaPlayback?
    fun pause()
    fun play()
}

/**
 * The platform's active-session list. [active] throws [SecurityException] when notification-listener
 * access is missing or was revoked.
 */
internal interface MediaSessions {
    fun active(): List<MediaSessionHandle>
}

/** The media-button fallback used when no session can be paused: one key press (a DOWN and an UP). */
internal interface MediaKeys {
    fun dispatch(keyCode: Int)
    fun isMusicActive(): Boolean
}

/**
 * Pauses the media playing behind the lookup overlay, and resumes exactly that playback after.
 *
 * The overlay paints a frozen screenshot in a window that never touches the activity stack, so the
 * app behind keeps playing — a video advancing under a still picture is what this exists to stop.
 * The overlay draws the media pause and the media play, so the two are always paired.
 *
 * Pausing is by media session ([MediaSessions.active]), which needs the notification-listener access
 * [hasNotificationAccess] reports; only sessions that are actually playing are touched. Resume plays
 * back only the sessions this instance paused, and only when they were playing at the time, so a
 * session the user had paused themselves is never started.
 *
 * **A pause, once armed, is committed.** [pause] is called the moment the frozen frame is on screen,
 * so from that instant the lookup has asked for the pause and nothing that later ends the lookup
 * un-asks it: [discard] — the path every automatic dismissal takes — drops the record of what to play
 * back, leaving the media paused for whatever took the screen, and [pauseNow] runs to completion even
 * when the lookup it belonged to is already gone. Only a resume — the user closing the overlay —
 * cancels a pause that has not fired yet, which is what keeps a close from stopping the media after
 * the overlay is gone. [release] tears the worker thread down, dropping anything still queued with it.
 *
 * Without that access the session list is unavailable, so [pause] falls back to dispatching a media
 * pause key event and [resume] a media play one. `AudioManager.dispatchMediaKeyEvent` is public
 * API (since API 19) with no permission requirement — not annotated `@RequiresPermission` in the
 * SDK, and the platform's `MediaSessionService.dispatchMediaKeyEvent` enforces none — so a plain
 * app can send the same key the hardware button would.
 *
 * A lookup can also end without the user asking: the screen goes off, a call arrives, the overlay
 * loses its window. Those go through [discard] instead of [resume] — nothing is played back, and
 * every trace of the pause is dropped, so a later lookup can never resume a call or a session it did
 * not pause.
 *
 * Work runs on a private thread: the session list is a binder call and the overlay shows on the main
 * thread. Both calls share one executor, so a queued resume always runs before a later pause.
 *
 * [hasAccess], [sessions] and [audio] are the platform seams, so the state machine below is testable
 * without a device; [MediaPauser] itself builds the real ones.
 */
class MediaPauser internal constructor(
    private val hasAccess: () -> Boolean,
    private val sessions: MediaSessions,
    private val audio: MediaKeys,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) {

    constructor(context: Context) : this(
        hasAccess = { hasNotificationAccess(context) },
        sessions = AndroidMediaSessions(context),
        audio = AndroidMediaKeys(context)
    )

    /** The delayed pause not yet run, which [resume] cancels; touched on the caller's thread only. */
    private var pendingPause: ScheduledFuture<*>? = null

    /**
     * The resume cycle's identity. [discard] and [release] move it on, so a resume that a manual
     * close already queued never plays after something took the screen — and so a pause that lands
     * after the lookup was abandoned records nothing that a later lookup could play back.
     */
    private val generation = AtomicLong(0L)

    /** Sessions [pause] stopped, with the playing state each was in, so [resume] restores those. */
    private val paused = mutableListOf<Pair<MediaSessionHandle, MediaPlayback>>()

    /** Set when [pause] had no session list and fell back to a media key event. */
    private var pausedByKeyEvent = false

    /**
     * Pauses the media playing now, remembering it for [resume]. Safe to call with nothing playing.
     *
     * The session lookup and the transport calls run on the private executor; nothing is reported
     * back. The lookup overlay already shows a frozen screenshot by the time this is called, so
     * nobody waits for the player to actually stop. [delayMs] holds the pause back on that executor,
     * off the caller's thread, so a busy main thread cannot hold it back further.
     *
     * The resume cycle this pause belongs to is taken **now**, not when the task finally runs: what
     * the pause records must be decided by the cycle that asked for it, not by whatever cycle happens
     * to be current by then.
     */
    fun pause(delayMs: Long = 0) {
        val token = generation.get()
        val requestedNanos = System.nanoTime()
        pendingPause = executor.schedule(
            { pauseNow(token, requestedNanos) },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Plays back only what [pause] stopped. A no-op when nothing was paused; a pause still waiting
     * out its delay is cancelled instead, so it cannot stop the media after this.
     */
    fun resume() {
        val token = generation.get()
        pendingPause?.cancel(false)
        pendingPause = null
        executor.execute { resumeNow(token) }
    }

    /**
     * Gives up the lookup without playing anything back.
     *
     * A pause already armed still lands: what the frozen frame asked for stands, and stopping the
     * media for the call, the lock or the dialog that took the screen is what the reader hears
     * anyway when the pause had already been dispatched. What goes is the *record* — nothing will be
     * played back later, and a later lookup can never resume a player it did not pause. The resume
     * cycle moves on too, so a resume a manual close already queued cannot play after this.
     *
     * The armed task is deliberately left alone rather than cancelled: cancelling it here is what
     * let an automatic dismissal land in the same beat as the frozen frame and silently drop the
     * pause, leaving the video playing under an overlay that had already asked for it to stop.
     */
    fun discard() {
        generation.incrementAndGet()
        pendingPause = null
        executor.execute { forget() }
    }

    /** Stops the worker thread once the service is gone; queued work is dropped with it. */
    fun release() {
        pendingPause?.cancel(false)
        pendingPause = null
        generation.incrementAndGet()
        // shutdownNow rather than shutdown: the service is gone, so nothing queued may still reach a
        // player through a controller it kept.
        executor.shutdownNow()
    }

    /** Drops everything this instance would play back; whatever is playing is left alone. */
    private fun forget() {
        paused.clear()
        pausedByKeyEvent = false
    }

    /**
     * Pauses active playback for the cycle that asked for it, running to completion once it starts.
     *
     * [token] is read as the resume cycle the pause was armed under: when it has moved on, the lookup
     * that wanted this pause is over, so the pause still stops the media (the reader must not hear it
     * play on under a call) but records nothing for a later resume to start again.
     */
    private fun pauseNow(token: Long, requestedNanos: Long) {
        val abandoned = token != generation.get()
        Log.d(
            TAG,
            "Media pause ran ${(System.nanoTime() - requestedNanos) / 1_000_000} ms after it was" +
                " requested" + if (abandoned) " (the lookup it belonged to was abandoned)" else ""
        )

        if (paused.isNotEmpty() || pausedByKeyEvent) {
            Log.d(
                TAG,
                "Media pause skipped: the previous cycle still holds" +
                    " (${paused.size} session(s), keyEvent=$pausedByKeyEvent)"
            )
            return
        }

        if (!hasAccess()) {
            Log.d(TAG, "No notification access - pausing with a media key event instead")
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            if (!abandoned) pausedByKeyEvent = true
            return
        }

        val active = try {
            sessions.active()
        } catch (e: SecurityException) {
            // Access can be revoked between the check above and the call.
            Log.w(TAG, "Media session access denied - pausing with a media key event instead", e)
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            if (!abandoned) pausedByKeyEvent = true
            return
        } catch (e: RuntimeException) {
            // The media-session service itself can be gone; the key event is the way out of that too,
            // and without this the whole pause would die silently inside the executor.
            Log.w(TAG, "Media sessions could not be read - pausing with a media key event instead", e)
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            if (!abandoned) pausedByKeyEvent = true
            return
        }

        // The packages alone: each session's state is read once, below, where a failed read can be
        // caught and reported for that session instead of ending the whole pause.
        Log.d(
            TAG,
            "Media sessions seen: " +
                if (active.isEmpty()) "none" else active.joinToString { it.packageName }
        )

        // One session at a time, and one that fails is left behind rather than taking the rest with
        // it: a stale controller in the list used to end this loop silently, so every session after
        // it — YouTube included — was never paused and no line was ever logged.
        for (controller in active) {
            val state = try {
                controller.playbackState
            } catch (e: RuntimeException) {
                Log.w(TAG, "Media state read failed for ${controller.packageName}" +
                    " - leaving it alone", e)
                continue
            }
            if (state == null || !isPlaying(state)) {
                Log.d(TAG, "Not playing: ${describe(controller, state)}")
                continue
            }
            try {
                controller.pause()
                Log.d(TAG, "Media pause dispatched to ${controller.packageName}" +
                    " (was ${stateName(state.state)})")
                // The state read above — before the pause was dispatched — is what resume restores
                // by; re-reading after the call would race the player's own state update.
                if (!abandoned) paused += controller to state
            } catch (e: RuntimeException) {
                Log.w(TAG, "Media pause failed for ${controller.packageName}" +
                    " - continuing with the rest", e)
            }
        }

        // A player can be playing without exposing a usable media session — Google Photos' does not
        // register one at all — so nothing above can see it. `isMusicActive` is the platform's own
        // "something is coming out of the music stream" flag; when it is set and no session was
        // pausable, send the same key a headset button would. It stays quiet when nothing plays, so
        // an overlay over silence never queues a play() for the way out.
        val musicActive = audio.isMusicActive()
        if (paused.isEmpty() && musicActive) {
            Log.d(TAG, "No pausable media session - pausing with a media key event instead")
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            if (!abandoned) pausedByKeyEvent = true
        }
        Log.d(
            TAG,
            "Media pause done: paused ${paused.size} of ${active.size} session(s)" +
                ", musicActive=$musicActive" + if (abandoned) ", nothing recorded (abandoned)" else ""
        )
    }

    /** Restores only this cycle's paused playback, rechecking cancellation before each player. */
    private fun resumeNow(token: Long) {
        try {
            // A discard in between dropped this pause: playing now would start a player the lookup
            // that asked for the pause no longer owns.
            if (token != generation.get()) {
                Log.d(TAG, "Media resume abandoned - the pause it belonged to was dropped")
                return
            }
            if (pausedByKeyEvent) {
                Log.d(TAG, "Media resume: sending a media play key event")
                dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                return
            }
            if (paused.isEmpty()) {
                Log.d(TAG, "Media resume: nothing was paused by this cycle")
                return
            }
            for ((controller, state) in paused) {
                // A discard that raced the collection stops the restore here, before it plays anything.
                if (token != generation.get()) {
                    Log.d(TAG, "Media resume abandoned while restoring sessions")
                    return
                }
                try {
                    // The same predicate that let this session be paused decides whether it is played
                    // back, so the two sides can never disagree: a session the user had paused
                    // themselves was never in this list, and one we stopped while it was buffering is
                    // restored the way it was found.
                    if (isPlaying(state)) {
                        controller.play()
                        Log.d(TAG, "Media resume: played back ${controller.packageName}" +
                            " (was ${stateName(state.state)})")
                    } else {
                        Log.d(TAG, "Media resume: leaving ${controller.packageName}" +
                            " (was ${stateName(state.state)})")
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Media resume failed for ${controller.packageName}", e)
                }
            }
        } finally {
            // Unconditional: a player that throws must never leave this list holding the pause cycle
            // open, or every later pause is refused by the guard at the top of pauseNow — silently,
            // for the rest of the service's life.
            forget()
        }
    }

    /**
     * The states a session is playing in — the platform's own "active", minus the seek states.
     * Some players keep STATE_NONE while playing and say it only through the speed (Google Photos'
     * does on the emulator); a paused or idle session reports speed 0, so that check cannot start
     * one the user had stopped.
     */
    private fun isPlaying(state: MediaPlayback): Boolean {
        return when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            PlaybackState.STATE_NONE -> state.speed > 0f
            else -> false
        }
    }

    /** One session as the log shows it: package, state and speed, which is what tells them apart. */
    private fun describe(controller: MediaSessionHandle, state: MediaPlayback?): String =
        "${controller.packageName}=${
            state?.let { "${stateName(it.state)}/speed ${it.speed}" } ?: "no state"
        }"

    /** The state's name, for the log only. The constants are inlined by the compiler, so this is safe off-device. */
    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "STATE_NONE"
        PlaybackState.STATE_STOPPED -> "STATE_STOPPED"
        PlaybackState.STATE_PAUSED -> "STATE_PAUSED"
        PlaybackState.STATE_PLAYING -> "STATE_PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "STATE_REWINDING"
        PlaybackState.STATE_BUFFERING -> "STATE_BUFFERING"
        PlaybackState.STATE_ERROR -> "STATE_ERROR"
        PlaybackState.STATE_CONNECTING -> "STATE_CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "STATE_SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "STATE_SKIPPING_TO_QUEUE_ITEM"
        else -> state.toString()
    }

    /** Sends one media key press — a DOWN then an UP — the way a hardware media key would. */
    private fun dispatchKeyEvent(keyCode: Int) {
        audio.dispatch(keyCode)
    }
}

/** The platform's active sessions, backed by [MediaSessionManager] and the listener component. */
internal class AndroidMediaSessions(context: Context) : MediaSessions {

    private val listener = ComponentName(context, MediaNotificationListener::class.java)
    private val manager = context.getSystemService(MediaSessionManager::class.java)

    override fun active(): List<MediaSessionHandle> = manager.getActiveSessions(listener).map(::Handle)

    /** A live [MediaController]; every call goes through it, so it fails the way it really fails. */
    private class Handle(private val controller: MediaController) : MediaSessionHandle {

        override val packageName: String
            get() = controller.packageName

        override val playbackState: MediaPlayback?
            get() = controller.playbackState?.let { MediaPlayback(it.state, it.playbackSpeed) }

        override fun pause() = controller.transportControls.pause()

        override fun play() = controller.transportControls.play()
    }
}

/** The media-button/key fallback, backed by [AudioManager]. */
internal class AndroidMediaKeys(context: Context) : MediaKeys {

    private val audio = context.getSystemService(AudioManager::class.java)

    override fun dispatch(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun isMusicActive(): Boolean = audio.isMusicActive()
}
