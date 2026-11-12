/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.gaten.app.ocr.PpOcrModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The codes a failed download is handed to JS, and the rate at which a running one reports.
 *
 * Both are pure: the classifier reads the failure, and the throttle reads an injected clock, so a
 * JVM test pins them without a device.
 */
class DownloadProgressTest {

    // ---- DownloadFailure.code: one code per reason, exactly what the JS contract names. ----

    @Test
    fun `a pack failure is the generic code`() {
        val error = PpOcrModel.DownloadException("Gaten was not installed by Google Play")
        assertEquals(DownloadFailure.GENERIC, DownloadFailure.code(error))
    }

    @Test
    fun `a bare IOException is offline`() {
        assertEquals(
            DownloadFailure.OFFLINE,
            DownloadFailure.code(IOException("unexpected end of stream"))
        )
    }

    @Test
    fun `an unknown failure is the generic code`() {
        assertEquals(DownloadFailure.GENERIC, DownloadFailure.code(IllegalStateException("boom")))
    }

    // ---- DownloadFailure.isOutOfSpace: recognized by message, at any depth of the cause chain. ----

    @Test
    fun `an ENOSPC message anywhere in the chain is no space`() {
        assertEquals(
            DownloadFailure.NO_SPACE,
            DownloadFailure.code(IOException("write failed: ENOSPC (No space left on device)"))
        )
        val wrapped = PpOcrModel.DownloadException(
            "Could not write the revision marker",
            IOException("No space left on device")
        )
        assertEquals(DownloadFailure.NO_SPACE, DownloadFailure.code(wrapped))
        val deep = RuntimeException("outer", IllegalStateException("inner", IOException("ENOSPC")))
        assertEquals(DownloadFailure.NO_SPACE, DownloadFailure.code(deep))
    }

    @Test
    fun `a message that merely mentions space is not no space`() {
        assertEquals(
            DownloadFailure.OFFLINE,
            DownloadFailure.code(IOException("there is space between the packets"))
        )
    }

    // ---- DownloadProgressThrottle: five a second, one verifying, monotonic, silent after stop. ----

    private class Recorder {
        val phases = mutableListOf<String>()
        val done = mutableListOf<Long>()
        val totals = mutableListOf<Long>()
    }

    private fun throttle(now: () -> Long, seen: Recorder, intervalMs: Long = 200L) =
        DownloadProgressThrottle(intervalMs, now) { phase, bytesDone, bytesTotal ->
            seen.phases += phase
            seen.done += bytesDone
            seen.totals += bytesTotal
        }

    @Test
    fun `the first tick reports at once and later ones wait for the interval`() {
        var now = 1_000L
        val seen = Recorder()
        val throttle = throttle({ now }, seen)
        throttle.onBytes(64, 1_000)
        assertEquals(listOf(DownloadProgressThrottle.DOWNLOADING), seen.phases)
        assertEquals(listOf(64L), seen.done)
        now = 1_100
        throttle.onBytes(128, 1_000)
        assertEquals(1, seen.phases.size)
        now = 1_200
        throttle.onBytes(192, 1_000)
        assertEquals(2, seen.phases.size)
        assertEquals(192L, seen.done.last())
        assertEquals(1_000L, seen.totals.last())
    }

    @Test
    fun `verifying lands once at the total and ignores the interval`() {
        var now = 1_000L
        val seen = Recorder()
        val throttle = throttle({ now }, seen)
        throttle.onBytes(500, 1_000)
        now = 1_100
        throttle.onBytes(1_000, 1_000)
        assertEquals(
            listOf(DownloadProgressThrottle.DOWNLOADING, DownloadProgressThrottle.VERIFYING),
            seen.phases
        )
        assertEquals(1_000L, seen.done.last())
        throttle.onBytes(1_000, 1_000)
        throttle.onBytes(1_000, 1_000)
        assertEquals(2, seen.phases.size)
    }

    @Test
    fun `a retry that restarts a file cannot drag the bar backwards`() {
        var now = 1_000L
        val seen = Recorder()
        val throttle = throttle({ now }, seen)
        throttle.onBytes(800, 1_000)
        now = 1_200
        throttle.onBytes(100, 1_000)
        assertEquals(listOf(800L, 800L), seen.done)
    }

    @Test
    fun `nothing is emitted after stop`() {
        var now = 1_000L
        val seen = Recorder()
        val throttle = throttle({ now }, seen)
        throttle.onBytes(500, 1_000)
        throttle.stop()
        now = 5_000
        throttle.onBytes(900, 1_000)
        throttle.onBytes(1_000, 1_000)
        assertEquals(listOf(DownloadProgressThrottle.DOWNLOADING), seen.phases)
    }

    /**
     * A Play pack reports its real byte counts, so the bar has a real ratio all the way to full and
     * one verifying event, not a stream of zeros the way a status-only signal produced.
     */
    @Test
    fun `play pack byte samples fill the bar and verify once`() {
        var now = 0L
        val seen = Recorder()
        val throttle = throttle({ now }, seen)
        val total = PpOcrModel.Engine.MEDIUM.approximateDownloadBytes
        val samples = listOf(0L, 12_000_000L, 48_000_000L, total)
        for (done in samples) {
            throttle.onBytes(done, total)
            now += 200
        }
        assertEquals(
            listOf(
                DownloadProgressThrottle.DOWNLOADING,
                DownloadProgressThrottle.DOWNLOADING,
                DownloadProgressThrottle.DOWNLOADING,
                DownloadProgressThrottle.VERIFYING
            ),
            seen.phases
        )
        assertEquals(samples.dropLast(1) + total, seen.done)
        assertEquals(List(samples.size) { total }, seen.totals)
    }
}
