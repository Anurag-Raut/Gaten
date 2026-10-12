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
 * Pauses the media playing behind the lookup overlay, and resumes exactly that playback after.
 *
 * The overlay paints a frozen screenshot in a window that never touches the activity stack, so the
 * app behind keeps playing — a video advancing under a still picture is what this exists to stop.
 * The overlay draws the media pause and the media play, so the two are always paired.
 *
 * Pausing is by media session ([MediaSessionManager.getActiveSessions]), which needs the
 * notification-listener access [hasNotificationAccess] reports; only sessions that are actually
 * playing are touched. Resume plays back only the sessions this instance paused, and only when they
 * were PLAYING at the time, so a session the user had paused themselves is never started.
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
 * not pause. A scheduled pause that [discard] beats still runs on the executor; [generation] makes
 * it notice that its work was abandoned and return without touching a player.
 *
 * Work runs on a private thread: [getActiveSessions] is a binder call and the overlay shows on the
 * main thread. Both calls share one executor, so a queued resume always runs before a later pause.
 */
class MediaPauser(private val context: Context) {

    private val listener = ComponentName(context, MediaNotificationListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** The delayed pause not yet run, which [resume] cancels; touched on the caller's thread only. */
    private var pendingPause: ScheduledFuture<*>? = null

    /**
     * The pause cycle in flight. [discard] and the executor's own work move it on, so a pause
     * already running when the lookup is abandoned notices at its next step and stops, and a resume
     * aimed at a dropped pause plays nothing.
     */
    private val generation = AtomicLong(0L)

    /** Sessions [pause] stopped, with the state each was in, so [resume] restores only those. */
    private val paused = mutableListOf<Pair<MediaController, Int>>()

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
     * The generation the task belongs to is taken **now**, not when the task finally runs: an
     * abandoned pause must never adopt whatever cycle happens to be current by then.
     */
    fun pause(delayMs: Long = 0) {
        val token = generation.get()
        pendingPause = executor.schedule({ pauseNow(token) }, delayMs, TimeUnit.MILLISECONDS)
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
     * Gives up a pause without playing anything back: the delayed pause is cancelled and the
     * sessions this instance holds are forgotten. A lookup ended by the screen going off, a call or
     * an alarm leaves that playback for the call — or for the user — to deal with, and a lookup that
     * starts later can never resume a player it did not pause.
     *
     * Clearing happens on the same serial executor as the work it cancels, so a pause already
     * running sees the new generation at its next check and stops, and the state it might have left
     * behind is cleared after it.
     */
    fun discard() {
        pendingPause?.cancel(false)
        pendingPause = null
        // Moving the generation on abandons the work a cancelled task would have done, and makes any
        // pause already on the executor stop at its next step rather than touch a player.
        generation.incrementAndGet()
        executor.execute {
            paused.clear()
            pausedByKeyEvent = false
        }
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

    /** Pauses active playback for this cycle, stopping before further effects if it was discarded. */
    private fun pauseNow(token: Long) {
        if (paused.isNotEmpty() || pausedByKeyEvent) {
            return
        }
        // The lookup this pause belonged to may already have ended (a call, the screen going off).
        if (token != generation.get()) return

        if (!hasNotificationAccess(context)) {
            Log.d(TAG, "No notification access - pausing with a media key event instead")
            if (token != generation.get()) return
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKeyEvent = true
            return
        }

        val active = try {
            sessions.getActiveSessions(listener)
        } catch (e: SecurityException) {
            // Access can be revoked between the check above and the call.
            Log.w(TAG, "Media session access denied - pausing with a media key event instead", e)
            if (token != generation.get()) return
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKeyEvent = true
            return
        }

        for (controller in active) {
            if (!isPlaying(controller)) continue
            // The lookup this pause belonged to may have ended while the sessions were collected.
            if (token != generation.get()) {
                Log.d(TAG, "Media pause abandoned while collecting sessions - pausing nothing")
                return
            }
            controller.transportControls.pause()
            paused += controller to (controller.playbackState?.state ?: PlaybackState.STATE_NONE)
        }

        // A player can be playing without exposing a usable media session — Google Photos' does not
        // register one at all — so nothing above can see it. `isMusicActive` is the platform's own
        // "something is coming out of the music stream" flag; when it is set and no session was
        // pausable, send the same key a headset button would. It stays quiet when nothing plays, so
        // an overlay over silence never queues a play() for the way out.
        if (paused.isEmpty() && audio.isMusicActive() && token == generation.get()) {
            Log.d(TAG, "No pausable media session - pausing with a media key event instead")
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKeyEvent = true
        }
        Log.d(TAG, "Paused ${paused.size} of ${active.size} media sessions")
    }

    /** Restores only this cycle's paused playback, rechecking cancellation before each player. */
    private fun resumeNow(token: Long) {
        // A discard in between dropped this pause: playing now would start a player the lookup that
        // asked for the pause no longer owns.
        if (token != generation.get()) {
            Log.d(TAG, "Media resume abandoned - the pause it belonged to was dropped")
            paused.clear()
            pausedByKeyEvent = false
            return
        }
        if (pausedByKeyEvent) {
            pausedByKeyEvent = false
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        for ((controller, state) in paused) {
            // A discard that raced the collection stops the restore here, before it plays anything.
            if (token != generation.get()) {
                Log.d(TAG, "Media resume abandoned while restoring sessions")
                paused.clear()
                return
            }
            // PLAYING is restored, and NONE too — it only got here through the speed check below,
            // so it was playing. A session the user had paused themselves was never in this list,
            // and playing back a session caught while buffering or connecting could start playback
            // that had not begun.
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_NONE) {
                controller.transportControls.play()
            }
        }
        paused.clear()
    }

    /**
     * The states a session is playing in — the platform's own "active", minus the seek states.
     * Some players keep STATE_NONE while playing and say it only through the speed (Google Photos'
     * does on the emulator); a paused or idle session reports speed 0, so that check cannot start
     * one the user had stopped.
     */
    private fun isPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState ?: return false
        return when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            PlaybackState.STATE_NONE -> state.playbackSpeed > 0f
            else -> false
        }
    }

    /** Sends one media key press — a DOWN then an UP — the way a hardware media key would. */
    private fun dispatchKeyEvent(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
