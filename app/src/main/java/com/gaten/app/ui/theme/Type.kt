/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gaten.app.R

/**
 * Inter, the bundled face for everything Latin: one entry per weight the scale asks for.
 *
 * The variable font's weight axis is set explicitly for each entry. The face covers Latin, Greek
 * and Cyrillic and no CJK at all: a Japanese glyph is left to
 * the system's own face, which the text engine falls back to per glyph, rather than carrying the
 * megabytes a CJK font would add to the APK.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight): Font = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** The Latin face, including the weights used by the shared type scale. */
private val Latin = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold)
)

/*
 * The app's one type scale: a title step per level of the hierarchy, a body step per density of
 * prose, and a label step for controls, chips and captions. Latin text is drawn in [Latin]; a
 * Japanese glyph falls back to the system face; the display steps, which are always Japanese words,
 * use that system face directly.
 *
 * Every step carries an explicit line height, because text that is read in bulk — a dictionary
 * entry, a mnemonic, a card question — needs a rhythm the platform default does not give it, and a
 * Japanese glyph is taller than a Latin one at the same size.
 *
 * Sizes are in sp and so grow with the reader's own display size. That size is not capped anywhere:
 * a surface that has to hold this text must lay out to it — `heightIn`/`weight` and a scroller, not
 * a fixed `.height`.
 */
val Typography = Typography(
    // The review card's front word: the largest thing the app draws, and a Japanese one, so it is the
    // system face at Medium like the headword below it.
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp
    ),
    // The large Japanese headword in the lookup sheet: the one step the reader reads as a glyph.
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 44.sp
    ),
    // A screen's own title, when it is not in a bar.
    headlineSmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Bold,
        fontSize = 35.sp,
        lineHeight = 38.sp
    ),
    // A bar or section title.
    titleLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 21.sp
    ),
    // Item and settings row titles.
    titleMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // Prose: dictionary glosses, mnemonics, card answers read as text.
    bodyLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    // Controls, chips and buttons.
    labelLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // The quietest step: captions and badges around the content.
    labelSmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)
