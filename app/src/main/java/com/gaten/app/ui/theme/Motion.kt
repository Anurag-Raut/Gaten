/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween

/**
 * Material 3 motion tokens shared by every screen transition: the easing curves and durations the
 * screens animate with, so a slide into the review screen and a step change inside it move alike.
 *
 * The curves and durations follow m3.material.io/styles/motion: emphasized easing for moves that
 * cross a screen boundary, medium durations for in-place step changes and long ones for full
 * screen slides. [PAGE], [pressEasing] and [overshoot] are this app's own three tokens on top of
 * that — one page slide, one curve for a press, one spring for a selection — so the pace of the
 * whole app and the feel of a tap are each a single constant to tune.
 */
object Motion {
    /** M3 emphasized easing — the curve for a screen or step entering and leaving. */
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * The one page slide's curve: the same emphasized curve the screens have always used for moves
     * that cross a boundary, named for what it is — the single easing of every destination change,
     * push or pop.
     */
    val slideEasing = emphasized

    /** M3 emphasized decelerate — content arriving feels like it settles. */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 emphasized accelerate — content leaving gets out of the way quickly. */
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The curve a press answers a touch with, and the only one the press micro-animations use. */
    val pressEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** M3 short duration (150 ms): a local answer to a tap — a chip, a highlight, a badge. */
    const val SHORT = 150

    /** M3 medium duration (300 ms): step changes inside a screen. */
    const val MEDIUM = 300

    /** M3 long duration (450 ms): a full screen sliding in or out. */
    const val LONG = 450

    /**
     * One page slide (380 ms): what every in-app destination change takes — a tab, a push, a pop, a
     * review stage — so one constant tunes the whole app's pace. The compromise between the 450 ms a
     * full screen used and the 300 ms a step did.
     */
    const val PAGE = 380

    /**
     * The spring a selection or a confirmation lands with: a short overshoot past the value and back
     * (0.8 damping, 380 stiffness), for the pops a tween would make feel flat — a chip's selection, a
     * word that has just been answered.
     */
    val overshoot = SpringSpec<Float>(dampingRatio = 0.8f, stiffness = 380f)
}

/**
 * One page slide, shared by every surface that swaps a whole page: the navigation display, the review
 * screen's step, the lookup sheet's WaniKani page. Forward, the incoming page enters over its whole
 * width while the outgoing one stops [PAGE_PARALLAX] of the way out — cheap, and it reads as depth
 * rather than as two pages crossing. Back, the pair is mirrored: the incoming page starts a quarter of
 * the way out and the outgoing one leaves entirely.
 *
 * Z-order follows the same asymmetry: the arriving page draws on top going forward, and underneath
 * going back, so a pop uncovers the page it is returning to instead of covering it at once. No fade:
 * two pages fading through each other over the same move is what this replaces.
 */
internal fun <S> AnimatedContentTransitionScope<S>.pageSlide(forward: Boolean): ContentTransform {
    val towards = if (forward) SlideDirection.Start else SlideDirection.End
    val whole: (Int) -> Int = { it }
    val parallax: (Int) -> Int = { (it * PAGE_PARALLAX).toInt() }
    return ContentTransform(
        targetContentEnter = slideIntoContainer(
            towards,
            tween(Motion.PAGE, easing = Motion.slideEasing),
            if (forward) whole else parallax
        ),
        initialContentExit = slideOutOfContainer(
            towards,
            tween(Motion.PAGE, easing = Motion.slideEasing),
            if (forward) parallax else whole
        ),
        targetContentZIndex = if (forward) 1f else -1f
    )
}

/** How much of a page stays on screen while the next one covers it, or uncovers it going back. */
private const val PAGE_PARALLAX = 0.25f
