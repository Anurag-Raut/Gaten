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
import androidx.compose.ui.draw.alpha
import com.gaten.app.ui.theme.Motion

/** How far a pressed control dims: enough to answer the touch, not enough to lose the label. */
private const val PressedAlpha = 0.72f

/**
 * The overlay's one answer to a touch: the control fades from alpha 1.0 to [PressedAlpha] while a
 * finger is on it and eases back on release, over [Motion.SHORT] with [Motion.pressEasing].
 *
 * Opacity, not scale: these controls sit on a frozen screenshot inside a window whose geometry the
 * capture mapping depends on, and a scaled control would move pixels the reader is aiming at. The
 * whole overlay is drawn over somebody else's content, so the feedback has to stay inside the
 * control's own box.
 *
 * It observes a press; it never handles one. The caller passes the *same* [interactionSource] it gave
 * its own tappable, so the control's own press state drives the fade and nothing here consumes,
 * re-detects or duplicates a click — and a control that is disabled, whose press is cancelled (a drag
 * that turns into a scroll), or that is activated from a keyboard reports no press at all.
 *
 * ```
 * val interactions = remember { MutableInteractionSource() }
 * IconButton(onClick = …, modifier = Modifier.pressOpacity(interactions)) { … }
 * ```
 *
 * A `Modifier.clickable` takes an interaction source too, so a row that is not a Material component
 * is wired the same way.
 */
@Composable
fun Modifier.pressOpacity(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val fade = animateFloatAsState(
        targetValue = if (pressed) PressedAlpha else 1f,
        animationSpec = tween(Motion.SHORT, easing = Motion.pressEasing),
        label = "pressOpacity"
    )
    return this.alpha(fade.value)
}
