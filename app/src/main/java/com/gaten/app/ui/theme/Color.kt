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
 * Only the roles Gaten actually draws with (or aliases in Theme.kt) are named here. The container
 * roles are the tonal neighbours of the accent's hue: leaving them at the library defaults would
 * pull the baseline purple scheme back into the UI, since dynamic colour is deliberately off.
 */

// --- Neutral and status tones: the same whatever the accent ---

// --- Light ---

val LightBackground = Color(0xFFFAFAF9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F1EF)
val LightSurfaceContainer = Color(0xFFF1F1EF)
val LightSurfaceContainerHigh = Color(0xFFEAEAE7)
val LightSurfaceContainerHighest = Color(0xFFE4E4E1)
val LightOnSurface = Color(0xFF1A1A1A)
val LightOnSurfaceVariant = Color(0xFF6B6B6B)
val LightOutline = Color(0xFFE4E4E1)
val LightError = Color(0xFFC62828)
val LightErrorContainer = Color(0xFFFFEBEE)
val LightOnErrorContainer = Color(0xFFB71C1C)

// --- Dark ---

val DarkBackground = Color(0xFF111111)
val DarkSurface = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF242424)
val DarkSurfaceContainer = Color(0xFF242424)
val DarkSurfaceContainerHigh = Color(0xFF2B2B2B)
val DarkSurfaceContainerHighest = Color(0xFF2E2E2E)
val DarkOnSurface = Color(0xFFEDEDED)
val DarkOnSurfaceVariant = Color(0xFF9A9A9A)
val DarkOutline = Color(0xFF2E2E2E)
val DarkError = Color(0xFFEF9A9A)
val DarkErrorContainer = Color(0xFFB71C1C)
val DarkOnErrorContainer = Color(0xFFFFEBEE)

// --- Accents ---

/** The accent the reader picked in Settings. [Indigo] is the app's own accent and the default. */
enum class AccentColor { Indigo, Blue, Violet, Teal, Green, Rose, Graphite }

/** One accent's tones for one mode: everything in the UI that is neither a neutral nor an error. */
data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiary: Color
)

/**
 * One accent's tones in one mode. [onPrimary] is not the accent's business — it is the near-black
 * or near-white that reads on [primary], and the same on every accent because every accent's
 * [primary] carries the same luminance by construction (see the table below).
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
 * The accents, as (primary, primaryContainer, onPrimaryContainer, tertiary) per mode.
 *
 * Indigo is the app's own accent, one Material step deeper than the palette it shipped with
 * (0xFF3F51B5 → 0xFF3949AB), which is the weight the rest of the app's accents are now drawn at.
 * Each of the others is that family's hue moved to its own hue and every tone's lightness corrected
 * to hold the relative luminance the *previous* indigo primary had — a hue may change the colour of
 * the overlay, but not how well it reads. That correction is what stops the trap a plain hue
 * rotation falls into: at that lightness, teal and green come out at 2.5:1 over a white page, which
 * is a lasso the reader cannot see.
 *
 * Measured, so a change here can be checked rather than eyeballed. Indigo's own light primary reads
 * 7.73:1 over white. The other six, all at the luminance they were built to: primary 6.87:1 over
 * white and 8.17:1 over the dark background, the container pair 11:1 in either mode, tertiary
 * 3.45:1 over white.
 */

/** The shipped indigo palette, bolder by one step, and the reference every other accent is measured against. */
private val IndigoLight = tones(0xFF3949AB, 0xFFE8EAF6, 0xFF1A237E, 0xFF7986CB, dark = false)
private val IndigoDark = tones(0xFF9FA8DA, 0xFF283593, 0xFFE8EAF6, 0xFFC5CAE9, dark = true)

private val BlueLight = tones(0xFF305D8A, 0xFFE3ECF4, 0xFF113151, 0xFF5C8EC0, dark = false)
private val BlueDark = tones(0xFF8AAED2, 0xFF1C4267, 0xFFE3ECF4, 0xFFB8CEE4, dark = true)

private val VioletLight = tones(0xFF6641BC, 0xFFEDE9F6, 0xFF37197B, 0xFF957DCD, dark = false)
private val VioletDark = tones(0xFFB3A2DB, 0xFF492894, 0xFFEDE9F6, 0xFFD1C6EA, dark = true)

private val TealLight = tones(0xFF236460, 0xFFD7EFEE, 0xFF0B3633, 0xFF3B9892, dark = false)
private val TealDark = tones(0xFF4EBBB4, 0xFF134744, 0xFFD7EFEE, 0xFF96D7D3, dark = true)

private val GreenLight = tones(0xFF246640, 0xFFD9F0E3, 0xFF0B371E, 0xFF3C9B65, dark = false)
private val GreenDark = tones(0xFF56BE83, 0xFF14492B, 0xFFD9F0E3, 0xFF9BD9B6, dark = true)

private val RoseLight = tones(0xFF9A365B, 0xFFF6E7EC, 0xFF5C132E, 0xFFC76F8F, dark = false)
private val RoseDark = tones(0xFFD899B0, 0xFF74203F, 0xFFF6E7EC, 0xFFE8C2D0, dark = true)

/** The colourless accent: no hue at all, the same luminance. */
private val GraphiteLight = tones(0xFF5A5A5A, 0xFFEAEAEA, 0xFF303030, 0xFF8A8A8A, dark = false)
private val GraphiteDark = tones(0xFFAAAAAA, 0xFF404040, 0xFFEAEAEA, 0xFFCBCBCB, dark = true)

/** [accent]'s tones for [dark]. */
fun accentRoles(accent: AccentColor, dark: Boolean): AccentRoles = when (accent) {
    AccentColor.Indigo -> if (dark) IndigoDark else IndigoLight
    AccentColor.Blue -> if (dark) BlueDark else BlueLight
    AccentColor.Violet -> if (dark) VioletDark else VioletLight
    AccentColor.Teal -> if (dark) TealDark else TealLight
    AccentColor.Green -> if (dark) GreenDark else GreenLight
    AccentColor.Rose -> if (dark) RoseDark else RoseLight
    AccentColor.Graphite -> if (dark) GraphiteDark else GraphiteLight
}

// --- Palettes ---

/**
 * The palettes the reader can pick between. A palette recolours the neutral ladder — paper, its
 * hairline and the ink written on it — without changing the reader's selected accent.
 *
 * [Gaten] carries no tone: its ladder is the one the app shipped with, written out, and no single
 * (hue, saturation) pair reproduces it — its light paper is warm while its ink is neutral, and its
 * dark ladder is pure grey. Every other palette is that ladder retinted.
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
 * The tint one palette puts on a mode. Paper and ink take the same hue but different saturations:
 * at the paper's saturation the ink turns into coloured text, and the palette the app shipped with
 * sets the precedent — a warm paper, a neutral ink.
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
 * holds it — so a palette moves the hue of the paper and the ink without moving a single contrast
 * ratio in the app. That is the whole contract: a palette changes the mood, never the readability.
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
 * usually between two steps. The step taken is the one *at or above* the shipped luminance, never
 * the one below: a palette may make a role a channel brighter, but it may not make the ink fainter
 * or the paper darker than the app shipped with. Measured across every palette, that holds every
 * readable pair within 0.64% of the contrast it had.
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
