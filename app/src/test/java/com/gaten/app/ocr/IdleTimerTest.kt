/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scheduling half of [OcrModels], with the release itself replaced by a count: when the models
 * are handed back, that the idle clock starts after the *last* use rather than during one, and that
 * a new use or a memory-pressure request does not leave a stale release behind.
 */
class IdleTimerTest {

    private val timeout = 200L
    private val margin = 600L

    private fun timer(onIdle: suspend () -> Unit) =
        IdleTimer(timeout, CoroutineScope(Dispatchers.Default), onIdle)

    @Test
    fun releasesOnceTheLastUseHasEnded() = runBlocking {
        val releases = AtomicInteger()
        val timer = timer { releases.incrementAndGet() }

        timer.begin()
        timer.end()

        delay(timeout + margin)
        assertEquals(1, releases.get())
    }

    @Test
    fun aNewUseCancelsTheArmedRelease() = runBlocking {
        val releases = AtomicInteger()
        val timer = timer { releases.incrementAndGet() }

        timer.begin()
        timer.end()
        timer.begin() // the armed release is cancelled before it can fire

        delay(timeout + margin)
        assertEquals(0, releases.get())
    }

    @Test
    fun overlappingUsesHoldTheModelsUntilTheLastOneEnds() = runBlocking {
        val releases = AtomicInteger()
        val timer = timer { releases.incrementAndGet() }

        timer.begin()
        timer.begin()
        timer.end() // one use is still running: no release may be armed

        delay(timeout + margin)
        assertEquals(0, releases.get())

        timer.end()
        delay(timeout + margin)
        assertEquals(1, releases.get())
    }

    @Test
    fun releaseNowFiresAtOnceAndLeavesNoArmedReleaseBehind() = runBlocking {
        val releases = AtomicInteger()
        val timer = timer { releases.incrementAndGet() }

        timer.begin()
        timer.end()
        timer.releaseNow()

        delay(timeout + margin)
        assertEquals(1, releases.get())
    }

    @Test
    fun memoryPressureWaitsForEveryUseThenSkipsTheIdleDelay() = runBlocking {
        val released = CompletableDeferred<Unit>()
        val timer = IdleTimer(10_000L, CoroutineScope(Dispatchers.Default)) { released.complete(Unit) }
        timer.begin()
        timer.begin()
        timer.releaseNow()
        timer.end()
        delay(timeout + margin)
        assertEquals(false, released.isCompleted)
        timer.end()
        withTimeout(2_000L) { released.await() }
    }

    @Test
    fun anotherUseDoesNotLosePendingMemoryPressureCleanup() = runBlocking {
        val released = CompletableDeferred<Unit>()
        val timer = IdleTimer(10_000L, CoroutineScope(Dispatchers.Default)) { released.complete(Unit) }
        timer.begin()
        timer.releaseNow()
        timer.begin()
        timer.end()
        timer.end()
        withTimeout(2_000L) { released.await() }
    }
}
