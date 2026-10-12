/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trims a line's loose ML Kit box back onto the glyphs using the screenshot's own pixels.
 *
 * ML Kit reports a line box with padding, merged punctuation and bubble edges, so an even split of
 * that box drifts off the real characters. The pixels do not lie: text is a cluster of strokes on a
 * flat background, so a projection profile of the crop finds exactly where the ink starts and stops.
 *
 * The crop is a luminance array (`0..255`), the line runs along one axis (horizontal: `x`, vertical:
 * `y`) and the perpendicular axis is the cross axis. Background is the median of the crop's border
 * pixels — the border of a line crop is background by construction — and a pixel is ink when it
 * lies on the far side of the crop's Otsu split from that background and differs from it by more than
 * [MIN_CONTRAST].
 *
 * No Android here: the arithmetic is the part most likely to be subtly wrong, so it is unit-tested
 * on the JVM with synthetic arrays.
 */
object InkProjection {

    /** Smallest luminance gap that still reads as ink, whatever Otsu says about a flat crop. */
    private const val MIN_CONTRAST = 40

    /** Share of the perpendicular size an axis must carry before it counts as ink, as a percent. */
    private const val NOISE_PERCENT = 2

    /**
     * The tightened extent of the ink, in crop coordinates: `mainStart`/`mainEnd` bound the line
     * axis (`end` exclusive), `crossStart`/`crossEnd` the perpendicular axis. Null when nothing in
     * the crop is ink, i.e. the caller keeps ML Kit's own box.
     */
    data class Tightened(
        val mainStart: Int,
        val mainEnd: Int,
        val crossStart: Int,
        val crossEnd: Int
    )

