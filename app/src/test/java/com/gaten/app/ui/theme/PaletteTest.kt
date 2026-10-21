/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The palette contract: a palette moves the hue of the neutral ladder and nothing else.
 *
 * These are the numbers a palette must not move. Every readable pair in the app is judged by a
 * contrast ratio, and a ratio is computed from two relative luminances — so a palette that holds
 * those ratios cannot make the ink too faint for the paper it was put on.
 */
class PaletteTest {

    /**
     * How far a contrast ratio may move. The retint takes the 8-bit step at or above the source
     * luminance, so a role can land one channel brighter. The bound allows a rounding step.
     */
    private val maxContrastDrift = 0.01f

    private val roleNames = listOf(
        "background",
        "surface",
        "surfaceVariant",
        "surfaceContainer",
        "surfaceContainerHigh",
        "surfaceContainerHighest",
        "onSurface",
        "onSurfaceVariant",
        "outline"
    )

    /** The pairs a reader actually reads through: ink on each surface it is written on. */
    private val readablePairs = listOf(
        "onSurface" to "background",
        "onSurface" to "surface",
        "onSurface" to "surfaceVariant",
        "onSurfaceVariant" to "surfaceVariant",
        "onSurfaceVariant" to "surface"
    )

    /** Picks a role out of a ladder, so one loop can walk all of them. */
    private fun role(ladder: NeutralRoles, name: String): Color = when (name) {
        "background" -> ladder.background
        "surface" -> ladder.surface
        "surfaceVariant" -> ladder.surfaceVariant
        "surfaceContainer" -> ladder.surfaceContainer
        "surfaceContainerHigh" -> ladder.surfaceContainerHigh
        "surfaceContainerHighest" -> ladder.surfaceContainerHighest
        "onSurface" -> ladder.onSurface
        "onSurfaceVariant" -> ladder.onSurfaceVariant
        else -> ladder.outline
    }

    /** WCAG contrast ratio, the form every readable pair in the app is judged in. */
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    /** Gaten is the ladder the app shipped with: it is not tinted, it is returned as written. */
    @Test
    fun gatenIsTheLadderAsShipped() {
        val light = paletteNeutrals(Palette.Gaten, dark = false)
        assertEquals(LightBackground, light.background)
        assertEquals(LightSurface, light.surface)
        assertEquals(LightSurfaceVariant, light.surfaceVariant)
        assertEquals(LightSurfaceContainerHigh, light.surfaceContainerHigh)
        assertEquals(LightOnSurface, light.onSurface)
        assertEquals(LightOnSurfaceVariant, light.onSurfaceVariant)
        assertEquals(LightOutline, light.outline)

        val dark = paletteNeutrals(Palette.Gaten, dark = true)
        assertEquals(DarkBackground, dark.background)
        assertEquals(DarkSurface, dark.surface)
        assertEquals(DarkSurfaceVariant, dark.surfaceVariant)
        assertEquals(DarkSurfaceContainerHigh, dark.surfaceContainerHigh)
        assertEquals(DarkOnSurface, dark.onSurface)
        assertEquals(DarkOnSurfaceVariant, dark.onSurfaceVariant)
        assertEquals(DarkOutline, dark.outline)
    }

    /** Every palette, every mode, every readable pair: the contrast the shipped ladder had. */
    @Test
    fun everyPaletteHoldsTheContrastOfTheLadder() {
        for (palette in Palette.entries) {
            for (dark in listOf(false, true)) {
                val shipped = paletteNeutrals(Palette.Gaten, dark)
                val tinted = paletteNeutrals(palette, dark)
                for ((ink, paper) in readablePairs) {
                    val before = contrast(role(shipped, ink), role(shipped, paper))
                    val after = contrast(role(tinted, ink), role(tinted, paper))
                    val drift = abs(after - before) / before
                    assertTrue(
                        "$palette/$ink on $paper: contrast $before -> $after " +
                            "(${drift * 100}%, dark=$dark)",
                        drift < maxContrastDrift
                    )
                }
            }
        }
    }

