/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gaten.app.ui.theme.Motion

/** How far a pressed surface shrinks: enough to answer the touch, not enough to move the layout. */
private const val PressedScale = 0.98f

/**
 * The app's one answer to a touch: the surface scales from 1.0 to [pressedScale] while a finger is on
 * it and eases back on release, over [Motion.SHORT] with [Motion.pressEasing] — instead of the
 * elevation bump a Material card would otherwise jump by.
 *
 * It observes a press; it never handles one. The caller passes the *same* [interactionSource] it gave
 * its own tappable, so the control's own press state drives the scale and nothing here consumes,
 * re-detects or duplicates a click — and a control that is disabled, whose press is cancelled (a drag
 * that turns into a scroll), or that is activated from a keyboard reports no press at all, so the
 * surface the reader's finger is really on is the only one that moves.
 *
 * ```
 * val interactions = remember { MutableInteractionSource() }
 * Card(
 *     onClick = …,
 *     interactionSource = interactions,
 *     modifier = Modifier.pressScale(interactions)
 * )
 * ```
 *
 * A `Modifier.clickable` takes an interaction source too, so a row that is not a Material component
 * is wired the same way. A tappable that owns its source and does not expose it cannot be scaled from
 * here; that is the one case this does not cover.
 *
 * The scale is drawn in a layer and read at draw time, so a frame of the animation never recomposes
 * the surface it is attached to.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PressedScale
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.SHORT, easing = Motion.pressEasing),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
