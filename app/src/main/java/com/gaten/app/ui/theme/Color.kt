/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * The palette is near-monochrome neutrals plus exactly one accent. The accent is the reader's
 * choice in Settings ([AccentColor]); indigo (藍) is the one the app shipped with and the default.
 *
 * The neutrals and the status colours do not follow the accent: a red error stays red and the greys
 * stay grey. Only the accent moves.
 *
 * Only the roles Gaten actually draws with (or aliases in Theme.kt) are named here. Accent
 * containers follow the selected accent; neutral action containers come from the neutral ladder.
 */

// --- Neutral and status tones: the same whatever the accent ---

// --- Light ---

val LightBackground = Color(0xFFFDFDFD)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF6F6F8)
val LightSurfaceContainer = Color(0xFFF6F6F8)
val LightSurfaceContainerHigh = Color(0xFFF6F6F8)
val LightSurfaceContainerHighest = Color(0xFFF6F6F8)
val LightOnSurface = Color(0xFF17171A)
val LightOnSurfaceVariant = Color(0xFF6C6C75)
val LightOutline = Color(0xFFE9E9ED)
val LightError = Color(0xFFB42335)
val LightErrorContainer = Color(0xFFFFEBEE)
val LightOnErrorContainer = Color(0xFFB71C1C)

// --- Dark ---

val DarkBackground = Color(0xFF101114)
val DarkSurface = Color(0xFF17181C)
val DarkSurfaceVariant = Color(0xFF1B1C21)
val DarkSurfaceContainer = Color(0xFF1B1C21)
val DarkSurfaceContainerHigh = Color(0xFF1C1D21)
val DarkSurfaceContainerHighest = Color(0xFF303136)
val DarkOnSurface = Color(0xFFF5F5F6)
val DarkOnSurfaceVariant = Color(0xFFB0B0BA)
val DarkOutline = Color(0xFF303136)
val DarkError = Color(0xFFEF9A9A)
val DarkErrorContainer = Color(0xFFB71C1C)
val DarkOnErrorContainer = Color(0xFFFFEBEE)

// --- Accents ---

/** The accent the reader picked in Settings. [Indigo] is the app's own accent and the default. */
enum class AccentColor { Indigo, Blue, Violet, Teal, Green, Rose, Black }

/** One accent's tones for one mode, kept separate from neutral and error roles. */
data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiary: Color
)

/**
 * One accent's roles in one mode, including the foreground that reads on its primary tone.
 */
private fun tones(
    primary: Long,
    container: Long,
    onContainer: Long,
    tertiary: Long,
    dark: Boolean
) = AccentRoles(
    primary = Color(primary),
    onPrimary = if (dark) Color(0xFF111111) else Color(0xFFFFFFFF),
    primaryContainer = Color(container),
    onPrimaryContainer = Color(onContainer),
    tertiary = Color(tertiary)
)

/*
 * Accent tones by role and mode. The ladder is pitched for contrast rather than for tinting: in
 * light mode a primary is deep enough to carry white ink and to be read as text on the paper, and in
 * dark mode it is light enough to carry the near-black ink `tones` gives that mode. Every accent is
 * a three-step ramp of one hue — primary, a mid tertiary for charts, a soft container.
 */

/** Default indigo accent tones. */
private val IndigoLight = tones(0xFF4338CA, 0xFFEEF2FF, 0xFF1E1B4B, 0xFF6366F1, dark = false)
private val IndigoDark = tones(0xFFA5B4FC, 0xFF312E81, 0xFFE0E7FF, 0xFF6366F1, dark = true)

private val BlueLight = tones(0xFF0B4FB8, 0xFFEFF6FF, 0xFF172554, 0xFF2563EB, dark = false)
private val BlueDark = tones(0xFF93C5FD, 0xFF1E3A8A, 0xFFDBEAFE, 0xFF3B82F6, dark = true)

private val VioletLight = tones(0xFF6D28D9, 0xFFF5F3FF, 0xFF2E1065, 0xFF7C3AED, dark = false)
private val VioletDark = tones(0xFFC4B5FD, 0xFF4C1D95, 0xFFEDE9FE, 0xFF8B5CF6, dark = true)

private val TealLight = tones(0xFF115E59, 0xFFF0FDFA, 0xFF042F2E, 0xFF0D9488, dark = false)
private val TealDark = tones(0xFF5EEAD4, 0xFF134E4A, 0xFFCCFBF1, 0xFF14B8A6, dark = true)

private val GreenLight = tones(0xFF166534, 0xFFF0FDF4, 0xFF052E16, 0xFF16A34A, dark = false)
private val GreenDark = tones(0xFF6EE7B7, 0xFF14532D, 0xFFDCFCE7, 0xFF10B981, dark = true)

private val RoseLight = tones(0xFF9F1239, 0xFFFFF1F2, 0xFF4C0519, 0xFFE11D48, dark = false)
private val RoseDark = tones(0xFFFDA4AF, 0xFF881337, 0xFFFFE4E6, 0xFFF43F5E, dark = true)

