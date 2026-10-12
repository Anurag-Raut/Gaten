/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionMergeTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = Box(l, t, r, b)

    // --- merge ---

    @Test
    fun aMeikitBoxOverlappingAnMlKitBoxByIoUIsNotAdded() {
        val mlKit = listOf(box(20, 20, 100, 100))
        val meiki = listOf(box(0, 0, 80, 80)) // IoU ~0.39 > 0.3

        val result = DetectionMerge.merge(mlKit, meiki)

        assertTrue(result.added.isEmpty())
        assertEquals(mlKit, result.all)
    }

    @Test
    fun aMeikiBoxMostlyInsideAnMlKitBoxIsNotAdded() {
        val mlKit = listOf(box(0, 0, 100, 100))
        val meiki = listOf(box(40, 40, 60, 60)) // IoU ~0.04, but fully inside

        val result = DetectionMerge.merge(mlKit, meiki)

        assertTrue(result.added.isEmpty())
    }

    @Test
    fun anMlKitBoxMostlyInsideAMeikiBoxIsNotAdded() {
        val mlKit = listOf(box(40, 40, 60, 60))
        val meiki = listOf(box(0, 0, 100, 100)) // the meiki box swallows ML Kit's

        val result = DetectionMerge.merge(mlKit, meiki)

        assertTrue(result.added.isEmpty())
    }

    @Test
    fun aMeikiBoxClearOfEveryMlKitBoxIsAdded() {
        val mlKit = listOf(box(0, 0, 100, 100))
        val meiki = listOf(box(300, 300, 400, 400))

        val result = DetectionMerge.merge(mlKit, meiki)

        assertEquals(listOf(box(300, 300, 400, 400)), result.added)
        assertEquals(listOf(box(0, 0, 100, 100), box(300, 300, 400, 400)), result.all)
    }

    @Test
    fun aSlightOverlapThatIsNeitherContainmentNorHighIoUIsAdded() {
        val mlKit = listOf(box(0, 0, 60, 60))
        val meiki = listOf(box(40, 40, 100, 100)) // 20x20 shared, IoU ~0.06

        assertEquals(listOf(box(40, 40, 100, 100)), DetectionMerge.merge(mlKit, meiki).added)
    }

    @Test
    fun mlKitBoxesAreAlwaysKeptEvenWithNoMeikiBoxes() {
        val mlKit = listOf(box(0, 0, 10, 10), box(50, 50, 70, 70))

        val result = DetectionMerge.merge(mlKit, emptyList())

        assertEquals(mlKit, result.all)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun emptyInputsGiveAnEmptyResult() {
        val result = DetectionMerge.merge(emptyList(), emptyList())

        assertTrue(result.added.isEmpty())
        assertTrue(result.all.isEmpty())
    }

    // --- substantial overlap arithmetic ---

    @Test
    fun disjointBoxesHaveNoIntersectionOrIou() {
        assertEquals(0L, DetectionMerge.intersectionArea(box(0, 0, 10, 10), box(20, 20, 30, 30)))
        assertEquals(0f, DetectionMerge.iou(box(0, 0, 10, 10), box(20, 20, 30, 30)), 0f)
    }

    @Test
    fun identicalBoxesHaveIouOne() {
        assertEquals(1f, DetectionMerge.iou(box(5, 5, 40, 40), box(5, 5, 40, 40)), 0f)
    }

    @Test
    fun fractionInsideMeasuresTheRatioOfTheFirstBoxCoveredByTheSecond() {
        // 40x40 box with the top-left 20x20 inside a smaller box: a quarter of it.
        assertEquals(0.25f, DetectionMerge.fractionInside(box(0, 0, 40, 40), box(0, 0, 20, 20)), 0f)
    }

    // --- tile planning ---

    @Test
    fun aSquareBitmapIsASingleTile() {
        val tiles = DetectionMerge.planTiles(1000, 1000)

        assertEquals(1, tiles.size)
        assertEquals(DetectionMerge.Tile(0, 0, 1000, 1000), tiles.single())
    }

    @Test
    fun aLandscapeBitmapTilesAlongItsWidth() {
        val tiles = DetectionMerge.planTiles(2400, 1080)

        assertEquals(listOf(0, 864, 1320), tiles.map { it.left })
        assertEquals(listOf(0, 0, 0), tiles.map { it.top })
        assertTrue(tiles.all { it.width == 1080 && it.height == 1080 })
        assertEquals(2400, tiles.last().right) // the last tile is flush with the far edge
    }

    @Test
    fun aPortraitBitmapTilesAlongItsHeight() {
        val tiles = DetectionMerge.planTiles(1080, 2400)

        assertEquals(listOf(0, 0, 0), tiles.map { it.left })
        assertEquals(listOf(0, 864, 1320), tiles.map { it.top })
        assertEquals(2400, tiles.last().bottom)
    }

    @Test
    fun aSquareBitmapIsOneTileWhateverItsSize() {
        assertEquals(1, DetectionMerge.planTiles(500, 500).size)
    }

    @Test
    fun aBitmapOnlySlightlyWiderThanTallStillGetsASecondTile() {
        assertEquals(2, DetectionMerge.planTiles(640, 480).size)
    }

    @Test
    fun adjacentTilesShareTheirOverlap() {
        val tiles = DetectionMerge.planTiles(2400, 1080)

        // Second tile starts at 864, first ends at 1080: 216 px shared, ~20% of the 1080 side.
        assertEquals(216, tiles[0].right - tiles[1].left)
    }

    @Test
    fun aBoxMovesIntoBitmapCoordinatesByTheTileOrigin() {
        val tile = DetectionMerge.Tile(864, 0, 1944, 1080)

        assertEquals(box(874, 20, 974, 120), DetectionMerge.tileToBitmap(box(10, 20, 110, 120), tile))
    }

    // --- letterbox ---

    @Test
    fun letterboxCentresAndPadsTheShorterAxis() {
        val lb = DetectionMerge.letterbox(800, 600, 640)

        assertEquals(0.8f, lb.ratio, 1e-6f)
        assertEquals(0, lb.padW)
        assertEquals(80, lb.padH)
    }

    @Test
    fun letterboxRoundTripsThroughModelCoordinates() {
        val lb = DetectionMerge.letterbox(800, 600, 640)
        val original = box(60, 120, 444, 201)

        val model = DetectionMerge.bitmapToModel(original, lb)
        val back = DetectionMerge.modelToBitmap(model, lb)

        assertEquals(original, back)
    }

    @Test
    fun letterboxRoundTripsOnAPortraitTile() {
        val lb = DetectionMerge.letterbox(1080, 1080, 640)
        val original = box(0, 0, 1080, 1080)

        assertEquals(original, DetectionMerge.modelToBitmap(DetectionMerge.bitmapToModel(original, lb), lb))
    }

    // --- nms ---

    @Test
    fun nmsKeepsTheHigherScoringBoxOfAnOverlappingPair() {
        val boxes = listOf(
            DetectionMerge.ScoredBox(box(0, 0, 100, 100), 0.9f),
            DetectionMerge.ScoredBox(box(10, 10, 110, 110), 0.8f) // IoU ~0.68
        )

        val kept = DetectionMerge.nms(boxes, 0.5f)

        assertEquals(1, kept.size)
        assertEquals(0.9f, kept.single().score, 0f)
    }

    @Test
    fun nmsKeepsBoxesThatDoNotOverlap() {
        val boxes = listOf(
            DetectionMerge.ScoredBox(box(0, 0, 100, 100), 0.9f),
            DetectionMerge.ScoredBox(box(200, 200, 300, 300), 0.7f)
        )

        assertEquals(2, DetectionMerge.nms(boxes, 0.5f).size)
    }

    @Test
    fun nmsOfNothingIsNothing() {
        assertTrue(DetectionMerge.nms(emptyList(), 0.5f).isEmpty())
    }

    @Test
    fun sameLineIsTrueForIoUOverlapAndFalseForATouch() {
        assertTrue(DetectionMerge.sameLine(box(0, 0, 80, 80), box(20, 20, 100, 100)))
        assertFalse(DetectionMerge.sameLine(box(0, 0, 40, 40), box(60, 60, 100, 100)))
    }
}