    /**
     * The direction of that drift, which the contrast bound alone would not catch: the 8-bit step
     * taken is always the one at or above the shipped luminance, so no palette can darken the paper
     * or thin the ink. A palette may only ever add light.
     */
    @Test
    fun everyPaletteIsAtOrAboveTheShippedLuminance() {
        for (palette in Palette.entries) {
            for (dark in listOf(false, true)) {
                val shipped = paletteNeutrals(Palette.Gaten, dark)
                val tinted = paletteNeutrals(palette, dark)
                for (name in roleNames) {
                    val before = role(shipped, name).luminance()
                    val after = role(tinted, name).luminance()
                    assertTrue(
                        "$palette/$name: luminance fell from $before to $after (dark=$dark)",
                        after >= before
                    )
                }
            }
        }
    }

    /** Mono is the colourless palette: no hue anywhere in the ladder. */
    @Test
    fun monoHasNoHue() {
        for (dark in listOf(false, true)) {
            val mono = paletteNeutrals(Palette.Mono, dark)
            for (name in roleNames) {
                val color = role(mono, name)
                assertTrue(
                    "Mono/$name is not grey: $color (dark=$dark)",
                    color.red == color.green && color.green == color.blue
                )
            }
        }
    }

    /** A palette that changed nothing would be a broken choice on the settings screen. */
    @Test
    fun coolAndWarmMoveTheLadder() {
        for (dark in listOf(false, true)) {
            val shipped = paletteNeutrals(Palette.Gaten, dark)
            for (palette in listOf(Palette.Cool, Palette.Warm)) {
                val tinted = paletteNeutrals(palette, dark)
                assertNotEquals(
                    "$palette leaves the subtle surface as it was (dark=$dark)",
                    shipped.surfaceVariant,
                    tinted.surfaceVariant
                )
                assertNotEquals(
                    "$palette leaves the ink as it was (dark=$dark)",
                    shipped.onSurfaceVariant,
                    tinted.onSurfaceVariant
                )
            }
        }
    }

    @Test
    fun graphiteIsColourless() {
        for (dark in listOf(false, true)) {
            val primary = accentRoles(AccentColor.Graphite, dark).primary
            assertTrue(
                "Graphite's accent is not grey: $primary (dark=$dark)",
                primary.red == primary.green && primary.green == primary.blue
            )
            assertNotEquals(
                "Graphite's accent is the shipped one (dark=$dark)",
                accentRoles(AccentColor.Indigo, dark).primary,
                primary
            )
        }
    }

    @Test
    fun defaultLightBaselineColorsAndAccent() {
        val light = paletteNeutrals(Palette.Gaten, dark = false)
        assertEquals(Color(0xFFFDFDFD), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFFF6F6F8), light.surfaceVariant)
        assertEquals(Color(0xFF17171A), light.onSurface)
        assertEquals(Color(0xFF6C6C75), light.onSurfaceVariant)
        assertEquals(Color(0xFFE9E9ED), light.outline)
        assertEquals(Color(0xFFB42335), LightError)

        val indigo = accentRoles(AccentColor.Indigo, dark = false)
        assertEquals(Color(0xFF7564D9), indigo.primary)
        assertEquals(Color(0xFFF2EFFF), indigo.primaryContainer)

        val dark = paletteNeutrals(Palette.Gaten, dark = true)
        assertEquals(Color(0xFF101114), dark.background)
        assertEquals(Color(0xFF17181C), dark.surface)
        assertEquals(Color(0xFF1B1C21), dark.surfaceVariant)
        assertEquals(Color(0xFFF5F5F6), dark.onSurface)
        assertEquals(Color(0xFFB0B0BA), dark.onSurfaceVariant)
        assertEquals(Color(0xFF303136), dark.outline)
        assertEquals(Color(0xFFB0A0FF), accentRoles(AccentColor.Indigo, dark = true).primary)
    }
}
