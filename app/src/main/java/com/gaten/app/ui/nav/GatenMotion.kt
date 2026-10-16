/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.pageSlide

/**
 * The app's one destination move: [pageSlide] — a horizontal page slide of [Motion.PAGE] ms on
 * [Motion.slideEasing], with no fade — for a tab, a push, a pop and a previewed back gesture alike.
 *
 * Which way it goes is a tab's business: a tab's place in [GatenTab.entries] orders the four, so a
 * move to a later tab slides forward and one back to an earlier tab slides back, whichever of the
 * three specs the display is asking from; two scenes of one tab have no order of their own, so there
 * the asking spec decides — a push is forward, a pop is back. [pageSlide] slides on the
 * direction-aware container pair, so an RTL layout mirrors the whole move with no second rule.
 */
internal val gatenTransitionSpec:
    AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform =
    { pageSlide(forward = tabForward(sameTab = true)) }

internal val gatenPopTransitionSpec:
    AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform =
    { pageSlide(forward = tabForward(sameTab = false)) }

/** As [gatenPopTransitionSpec]: the gesture drives it, so all this decides is the two ends. */
internal val gatenPredictivePopTransitionSpec:
    AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform =
    { _ -> pageSlide(forward = tabForward(sameTab = false)) }

/**
 * Whether a tab change goes forward: a target tab later in [GatenTab.entries] than the initial one
 * does, and an earlier one comes back. [sameTab] answers for the two scenes of one tab, and for a key
 * this app did not stamp.
 */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.tabForward(sameTab: Boolean): Boolean {
    val from = tabIndexOf(initialState.entries.lastOrNull()?.contentKey)
    val to = tabIndexOf(targetState.entries.lastOrNull()?.contentKey)
    return when {
        from == null || to == null || from == to -> sameTab
        else -> to > from
    }
}

/** Where [contentKey]'s tab sits in the bar, or null for a key this app did not stamp. */
private fun tabIndexOf(contentKey: Any?): Int? {
    val stamp = tabStampOf(contentKey) ?: return null
    val index = GatenTab.entries.indexOfFirst { it.name == stamp }
    return index.takeIf { it >= 0 }
}
