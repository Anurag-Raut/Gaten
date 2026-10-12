/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.overlay

import androidx.compose.ui.geometry.Rect
import com.gaten.app.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewMappingTest {

    @Test
    fun theSameSizeAtTheDisplayOriginIsIdentity() {
        val mapping = ViewMapping.between(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            viewWidth = 1080,
            viewHeight = 2400
        )

        assertEquals(ViewMapping.IDENTITY, mapping)
        assertEquals(300f, mapping.toView(300f, 700f).x, 0f)
        assertEquals(700f, mapping.toView(300f, 700f).y, 0f)
        assertEquals(Box(10, 20, 30, 40), mapping.toView(Box(10, 20, 30, 40)).toBox())
    }

    @Test
    fun aWindowHalfTheCaptureScalesEveryBoxByHalf() {
        val mapping = ViewMapping.between(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            viewWidth = 540,
            viewHeight = 1200
        )

        assertEquals(270f, mapping.toView(540f, 1000f).x, 0f)
        assertEquals(500f, mapping.toView(540f, 1000f).y, 0f)
        // The line box 800..1080 x 1000..1200 lies at 400..540 x 500..600 in the window.
        assertEquals(540f, mapping.toView(Box(800, 1000, 1080, 1200)).right, 0f)
        assertEquals(600f, mapping.toView(Box(800, 1000, 1080, 1200)).bottom, 0f)
        assertEquals(0.5f, mapping.minScale, 0f)
    }

    @Test
    fun aWindowBelowTheDisplayOriginPullsTheBoxesUpByTheOffset() {
        val mapping = ViewMapping.between(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            viewWidth = 1080,
            viewHeight = 2400,
            originY = 100
        )

        // Bitmap row 0 is above the window, so it is drawn 100 px above the window's own origin.
        assertEquals(-100f, mapping.toView(0f, 0f).y, 0f)
        assertEquals(900f, mapping.toView(0f, 1000f).y, 0f)
        assertEquals(300f, mapping.toView(Box(0, 300, 20, 400)).bottom, 0f)
    }

    @Test
    fun aGestureBackThroughTheMappingLandsOnThePixelItWasDrawnFrom() {
        val mapping = ViewMapping.between(
            bitmapWidth = 1440,
            bitmapHeight = 3120,
            viewWidth = 1080,
            viewHeight = 2340,
            originX = 24,
            originY = 100
        )

        val drawn = mapping.toView(600f, 1200f)
        val back = mapping.toBitmap(drawn.x, drawn.y)

        assertEquals(600f, back.x, 0.0001f)
        assertEquals(1200f, back.y, 0.0001f)
    }

    @Test
    fun aSelectionInViewPixelsComesBackAsTheCaptureRectangleItCovered() {
        val mapping = ViewMapping.between(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            viewWidth = 540,
            viewHeight = 1200
        )

        val selection = mapping.toBitmap(Rect(100f, 200f, 300f, 400f))

        assertEquals(200f, selection.left, 0f)
        assertEquals(400f, selection.top, 0f)
        assertEquals(600f, selection.right, 0f)
        assertEquals(800f, selection.bottom, 0f)
    }

    @Test
    fun aWindowThatHasNotBeenLaidOutYetMapsToIdentity() {
        assertEquals(ViewMapping.IDENTITY, ViewMapping.between(1080, 2400, 0, 0))
        assertEquals(ViewMapping.IDENTITY, ViewMapping.between(0, 0, 1080, 2400))
    }
}

/** A compose [Rect] as the integer [Box] the index speaks in. */
private fun Rect.toBox() = Box(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