/** The colourless accent: the same ramp with no hue — black ink in light, white in dark. */
private val BlackLight = tones(0xFF000000, 0xFFEAEAEA, 0xFF17171A, 0xFF4A4A4A, dark = false)
private val BlackDark = tones(0xFFFFFFFF, 0xFF404040, 0xFFEAEAEA, 0xFFCBCBCB, dark = true)

/** [accent]'s tones for [dark]. */
fun accentRoles(accent: AccentColor, dark: Boolean): AccentRoles = when (accent) {
    AccentColor.Indigo -> if (dark) IndigoDark else IndigoLight
    AccentColor.Blue -> if (dark) BlueDark else BlueLight
    AccentColor.Violet -> if (dark) VioletDark else VioletLight
    AccentColor.Teal -> if (dark) TealDark else TealLight
    AccentColor.Green -> if (dark) GreenDark else GreenLight
    AccentColor.Rose -> if (dark) RoseDark else RoseLight
    AccentColor.Black -> if (dark) BlackDark else BlackLight
}

// --- Palettes ---

/**
 * The palettes the reader can pick between. A palette recolours the neutral ladder — paper, its
 * hairline and the ink written on it — without changing the reader's selected accent.
 *
 * [Gaten] carries no tone: it is the Ink neutral ladder. Every other palette is that ladder
 * retinted while the reader's selected accent stays independent.
 */
enum class Palette(
    private val lightTone: PaletteTone?,
    private val darkTone: PaletteTone?
) {
    Gaten(null, null),
    Mono(PaletteTone(0f, 0f, 0f), PaletteTone(0f, 0f, 0f)),
    Cool(PaletteTone(215f, 0.30f, 0.10f), PaletteTone(215f, 0.25f, 0.12f)),
    Warm(PaletteTone(45f, 0.45f, 0.10f), PaletteTone(45f, 0.30f, 0.12f));

    internal fun tone(dark: Boolean): PaletteTone? = if (dark) darkTone else lightTone
}

/**
 * The tint one palette puts on a mode. Paper and ink use separate saturation values so text remains
 * visually neutral while the surface carries the selected palette hue.
 */
data class PaletteTone(val hue: Float, val paper: Float, val ink: Float)

/** The neutral ladder for one mode: paper, its hairline, and the ink written on it. */
data class NeutralRoles(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color
)

/** The ladder the app shipped with: [Palette.Gaten] itself, and the basis of every other palette. */
private fun shippedNeutrals(dark: Boolean): NeutralRoles = if (dark) {
    NeutralRoles(
        background = DarkBackground,
        surface = DarkSurface,
        surfaceVariant = DarkSurfaceVariant,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        onSurface = DarkOnSurface,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline
    )
} else {
    NeutralRoles(
        background = LightBackground,
        surface = LightSurface,
        surfaceVariant = LightSurfaceVariant,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
        onSurface = LightOnSurface,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline
    )
}

/**
 * [palette]'s neutral ladder for [dark].
 *
 * Every tone is retinted at its own relative luminance — [retinted] solves for the lightness that
 * holds it — so a palette changes hue while preserving the contrast ladder.
 */
fun paletteNeutrals(palette: Palette, dark: Boolean): NeutralRoles {
    val shipped = shippedNeutrals(dark)
    val tone = palette.tone(dark) ?: return shipped
    return NeutralRoles(
        background = shipped.background.retinted(tone.hue, tone.paper),
        surface = shipped.surface.retinted(tone.hue, tone.paper),
        surfaceVariant = shipped.surfaceVariant.retinted(tone.hue, tone.paper),
        surfaceContainer = shipped.surfaceContainer.retinted(tone.hue, tone.paper),
        surfaceContainerHigh = shipped.surfaceContainerHigh.retinted(tone.hue, tone.paper),
        surfaceContainerHighest = shipped.surfaceContainerHighest.retinted(tone.hue, tone.paper),
        onSurface = shipped.onSurface.retinted(tone.hue, tone.ink),
        onSurfaceVariant = shipped.onSurfaceVariant.retinted(tone.hue, tone.ink),
        outline = shipped.outline.retinted(tone.hue, tone.paper)
    )
}

/**
 * [this] in the given hue and saturation, at the first lightness whose tone reaches this colour's
 * relative luminance.
 *
 * `Color.hsl` lands in 8-bit channels, so the ladder is a staircase and the exact lightness is
 * usually between two steps. Choose the first step at or above the source luminance.
 */
private fun Color.retinted(hue: Float, saturation: Float): Color {
    val target = luminance()
    var low = 0f
    var high = 1f
    repeat(24) {
        val mid = (low + high) / 2f
        if (Color.hsl(hue, saturation, mid).luminance() < target) low = mid else high = mid
    }
    return Color.hsl(hue, saturation, high)
}
