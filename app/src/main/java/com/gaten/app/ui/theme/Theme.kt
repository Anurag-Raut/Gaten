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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat

/**
 * The palette for one palette, accent and mode: the accent's own tones, the palette's neutral
 * ladder, and the Material roles Gaten never draws aliased onto the accent's — a component reaching
 * for `secondaryContainer` or `tertiaryContainer` lands on the accent rather than on the library's
 * baseline purple, which is the one hue this palette never shows.
 */
private fun gatenColorScheme(palette: Palette, accent: AccentColor, dark: Boolean): ColorScheme {
    val tone = accentRoles(accent, dark)
    val neutral = paletteNeutrals(palette, dark)
    return if (dark) {
        darkColorScheme(
            primary = tone.primary,
            onPrimary = tone.onPrimary,
            primaryContainer = tone.primaryContainer,
            onPrimaryContainer = tone.onPrimaryContainer,
            tertiary = tone.tertiary,
            secondary = tone.primary,
            onSecondary = tone.onPrimary,
            secondaryContainer = tone.primaryContainer,
            onSecondaryContainer = tone.onPrimaryContainer,
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
            outline = neutral.outline,
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
            secondaryContainer = tone.primaryContainer,
            onSecondaryContainer = tone.onPrimaryContainer,
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
            outline = neutral.outline,
            error = LightError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer
        )
    }
}

private val GatenShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp)
)

/** Maximum font scale factor the app will honour (1.3 = 130% of normal text size). */
private const val MAX_FONT_SCALE = 1.3f

/**
 * The app's only theme: the Gaten palette and type scale. Dark or light comes from [mode], the
 * neutral ladder from [palette] and the accent from [accent] — all three the reader's choice in
 * Settings, defaulting to the system setting and to the shipped palette. Dynamic colour is
 * deliberately not used: the colours are chosen here, not taken from a wallpaper.
 */
@Composable
fun GatenTheme(
    mode: ThemeMode = ThemeMode.System,
    palette: Palette = Palette.Gaten,
    accent: AccentColor = AccentColor.Indigo,
    content: @Composable () -> Unit
) {
    val darkTheme = mode.isDark(isSystemInDarkTheme())
    // Built once per choice rather than once per screen recomposition.
    val colorScheme = remember(palette, accent, darkTheme) {
        gatenColorScheme(palette, accent, darkTheme)
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

    // Cap fontScale so that sp-based text doesn't grow unboundedly on devices
    // where the user has set very large display scale (150-200%).
    val currentDensity = LocalDensity.current
    val cappedDensity = if (currentDensity.fontScale > MAX_FONT_SCALE) {
        Density(
            density = currentDensity.density,
            fontScale = MAX_FONT_SCALE
        )
    } else {
        currentDensity
    }

    CompositionLocalProvider(LocalDensity provides cappedDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = GatenShapes,
            content = content
        )
    }
}
