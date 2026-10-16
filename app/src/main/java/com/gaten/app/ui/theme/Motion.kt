/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Material 3 motion tokens shared by every screen transition: the easing curves and durations the
 * screens animate with, so a slide into the review screen and a step change inside it move alike.
 *
 * The curves and durations follow m3.material.io/styles/motion: emphasized easing for moves that
 * cross a screen boundary, medium durations for in-place step changes and long ones for full
 * screen slides.
 */
object Motion {
    /** M3 emphasized easing — the curve for a screen or step entering and leaving. */
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 emphasized decelerate — content arriving feels like it settles. */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 emphasized accelerate — content leaving gets out of the way quickly. */
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** M3 medium duration (300 ms): step changes inside a screen. */
    const val MEDIUM = 300

    /** M3 long duration (450 ms): a full screen sliding in or out. */
    const val LONG = 450
}
