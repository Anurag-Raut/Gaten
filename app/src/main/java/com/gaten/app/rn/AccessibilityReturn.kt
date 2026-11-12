/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context

/**
 * The one-shot hand-off from the accessibility settings screen back to Gaten.
 *
 * The reader leaves the app to enable the service, and Android does not return them: without this
 * they have to press Back through Settings. The bridge arms the flag immediately before it opens
 * Settings, [com.gaten.app.GatenAccessibilityService.onServiceConnected] consumes it once, and the
 * service brings [com.gaten.app.MainActivity] to the front.
 *
 * The flag is deliberately short-lived. It is not a general "the service was enabled" signal: an
 * enable ten minutes later, from outside this hand-off, must never pull the reader out of whatever
 * they are doing. The decision is kept separate from storage so a JVM test can pin the expiry; the
 * preference is process-local to Gaten's single app process.
 */
internal object AccessibilityReturn {
    private const val PREFS = "accessibility_return"
    private const val KEY_ARMED = "armed"
    private const val KEY_MARKED_AT = "marked_at"

    /** How long an armed hand-off may wait for the service to connect. */
    internal const val EXPIRY_MS = 10 * 60 * 1000L

    /** Serialises a mark and the service's consume, which arrive on different threads. */
    private val lock = Any()

    /**
     * Wall-clock time behind one seam, as [com.gaten.app.support.SupportPreferences] keeps its own:
     * the AnkiDroid lint check the release build runs rejects a bare `System.currentTimeMillis()`,
     * and an object has no constructor to take a clock through. The callers below keep their
     * [nowMs] parameter so a JVM test can still hand in its own reading.
     */
    private val now: () -> Long = System::currentTimeMillis

    /** Arms the next service connection to return to Gaten, replacing any earlier request. */
    fun mark(context: Context, nowMs: Long = now()) = synchronized(lock) {
        prefs(context).edit()
            .putBoolean(KEY_ARMED, true)
            .putLong(KEY_MARKED_AT, nowMs)
            .apply()
    }

    /**
     * Reads and clears the hand-off. The flag is cleared even when it has expired, so an old request
     * can never fire later; only a live one returns true.
     */
    fun consume(context: Context, nowMs: Long = now()): Boolean =
        synchronized(lock) {
            val prefs = prefs(context)
            val armed = prefs.getBoolean(KEY_ARMED, false)
            val markedAt = prefs.getLong(KEY_MARKED_AT, 0L)
            prefs.edit()
                .remove(KEY_ARMED)
                .remove(KEY_MARKED_AT)
                .apply()
            shouldReturn(armed = armed, markedAtMs = markedAt, nowMs = nowMs)
        }

    /**
     * True only for an armed request made no more than [EXPIRY_MS] ago. A timestamp in the future
     * (the wall clock moved backwards) is not trusted either: returning then would hijack a screen
     * for an enable this hand-off cannot prove is the one the reader just made.
     */
    internal fun shouldReturn(armed: Boolean, markedAtMs: Long, nowMs: Long): Boolean =
        armed &&
            markedAtMs > 0L &&
            nowMs >= markedAtMs &&
            nowMs - markedAtMs <= EXPIRY_MS

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
