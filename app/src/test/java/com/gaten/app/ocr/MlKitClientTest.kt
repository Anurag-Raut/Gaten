/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.MlImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * The pin that keeps ML Kit's client alive while a pass runs. The client is a fake (the tests drive
 * the returned [Task] themselves), so nothing here loads ML Kit's model.
 */
class MlKitClientTest {

    @Test
    fun aPassNotYetCompleteKeepsTheClientFromBeingReleased() {
        var builds = 0
        val client = MlKitClient { builds++; FakeRecognizer() }
        val pending = TaskCompletionSource<String>()

        client.run { pending.task }

        assertFalse(client.release())
        pending.setResult("done")
        assertTrue(client.release())
        assertEquals(1, builds)
    }

    @Test
    fun aPassWhoseAwaitWasAbandonedStaysPinnedUntilTheTaskCompletes() {
        // The coroutine that would await the pass is never attached here: cancelling an await does
        // not stop ML Kit's native pass, so the client may only be released once the Task is done.
        val client = MlKitClient { FakeRecognizer() }
        val pending = TaskCompletionSource<String>()

        client.run { pending.task }

        assertFalse(client.release())
        pending.setException(CancellationException("the pass was cancelled"))
        assertTrue(client.release())
    }

    @Test
    fun theClientIsRebuiltAfterARelease() {
        var builds = 0
        val client = MlKitClient { builds++; FakeRecognizer() }

        client.run { Tasks.forResult("a") } // completes at once, so the pass unpins
        assertTrue(client.release())
        client.run { Tasks.forResult("b") }

        assertEquals(2, builds)
    }

    @Test
    fun onCompleteRunsExactlyOnceHoweverThePassEnds() {
        var completes = 0
        val client = MlKitClient { FakeRecognizer() }

        client.run(onComplete = { completes++ }) { Tasks.forResult("a") }
        assertThrows(IllegalStateException::class.java) {
            client.run<String>(onComplete = { completes++ }) { throw IllegalStateException("process threw") }
        }

        assertEquals(2, completes)
    }

    @Test
    fun aFailedBuildDoesNotKeepTheClientPinned() {
        var builds = 0
        var completes = 0
        val client = MlKitClient {
            if (++builds == 1) error("client build failed")
            FakeRecognizer()
        }
        assertThrows(IllegalStateException::class.java) {
            client.run<String>(onComplete = { completes++ }) { Tasks.forResult("unused") }
        }
        assertFalse(client.release())
        client.run(onComplete = { completes++ }) { Tasks.forResult("retry") }
        assertTrue(client.release())
        assertEquals(2, completes)
    }

    /** A recognizer that never runs a pass; the tests hand back their own [Task]. */
    private class FakeRecognizer : TextRecognizer {
        override fun close() {}

        override fun process(image: MlImage): Task<Text> = fail()

        override fun process(image: InputImage): Task<Text> = fail()

        override fun getDetectorType(): Int = Detector.TYPE_TEXT_RECOGNITION

        override fun process(bitmap: Bitmap, rotationDegrees: Int): Task<Text> = fail()

        override fun process(image: Image, rotationDegrees: Int): Task<Text> = fail()

        override fun process(image: Image, rotationDegrees: Int, matrix: Matrix): Task<Text> = fail()

        override fun process(
            bytes: ByteBuffer,
            width: Int,
            height: Int,
            format: Int,
            rotationDegrees: Int
        ): Task<Text> = fail()

        override fun getOptionalFeatures(): Array<Feature> = emptyArray()

        private fun fail(): Nothing = error("the fake does not run a pass")
    }
}
