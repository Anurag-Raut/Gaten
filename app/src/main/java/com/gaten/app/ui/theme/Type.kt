/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gaten.app.R

/**
 * IBM Plex Sans JP, the bundled face for everything the app draws: one static file per weight the
 * scale asks for.
 *
 * The JP faces carry IBM Plex's Latin design alongside the Japanese glyphs, so one family covers
 * both scripts and a mixed string is drawn in one face. The Latin is about 5 % larger on the em than
 * IBM Plex Sans's own, which is how the face sits with the kana. A glyph the face does not carry
 * (a rare symbol, an astral-plane kanji) still falls back to the platform's own, as any font does.
 */
private val Plex = FontFamily(
    Font(R.font.ibm_plex_sans_jp_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_jp_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_jp_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_jp_bold, FontWeight.Bold)
)

/*
 * The app's one type scale: a title step per level of the hierarchy, a body step per density of
 * prose, and a label step for controls, chips and captions. Every step is drawn in [Plex], which
 * carries both the Latin and the Japanese glyphs the app writes.
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
    // The review card's front word: the largest thing the app draws, and a Japanese one, so it is
    // [Plex] at Medium like the headword below it.
    displayMedium = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp
    ),
    // The large Japanese headword in the lookup sheet: the one step the reader reads as a glyph.
    displaySmall = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 44.sp
    ),
    // A screen's own title, when it is not in a bar.
    headlineSmall = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Bold,
        fontSize = 35.sp,
        lineHeight = 38.sp
    ),
    // A bar or section title.
    titleLarge = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 21.sp
    ),
    // Item and settings row titles.
    titleMedium = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // Prose: dictionary glosses, mnemonics, card answers read as text.
    bodyLarge = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    // Controls, chips and buttons.
    labelLarge = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // The quietest step: captions and badges around the content.
    labelSmall = TextStyle(
        fontFamily = Plex,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)
