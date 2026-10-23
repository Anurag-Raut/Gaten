/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.ui.unit.dp

/** The only spacing steps in the app; anything between them is a mistake, not a nuance. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /**
     * The house content inset (22 dp): the measure every page's column is set in from the screen
     * edge, reduced to [l] on a window narrower than 360 dp. It is the one step off the ladder above,
     * and it is here because a page's margin is a composition, not a gap between two elements.
     */
    val content = 22.dp
}
