/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import kotlin.math.abs

/**
 * The arithmetic behind the frame-stability probe loop in `GatenAccessibilityService`: how far
 * apart two downscaled screenshots are, when they count as the same picture, and whether the loop's
 * budget still allows another probe.
 *
 * Deliberately free of Android types so the thresholds can be unit tested without a screenshot.
 */
internal object FrameStability {

    /**
     * Below this mean per-channel difference two probe frames count as equal, allowing minor
     * compression noise while still catching an animation in flight.
     */
    const val DEFAULT_THRESHOLD = 2.0f

    /**
     * Mean absolute RGB difference between two ARGB pixel arrays, averaged over the three channels
     * of every pixel; alpha is ignored, since a frame that only fades differs in what the user sees
     * but not in what is drawn.
     *
     * Returns [Float.MAX_VALUE] when the arrays have different lengths or are empty, which every
     * caller reads as "the frame changed".
     */
    fun meanAbsoluteDifference(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || a.size != b.size) return Float.MAX_VALUE
        var total = 0L
        for (i in a.indices) {
            total += abs((a[i] shr 16 and 0xFF) - (b[i] shr 16 and 0xFF)) +
                abs((a[i] shr 8 and 0xFF) - (b[i] shr 8 and 0xFF)) +
                abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return total.toFloat() / (a.size * 3)
    }

    /** True while two frames with a difference of [diff] are close enough to count as one picture. */
    fun isStable(diff: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean = diff < threshold

    /** True only while [nowMs] is still before [deadlineMs]: the loop may take one more probe. */
    fun shouldProbeAgain(nowMs: Long, deadlineMs: Long): Boolean = nowMs < deadlineMs
}
