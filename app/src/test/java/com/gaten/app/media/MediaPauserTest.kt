/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.media

import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media pause state machine, driven through the platform seams [MediaSessions], [MediaKeys] and
 * [ManualExecutor] instead of a phone.
 *
 * The case these exist for: the pause is asked for when the frozen frame lands, and it used to be
 * cancelled by anything that ended the lookup in the same beat — a discard from the overlay's own
 * lifecycle dropped the pause before it ran and nothing was ever logged. A lookup that reached the
 * frozen frame must stop the media; only the user's own close may talk it out of that.
 */
class MediaPauserTest {

    private val playing = MediaPlayback(PlaybackState.STATE_PLAYING, 1f)
    private val paused = MediaPlayback(PlaybackState.STATE_PAUSED, 0f)

    // --- the pause commits at the frozen frame ---

    @Test
    fun pausingStopsAPlayingSessionAndResumingPlaysItBack() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause(delayMs = 100)
        fixture.runPending()

        assertEquals(1, youtube.pauseCalls)
        assertEquals(0, youtube.playCalls)

        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(1, youtube.playCalls)
    }

    @Test
    fun aDiscardWhileThePauseIsStillInFlightDoesNotCancelIt() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        // The frozen frame is on screen and the pause is armed; an automatic dismissal (a lost
        // focus, an interruption, a display change) lands before the pause has run. It must not
        // take the pause away: this is the regression - the video played on under the overlay.
        fixture.pauser.pause(delayMs = 100)
        fixture.pauser.discard()
        fixture.runPending()

        assertEquals(1, youtube.pauseCalls)
    }

    @Test
    fun anAbandonedPauseStopsTheMediaButRecordsNothingToStartAgain() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause(delayMs = 100)
        fixture.pauser.discard()
        fixture.runPending()

        // Nothing is played back for the abandoned lookup: the media is left paused for whatever
        // took the screen, exactly as it is when the pause had already landed.
        assertEquals(1, youtube.pauseCalls)
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(0, youtube.playCalls)
    }

    @Test
    fun aLookupAfterAnInterruptionStillStopsAndPlaysBack() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause(delayMs = 100)
        fixture.pauser.discard()
        fixture.runPending()
        assertEquals(1, youtube.pauseCalls)

        // The cycle after an interruption has to record what it stopped as usual, or the media would
        // be left stopped with nothing to play it back.
        fixture.pauser.pause()
        fixture.runPending()
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(2, youtube.pauseCalls)
        assertEquals(1, youtube.playCalls)
    }

    @Test
    fun theUsersOwnCloseBeforeThePauseFiresCancelsIt() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        // A close within the freeze's pause delay: the media must not stop after the overlay is gone.
        fixture.pauser.pause(delayMs = 100)
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(0, youtube.pauseCalls)
        assertEquals(0, youtube.playCalls)
    }

    @Test
    fun aDiscardStillCancelsAResumeTheUsersCloseAlreadyQueued() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause()
        fixture.runPending()
        assertEquals(1, youtube.pauseCalls)

        // The reader closed the overlay (playback owed) and then a call took the screen. The resume
        // must not land: the playback is left for the call to deal with.
        fixture.pauser.resume()
        fixture.pauser.discard()
        fixture.runPending()

        assertEquals(0, youtube.playCalls)
    }

    // --- what resume may and may not start ---

    @Test
    fun aSessionTheUserHadPausedIsNeitherStoppedNorStarted() {
        val photos = FakeSession("com.google.android.apps.photos", paused)
        val fixture = Fixture(listOf(photos))

        fixture.pauser.pause()
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(0, photos.pauseCalls)
        assertEquals(0, photos.playCalls)
    }

    @Test
    fun theStateStoredForResumeIsTheOneReadBeforeThePause() {
        // A player that lands in PAUSED the moment the pause is dispatched — the state a post-pause
        // read would have stored, and a state the resume gate would refuse, leaving the video stopped
        // for good after the overlay closed.
        val youtube = FakeSession("com.google.android.youtube", playing).apply {
            stateAfterPause = paused
        }
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause()
        fixture.runPending()
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(1, youtube.pauseCalls)
        assertEquals(1, youtube.playCalls)
    }

    @Test
    fun aSessionCaughtWhileBufferingIsPlayedBack() {
        val spotify = FakeSession("com.spotify.music", MediaPlayback(PlaybackState.STATE_BUFFERING, 1f))
        val fixture = Fixture(listOf(spotify))

        fixture.pauser.pause()
        fixture.runPending()
        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(1, spotify.pauseCalls)
        assertEquals(1, spotify.playCalls)
    }

    @Test
    fun aSessionWithNoReportedStateIsNotTouched() {
        val silent = FakeSession("com.example.silent", null)
        val fixture = Fixture(listOf(silent))

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(0, silent.pauseCalls)
    }

    @Test
    fun aSessionIsStoppedAtMostOncePerCycle() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause()
        fixture.runPending()
        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(1, youtube.pauseCalls)
    }

    // --- one bad session must not take the rest with it ---

    @Test
    fun aSessionThatThrowsOnPauseDoesNotStopTheOthers() {
        val broken = FakeSession("com.example.broken", playing).apply { pauseThrows = true }
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(broken, youtube))

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(1, youtube.pauseCalls)
    }

    @Test
    fun aSessionThatThrowsWhileItsStateIsReadIsLeftAlone() {
        val broken = FakeSession("com.example.broken", playing).apply { stateReadThrows = true }
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(broken, youtube))

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(0, broken.pauseCalls)
        assertEquals(1, youtube.pauseCalls)
        assertEquals(0, broken.playCalls)
    }

    @Test
    fun aPlayerThatThrowsOnResumeDoesNotLockOutLaterPauses() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause()
        fixture.runPending()
        youtube.playThrows = true
        fixture.pauser.resume()
        fixture.runPending()

        // The record has to be dropped even though the player threw: left behind it holds the pause
        // cycle open for the life of the service, and every later pause returns at the guard below.
        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(2, youtube.pauseCalls)
    }

    // --- the media-key fallback ---

    @Test
    fun withoutNotificationAccessTheKeyEventsAreThePauseAndThePlay() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube), access = false)

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), fixture.keys.dispatched)
        assertEquals(0, youtube.pauseCalls)

        fixture.pauser.resume()
        fixture.runPending()

        assertEquals(
            listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY),
            fixture.keys.dispatched
        )
    }

    @Test
    fun aDeniedSessionLookupFallsBackToTheKeyEvent() {
        val fixture = Fixture(listOf(FakeSession("com.google.android.youtube", playing)))
        fixture.sessions.denied = true

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), fixture.keys.dispatched)
    }

    @Test
    fun aDeadSessionServiceFallsBackToTheKeyEvent() {
        val fixture = Fixture(listOf(FakeSession("com.google.android.youtube", playing)))
        fixture.sessions.broken = RuntimeException("media session service is gone")

        fixture.pauser.pause()
        fixture.runPending()

        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), fixture.keys.dispatched)
    }

    @Test
    fun theKeyEventOnlyFiresWhenNothingWasPausableAndMusicIsPlaying() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val withSession = Fixture(listOf(youtube), musicActive = true)
        withSession.pauser.pause()
        withSession.runPending()
        assertTrue(withSession.keys.dispatched.isEmpty())

        val silent = Fixture(emptyList(), musicActive = false)
        silent.pauser.pause()
        silent.runPending()
        assertTrue(silent.keys.dispatched.isEmpty())

        val sessionless = Fixture(emptyList(), musicActive = true)
        sessionless.pauser.pause()
        sessionless.runPending()
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), sessionless.keys.dispatched)
    }

    // --- teardown ---

    @Test
    fun releaseDropsAPauseThatHasNotRun() {
        val youtube = FakeSession("com.google.android.youtube", playing)
        val fixture = Fixture(listOf(youtube))

        fixture.pauser.pause(delayMs = 100)
        fixture.pauser.release()
        fixture.runPending()

        assertEquals(0, youtube.pauseCalls)
    }

    // --- the seams the state machine runs through ---

    private class Fixture(
        sessionsList: List<MediaSessionHandle> = emptyList(),
        access: Boolean = true,
        musicActive: Boolean = false
    ) {
        val executor = ManualExecutor()
        val sessions = FakeSessions(sessionsList)
        val keys = FakeKeys(musicActive)
        val pauser = MediaPauser({ access }, sessions, keys, executor)

        fun runPending() = executor.runPending()
    }

    private class FakeSessions(private val list: List<MediaSessionHandle>) : MediaSessions {
        var denied = false
        var broken: RuntimeException? = null

        override fun active(): List<MediaSessionHandle> {
            broken?.let { throw it }
            if (denied) throw SecurityException("no listener access")
            return list
        }
    }

    private class FakeKeys(private val musicActive: Boolean) : MediaKeys {
        val dispatched = mutableListOf<Int>()

        override fun dispatch(keyCode: Int) {
            dispatched += keyCode
        }

        override fun isMusicActive(): Boolean = musicActive
    }

    private class FakeSession(
        override val packageName: String,
        private var state: MediaPlayback?
    ) : MediaSessionHandle {

        var pauseCalls = 0
            private set
        var playCalls = 0
            private set
        var pauseThrows = false
        var playThrows = false
        var stateReadThrows = false

        /** What the player reports once the pause has been dispatched, if it reports one at all. */
        var stateAfterPause: MediaPlayback? = null

        override val playbackState: MediaPlayback?
            get() {
                if (stateReadThrows) throw IllegalStateException("the session is gone")
                return state
            }

        override fun pause() {
            pauseCalls++
            if (pauseThrows) throw IllegalStateException("the session is gone")
            stateAfterPause?.let { state = it }
        }

        override fun play() {
            playCalls++
            if (playThrows) throw IllegalStateException("the session is gone")
        }
    }
}
