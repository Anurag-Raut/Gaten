/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.media

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A [ScheduledExecutorService] whose work runs only when [runPending] is called, in the order it was
 * submitted.
 *
 * The media pause, its resume and its discard all share one serial executor, and how they order
 * against each other is exactly the behaviour under test: a delayed pause must still land when a
 * discard was queued before it, and a cancelled one must not. Driving that order from the test is
 * what makes those cases deterministic instead of a race.
 */
internal class ManualExecutor : ScheduledExecutorService {

    private val queued = ArrayDeque<Runnable>()
    private var stopped = false

    /** Runs everything queued now, in order, including work queued while it drains. */
    fun runPending() {
        while (true) {
            val next = queued.removeFirstOrNull() ?: return
            next.run()
        }
    }

    /** How much work is still waiting, so a test can tell a cancelled task from a queued one. */
    val pending: Int get() = queued.size

    override fun execute(command: Runnable) {
        check(!stopped) { "the executor is shut down" }
        queued += command
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        check(!stopped) { "the executor is shut down" }
        val future = ManualFuture(command)
        queued += Runnable { future.runIfNotCancelled() }
        return future
    }

    override fun shutdown() {
        stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        stopped = true
        val dropped = queued.toMutableList()
        queued.clear()
        return dropped
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped

    // The pause path schedules runnables and nothing else; the rest is not worth faking.

    override fun <T> schedule(task: Callable<T>, delay: Long, unit: TimeUnit): ScheduledFuture<T> =
        unsupported()

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> = unsupported()

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> = unsupported()

    override fun <T> submit(task: Callable<T>): Future<T> = unsupported()

    override fun <T> submit(task: Runnable, result: T): Future<T> = unsupported()

    override fun submit(task: Runnable): Future<*> = unsupported()

    override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
        unsupported()

    override fun <T> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit
    ): MutableList<Future<T>> = unsupported()

    override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = unsupported()

    override fun <T> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit
    ): T = unsupported()

    private fun <T> unsupported(): T = throw UnsupportedOperationException("not used by MediaPauser")
}

/**
 * The future a scheduled task gets back: the only thing the pause path asks of it is `cancel(false)`,
 * so a cancelled task is skipped when the queue drains.
 */
internal class ManualFuture(private val task: Runnable) : ScheduledFuture<Any?> {

    private var cancelled = false

    fun runIfNotCancelled() {
        if (!cancelled) task.run()
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled

    override fun isDone(): Boolean = false

    override fun get(): Any? = throw UnsupportedOperationException("the pause reports nothing back")

    override fun get(timeout: Long, unit: TimeUnit): Any? = unsupported()

    override fun getDelay(unit: TimeUnit): Long = 0L

    override fun compareTo(other: Delayed): Int = 0

    private fun <T> unsupported(): T = throw UnsupportedOperationException("the pause reports nothing back")
}
