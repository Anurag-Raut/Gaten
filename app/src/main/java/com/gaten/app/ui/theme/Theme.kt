/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat

/**
 * The scheme for one accent and mode: the accent's own tones, Gaten's neutral ladder, and the
 * Material roles Gaten never draws aliased onto the accent's — a component reaching
 * for `tertiaryContainer` lands on the accent rather than on the library's
 * baseline purple, which is the one hue this theme never shows.
 *
 * The ladder is filled in all the way down for the same reason. The roles left unset are not
 * unset for free: the library's own defaults are a purple-tinted grey, so a divider, a sheet or a
 * snackbar drawn by a component would be the one thing on screen that is not Gaten's ladder.
 * Every role added here is one of the ladder's own tones or the accent's, or the *other* mode's
 * ladder for the inverse roles — nothing new is invented, and no tone's job is duplicated.
 */
private fun gatenColorScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val tone = accentRoles(accent, dark)
    val neutral = gatenNeutrals(dark)
    // The accent in the *other* mode: the one thing an inverse role can be without inventing a
    // colour, and the pairing the library's inverse surfaces (a snackbar, a tooltip) are built on —
    // the other mode's paper, the ink that reads on it, and an accent tone light enough for that
    // paper.
    val inverseNeutral = gatenNeutrals(!dark)
    val inverseTone = accentRoles(accent, !dark)
    return if (dark) {
        darkColorScheme(
            primary = tone.primary,
            onPrimary = tone.onPrimary,
            primaryContainer = tone.primaryContainer,
            onPrimaryContainer = tone.onPrimaryContainer,
            tertiary = tone.tertiary,
            secondary = tone.primary,
            onSecondary = tone.onPrimary,
            secondaryContainer = neutral.surfaceVariant,
            onSecondaryContainer = neutral.onSurface,
            tertiaryContainer = tone.primaryContainer,
            onTertiaryContainer = tone.onPrimaryContainer,
            background = neutral.background,
            onBackground = neutral.onSurface,
            surface = neutral.surface,
            onSurface = neutral.onSurface,
            surfaceVariant = neutral.surfaceVariant,
            onSurfaceVariant = neutral.onSurfaceVariant,
            surfaceContainer = neutral.surfaceContainer,
            surfaceContainerHigh = neutral.surfaceContainerHigh,
            surfaceContainerHighest = neutral.surfaceContainerHighest,
            // The library's finer steps of the same ladder. Gaten has one step between paper and its
            // hairline, so the two finer ones land on the two it has rather than on the library's
            // own purple-grey — a sheet or a menu drawn on it is still Gaten's ladder.
            surfaceContainerLow = neutral.surfaceContainer,
            surfaceContainerLowest = neutral.surface,
            surfaceTint = tone.primary,
            outline = neutral.outline,
            // The one divider line there is: Gaten's hairline is already the weakest line it draws.
            outlineVariant = neutral.outline,
            inverseSurface = inverseNeutral.surface,
            inverseOnSurface = inverseNeutral.onSurface,
            inversePrimary = inverseTone.primary,
            error = DarkError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer
        )
    } else {
        lightColorScheme(
            primary = tone.primary,
            onPrimary = tone.onPrimary,
            primaryContainer = tone.primaryContainer,
            onPrimaryContainer = tone.onPrimaryContainer,
            tertiary = tone.tertiary,
            secondary = tone.primary,
            onSecondary = tone.onPrimary,
            secondaryContainer = neutral.surfaceVariant,
            onSecondaryContainer = neutral.onSurface,
            tertiaryContainer = tone.primaryContainer,
            onTertiaryContainer = tone.onPrimaryContainer,
            background = neutral.background,
            onBackground = neutral.onSurface,
            surface = neutral.surface,
            onSurface = neutral.onSurface,
            surfaceVariant = neutral.surfaceVariant,
            onSurfaceVariant = neutral.onSurfaceVariant,
            surfaceContainer = neutral.surfaceContainer,
            surfaceContainerHigh = neutral.surfaceContainerHigh,
            surfaceContainerHighest = neutral.surfaceContainerHighest,
            // @see the dark branch: the same ladder, the same reasoning.
            surfaceContainerLow = neutral.surfaceContainer,
            surfaceContainerLowest = neutral.surface,
            surfaceTint = tone.primary,
            outline = neutral.outline,
            outlineVariant = neutral.outline,
            inverseSurface = inverseNeutral.surface,
            inverseOnSurface = inverseNeutral.onSurface,
            inversePrimary = inverseTone.primary,
            error = LightError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer
        )
    }
}

/** The corner steps: chips and buttons, grouped rows and the cards that hold content. */
private val GatenShapes = Shapes(
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(20.dp)
)

/**
 * The lookup sheet's corner radius.
 *
 * The sheet is the one surface drawn *over* the screen rather than on it, so its corner is stated
 * once here rather than borrowed from the ladder above, and the sheet builds its three
 * dock-directional shapes from this number (ui/lookup/ResultSheet.kt).
 */
val SheetShape = 20.dp

/**
 * The app's only theme: Gaten's neutral ladder, accent and type scale. Dark or light comes from
 * [mode] and the accent from [accent] — both the reader's choice in Settings, defaulting to the
 * system setting and to the app's own indigo. Dynamic colour is deliberately not used: the colours
 * are chosen here, not taken from a wallpaper.
 */
@Composable
fun GatenTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: AccentColor = AccentColor.Indigo,
    content: @Composable () -> Unit
) {
    val darkTheme = mode.isDark(isSystemInDarkTheme())
    // Built once per choice rather than once per screen recomposition.
    val colorScheme = remember(accent, darkTheme) {
        gatenColorScheme(accent, darkTheme)
    }

    // System bars stay transparent (edge-to-edge); only the icon contrast is decided here.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Taken from the view, not from its context: the overlay is drawn in the capture
            // service's window, which has no Activity to take a Window from. Null when the view is
            // not attached yet, and on API 29 when there is no Activity either.
            val controller = ViewCompat.getWindowInsetsController(view) ?: return@SideEffect
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // The reader's own text size is passed through untweaked: the scale is not capped here, so a
    // surface that holds text must lay out to it (heightIn/weight plus a scroller) rather than to a
    // fixed height, or the text is clipped rather than shrunk.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = GatenShapes,
        content = content
    )
}
