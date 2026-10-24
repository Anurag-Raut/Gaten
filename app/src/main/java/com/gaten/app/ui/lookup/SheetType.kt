/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle

/**
 * The sheet's own type roles: the theme's, unchanged, in both docks.
 *
 * One scale is deliberate. A dock changes geometry and nothing else: 45% of a landscape screen is
 * still a comfortable measure for the house scale, and the responsive header plus the one body
 * scroller are what make room when the reader's own text size is large. The WaniKani reference
 * pages render over the lookup through these same roles.
 */
internal data class SheetType(
    /** The recognized source line: `bodyMedium`. */
    val header: TextStyle,
    /** The word itself: `displaySmall`, the one step read as a glyph. */
    val headword: TextStyle,
    /** Readings and runner-up headwords: `titleMedium`. */
    val reading: TextStyle,
    /** Metadata, mnemonics and runner-up glosses: `bodyMedium`. */
    val body: TextStyle,
    /** The numbered glosses and WaniKani's prose: `bodyLarge`. */
    val gloss: TextStyle,
    /** Notes and the full part-of-speech values: `bodySmall`. */
    val small: TextStyle,
    /** Section captions: `labelMedium`. */
    val caption: TextStyle,
    /** The full-POS caption, a step quieter than the sections above it: `labelSmall`. */
    val tiny: TextStyle,
    /** Word tabs and the one filled action: `labelLarge`. */
    val label: TextStyle
)

/** The sheet's roles, taken from the theme's own scale. */
@Composable
internal fun sheetType(): SheetType {
    val type = MaterialTheme.typography
    return remember(type) {
        SheetType(
            header = type.bodyMedium,
            headword = type.displaySmall,
            reading = type.titleMedium,
            body = type.bodyMedium,
            gloss = type.bodyLarge,
            small = type.bodySmall,
            caption = type.labelMedium,
            tiny = type.labelSmall,
            label = type.labelLarge
        )
    }
}
