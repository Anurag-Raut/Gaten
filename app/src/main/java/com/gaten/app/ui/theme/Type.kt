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
 * Manrope, the bundled face for everything Latin: one entry per weight the scale asks for.
 *
 * The file is variable, and its own default instance is ExtraLight (200) — an entry with no
 * variation setting of its own would draw every step at 200 — so each entry states the axis it
 * wants. The face covers Latin, Greek and Cyrillic and no CJK at all: a Japanese glyph is left to
 * the system's own face, which the text engine falls back to per glyph, rather than carrying the
 * megabytes a CJK font would add to the APK.
 */
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight): Font = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** The Latin face: normal for prose, medium for controls, semibold for headings. */
private val Latin = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold)
)

/*
 * The app's one type scale: a title step per level of the hierarchy, a body step per density of
 * prose, and a label step for controls, chips and captions. Latin text is drawn in [Latin]; a
 * Japanese glyph falls back to the system face, which is why nothing here is Bold — CJK faces render
 * that as a smear — and why the display steps, which are always a Japanese word, are the system face
 * at Medium instead of the bundled one.
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
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    // A bar or section title.
    titleLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    // A card heading, a reading and the headword's neighbours. A reading is Japanese, so this step
    // stays Medium: a fallback face answers for the weight the text asks for, and 600 is the weight
    // the CJK faces draw heaviest of all.
    titleMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 26.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    // Prose: dictionary glosses, mnemonics, card answers read as text.
    bodyLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    // Controls, chips and buttons.
    labelLarge = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp
    ),
    // The quietest step: captions and badges around the content.
    labelSmall = TextStyle(
        fontFamily = Latin,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