    /**
     * Tightens [gray] (`w` x `h`, `0..255`) onto its ink. The main axis is `x` for a horizontal
     * line and `y` for a [vertical] one. Leading and trailing positions whose ink count does not
     * reach the noise floor are dropped on each axis; null when no ink is found at all.
     */
    fun tighten(gray: IntArray, w: Int, h: Int, vertical: Boolean): Tightened? {
        if (w <= 0 || h <= 0 || gray.size < w * h) return null

        val mask = inkMask(gray, w, h)
        val mainSize = if (vertical) h else w
        val crossSize = if (vertical) w else h

        // Pass 1: how much ink each line-axis position carries, over the whole cross extent.
        val mainProfile = IntArray(mainSize)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                if (vertical) mainProfile[y]++ else mainProfile[x]++
            }
        }
        val mainFloor = max(1, crossSize * NOISE_PERCENT / 100)
        val mainStart = firstAtLeast(mainProfile, mainFloor) ?: return null
        val mainEnd = lastAtLeast(mainProfile, mainFloor) + 1

        // Pass 2: the cross profile, but only where the line axis is already known to be ink, so a
        // stray mark past the end of the text cannot stretch the cross extent.
        val crossProfile = IntArray(crossSize)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                val along = if (vertical) y else x
                if (along < mainStart || along >= mainEnd) continue
                if (vertical) crossProfile[x]++ else crossProfile[y]++
            }
        }
        val crossFloor = max(1, (mainEnd - mainStart) * NOISE_PERCENT / 100)
        val crossStart = firstAtLeast(crossProfile, crossFloor) ?: return null
        val crossEnd = lastAtLeast(crossProfile, crossFloor) + 1

        return Tightened(mainStart, mainEnd, crossStart, crossEnd)
    }

    /**
     * Splits [span] into exactly [count] character cells along the line axis, for full-width text
     * laid out one em per character.
     *
     * A plain even split is the starting point, but the glyphs are not perfectly even: an internal
     * boundary is nudged to the emptiest position within a quarter of a cell either way, which lands
     * it in the inter-character gap. The nudge only happens when that valley is clearly emptier than
     * the line's average ink (half of it), so a line with no real gaps keeps the even boundaries.
     * Returns null when the span is too short for [count] non-empty cells or when [count] is not
     * positive — the caller then falls back to [interpolateCharBoxes].
     */
    fun charCells(gray: IntArray, w: Int, h: Int, vertical: Boolean, span: Tightened, count: Int): List<IntRange>? {
        if (count <= 0) return null
        val length = span.mainEnd - span.mainStart
        if (length < count) return null

        val mask = inkMask(gray, w, h)
        val profile = IntArray(length)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                val along = if (vertical) y else x
                if (along < span.mainStart || along >= span.mainEnd) continue
                val across = if (vertical) x else y
                if (across < span.crossStart || across >= span.crossEnd) continue
                profile[along - span.mainStart]++
            }
        }
        val mean = profile.sum().toDouble() / length
        val cellSize = length.toDouble() / count
        val radius = max(1, (cellSize * 0.25).roundToInt())

        val boundaries = IntArray(count + 1)
        boundaries[0] = span.mainStart
        boundaries[count] = span.mainEnd
        for (i in 1 until count) {
            val even = span.mainStart + i * length / count
            // Stay strictly between the previous boundary and one pixel per remaining cell.
            val lo = max(even - radius, boundaries[i - 1] + 1)
            val hi = min(even + radius, span.mainEnd - (count - i))
            var best = even
            var bestInk = Int.MAX_VALUE
            for (c in lo..hi) {
                val ink = profile[c - span.mainStart]
                if (ink < bestInk || (ink == bestInk && abs(c - even) < abs(best - even))) {
                    bestInk = ink
                    best = c
                }
            }
            boundaries[i] = if (bestInk * 2 < mean) best
            else even.coerceIn(boundaries[i - 1] + 1, span.mainEnd - (count - i))
        }
        return (0 until count).map { boundaries[it] until boundaries[it + 1] }
    }

    /** Per-pixel ink test: the border median is background, and ink is far enough from it. */
    private fun inkMask(gray: IntArray, w: Int, h: Int): BooleanArray {
        val background = backgroundLuminance(gray, w, h)
        val split = otsu(gray, w * h)
        // Otsu gives a luminance level, not a contrast: ink is what sits on the far side of that
        // split from the background, and is still at least MIN_CONTRAST away from it.
        val darkInk = background > split
        val mask = BooleanArray(w * h)
        for (i in mask.indices) {
            val g = gray[i]
            val farSide = if (darkInk) g <= split else g > split
            mask[i] = farSide && abs(g - background) > MIN_CONTRAST
        }
        return mask
    }

    /** The median luminance of the crop's border pixels: the flat background a line sits on. */
    private fun backgroundLuminance(gray: IntArray, w: Int, h: Int): Int {
        val border = IntArray(2 * (w + h))
        var n = 0
        for (x in 0 until w) {
            border[n++] = gray[x]
            border[n++] = gray[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            border[n++] = gray[y * w]
            border[n++] = gray[y * w + w - 1]
        }
        border.sort(0, n)
        return border[n / 2]
    }

    /**
     * Otsu's split of the crop's histogram: the luminance that best separates the two modes. On a
     * flat crop the split is meaningless, which is why the ink test also demands [MIN_CONTRAST].
     */
    private fun otsu(gray: IntArray, count: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until count) hist[gray[i].coerceIn(0, 255)]++

        var sum = 0.0
        for (level in 0..255) sum += level * hist[level]

        var sumBack = 0.0
        var weightBack = 0
        var bestVar = -1.0
        var threshold = 0
        for (level in 0..255) {
            weightBack += hist[level]
            if (weightBack == 0) continue
            val weightFore = count - weightBack
            if (weightFore == 0) break
            sumBack += level * hist[level]
            val meanBack = sumBack / weightBack
            val meanFore = (sum - sumBack) / weightFore
            val between = weightBack.toDouble() * weightFore * (meanBack - meanFore) * (meanBack - meanFore)
            if (between > bestVar) {
                bestVar = between
                threshold = level
            }
        }
        return threshold
    }

    private fun firstAtLeast(profile: IntArray, floor: Int): Int? {
        for (i in profile.indices) if (profile[i] >= floor) return i
        return null
    }

    private fun lastAtLeast(profile: IntArray, floor: Int): Int {
        for (i in profile.indices.reversed()) if (profile[i] >= floor) return i
        return -1
    }
}
