/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gaten's colour contract: the one shipped neutral ladder and the accent hold their contrast.
 *
 * These are the numbers the app's colours must not move. Every readable pair in the app is judged
 * by a contrast ratio, and a ratio is computed from two relative luminances — so a ladder or an
 * accent that holds those ratios cannot make the ink too faint for the paper it was put on.
 */
class PaletteTest {

    /** WCAG contrast ratio, the form every readable pair in the app is judged in. */
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    /** The shipped ladder is returned as written: the tones are the constants, not a retint. */
    @Test
    fun gatenIsTheLadderAsShipped() {
        val light = gatenNeutrals(dark = false)
        assertEquals(LightBackground, light.background)
        assertEquals(LightSurface, light.surface)
        assertEquals(LightSurfaceVariant, light.surfaceVariant)
        assertEquals(LightSurfaceContainerHigh, light.surfaceContainerHigh)
        assertEquals(LightOnSurface, light.onSurface)
        assertEquals(LightOnSurfaceVariant, light.onSurfaceVariant)
        assertEquals(LightOutline, light.outline)

        val dark = gatenNeutrals(dark = true)
        assertEquals(DarkBackground, dark.background)
        assertEquals(DarkSurface, dark.surface)
        assertEquals(DarkSurfaceVariant, dark.surfaceVariant)
        assertEquals(DarkSurfaceContainerHigh, dark.surfaceContainerHigh)
        assertEquals(DarkOnSurface, dark.onSurface)
        assertEquals(DarkOnSurfaceVariant, dark.onSurfaceVariant)
        assertEquals(DarkOutline, dark.outline)
    }

    /** Black is the colourless accent: the neutral axis at its extremes, not a tinted one. */
    @Test
    fun blackIsTheColourlessAccent() {
        assertEquals(Color(0xFF000000), accentRoles(AccentColor.Black, dark = false).primary)
        assertEquals(Color(0xFFFFFFFF), accentRoles(AccentColor.Black, dark = true).primary)
    }

    /**
     * The accent ladder's own contract: a primary carries the ink its label is written in — white in
     * light, near-black in dark — and reads as text on the paper it is drawn against. The floor is
     * WCAG's 4.5:1, which the selected pill's small label and every accent-coloured link depend on.
     */
    @Test
    fun everyAccentCarriesItsInkAndReadsOnThePaper() {
        for (accent in AccentColor.entries) {
            for (dark in listOf(false, true)) {
                val tone = accentRoles(accent, dark)
                val paper = gatenNeutrals(dark).background
                val ink = contrast(tone.primary, tone.onPrimary)
                assertTrue(
                    "$accent (dark=$dark): primary carries its ink at only $ink:1",
                    ink >= 4.5f
                )
                val onPaper = contrast(tone.primary, paper)
                assertTrue(
                    "$accent (dark=$dark): primary on the paper is only $onPaper:1",
                    onPaper >= 4.5f
                )
            }
        }
    }

    @Test
    fun defaultLightBaselineColorsAndAccent() {
        val light = gatenNeutrals(dark = false)
        assertEquals(Color(0xFFFDFDFD), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFFF6F6F8), light.surfaceVariant)
        assertEquals(Color(0xFF17171A), light.onSurface)
        assertEquals(Color(0xFF6C6C75), light.onSurfaceVariant)
        assertEquals(Color(0xFFE9E9ED), light.outline)
        assertEquals(Color(0xFFB42335), LightError)

        val indigo = accentRoles(AccentColor.Indigo, dark = false)
        assertEquals(Color(0xFF4338CA), indigo.primary)
        assertEquals(Color(0xFFEEF2FF), indigo.primaryContainer)

        val dark = gatenNeutrals(dark = true)
        assertEquals(Color(0xFF101114), dark.background)
        assertEquals(Color(0xFF17181C), dark.surface)
        assertEquals(Color(0xFF1B1C21), dark.surfaceVariant)
        assertEquals(Color(0xFFF5F5F6), dark.onSurface)
        assertEquals(Color(0xFFA7A7B0), dark.onSurfaceVariant)
        assertEquals(Color(0xFF303136), dark.outline)
        assertEquals(Color(0xFFA5B4FC), accentRoles(AccentColor.Indigo, dark = true).primary)
    }
}
