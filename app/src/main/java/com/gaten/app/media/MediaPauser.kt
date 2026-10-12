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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
 * Work runs on a private thread: [getActiveSessions] is a binder call and the overlay shows on the
 * main thread. Both calls share one executor, so a queued resume always runs before a later pause.
 */
class MediaPauser(private val context: Context) {

    private val listener = ComponentName(context, MediaNotificationListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Sessions [pause] stopped, with the state each was in, so [resume] restores only those. */
    private val paused = mutableListOf<Pair<MediaController, Int>>()

    /** Set when [pause] had no session list and fell back to a media key event. */
    private var pausedByKeyEvent = false

    /** Pauses the media playing now, remembering it for [resume]. Safe to call with nothing playing. */
    fun pause() = executor.execute(::pauseNow)

    /** Plays back only what [pause] stopped. A no-op when nothing was paused. */
    fun resume() = executor.execute(::resumeNow)

    private fun pauseNow() {
        if (paused.isNotEmpty() || pausedByKeyEvent) return

        if (!hasNotificationAccess(context)) {
            Log.d(TAG, "No notification access - pausing with a media key event instead")
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKeyEvent = true
            return
        }

        val active = try {
            sessions.getActiveSessions(listener)
        } catch (e: SecurityException) {
            // Access can be revoked between the check above and the call.
            Log.w(TAG, "Media session access denied - pausing with a media key event instead", e)
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKeyEvent = true
            return
        }

        for (controller in active) {
            if (!isPlaying(controller)) continue
            controller.transportControls.pause()
            paused += controller to (controller.playbackState?.state ?: PlaybackState.STATE_NONE)
        }
        Log.d(TAG, "Paused ${paused.size} of ${active.size} media sessions")
    }

    private fun resumeNow() {
        if (pausedByKeyEvent) {
            pausedByKeyEvent = false
            dispatchKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        for ((controller, state) in paused) {
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
