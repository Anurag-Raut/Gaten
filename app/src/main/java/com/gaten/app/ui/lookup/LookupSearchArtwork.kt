/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Home's own dictionary-search mark, drawn for the lookup cover with the cover's theme roles.
 *
 * Home draws this in React Native SVG (`src/rn/ui/search/SearchArtwork.tsx`); the overlay is a
 * separate Compose window and cannot share that component, so this is the same geometry in the
 * smallest native representation. The viewBox, every coordinate, transform, stroke width and
 * opacity are Home's, so the two prompts draw the same picture; only the colour roles are named
 * here, and each is the native counterpart of Home's (`surface`, `surfaceVariant`,
 * `onSurfaceVariant` for Home's neutral ink, and `primary` for its accent).
 */
@Composable
internal fun LookupSearchArtwork(width: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    val surfaceVariant = colors.surfaceVariant
    // Home's iconSecondary and onSurfaceVariant are both neutral ink in the picture; the house
    // ladder has one role for that, and the source opacities keep the tones apart.
    val ink = colors.onSurfaceVariant
    val accent = colors.primary
    Canvas(modifier = modifier.size(width, width * (220f / 340f))) {
        val unit = size.width / 340f
        scale(unit, unit, pivot = Offset.Zero) {
            drawMark(surface = surface, surfaceVariant = surfaceVariant, ink = ink, accent = accent)
        }
    }
}

/** One mark's own canvas, in the 340 x 220 viewBox's own units. */
private fun DrawScope.drawMark(
    surface: androidx.compose.ui.graphics.Color,
    surfaceVariant: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color
) {
    drawOval(
        color = ink.copy(alpha = 0.04f),
        topLeft = Offset(99f, 176f),
        size = Size(142f, 8f)
    )

    drawPath(
        path = DashedArcPath,
        color = ink.copy(alpha = 0.16f),
        style = Stroke(
            width = 1.5f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 8f))
        )
    )

    rotate(degrees = -8f, pivot = Offset(142f, 103f)) {
        drawRoundRect(
            color = surface,
            topLeft = Offset(102f, 51f),
            size = Size(80f, 104f),
            cornerRadius = CornerRadius(13f),
            style = Stroke(width = 1.6f)
        )
        drawRoundRect(
            color = surfaceVariant.copy(alpha = 0.5f),
            topLeft = Offset(112f, 61f),
            size = Size(60f, 58f),
            cornerRadius = CornerRadius(8f)
        )
        translate(122f, 102f) {
            scale(0.04f, -0.04f, pivot = Offset.Zero) {
                drawPath(KanaAPath, ink.copy(alpha = 0.6f))
            }
        }
        drawPath(
            path = CardLinesPath,
            color = ink.copy(alpha = 0.23f),
            style = Stroke(width = 1.7f, cap = StrokeCap.Round)
        )
    }

    rotate(degrees = 9f, pivot = Offset(216f, 74f)) {
        drawRoundRect(
            color = surface,
            topLeft = Offset(193f, 49f),
            size = Size(46f, 50f),
            cornerRadius = CornerRadius(10f),
            style = Stroke(width = 1.5f)
        )
        translate(204f, 84f) {
            scale(0.025f, -0.025f, pivot = Offset.Zero) {
                drawPath(KanaKaPath, ink.copy(alpha = 0.45f))
            }
        }
    }

    drawCircle(color = surface, radius = 33f, center = Offset(190f, 125f))
    drawCircle(
        color = ink.copy(alpha = 0.65f),
        radius = 33f,
        center = Offset(190f, 125f),
        style = Stroke(width = 3f)
    )
    drawCircle(color = accent.copy(alpha = 0.03f), radius = 27f, center = Offset(190f, 125f))
    drawLine(
        color = ink.copy(alpha = 0.65f),
        start = Offset(212f, 149f),
        end = Offset(238f, 175f),
        strokeWidth = 6f,
        cap = StrokeCap.Round
    )
    drawPath(
        path = LensHighlightPath,
        color = ink.copy(alpha = 0.2f),
        style = Stroke(width = 2f, cap = StrokeCap.Round)
    )

    drawCircle(color = accent.copy(alpha = 0.08f), radius = 5f, center = Offset(89f, 91f))
    drawCircle(color = ink.copy(alpha = 0.18f), radius = 3f, center = Offset(253f, 132f))
    drawPath(
        path = SparkPath,
        color = accent.copy(alpha = 0.08f),
        style = Stroke(width = 1.8f, cap = StrokeCap.Round)
    )
}

/** Home's `SearchArtwork` paths, parsed once from the same strings the RN component draws. */
private val DashedArcPath: Path =
    PathParser().parsePathString("M87 123c-6-35 13-68 46-79M235 47c15 7 25 22 28 39").toPath()

private val CardLinesPath: Path =
    PathParser().parsePathString("M119 133h29M119 141h18").toPath()

private val LensHighlightPath: Path =
    PathParser().parsePathString("M171 122c1-9 7-16 16-18").toPath()

private val SparkPath: Path =
    PathParser().parsePathString("M251 105h10M256 100v10").toPath()

private val KanaAPath: Path = PathParser().parsePathString(
    "M613 441C571 329 510 248 444 185C433 243 426 304 426 368L427 409C473 426 531 441 " +
        "596 441ZM727 551 648 571C647 554 642 528 637 513L634 503L597 504C546 504 485 495 " +
        "429 479C432 521 435 563 439 602C562 608 695 622 800 640L799 714C697 690 575 677 " +
        "448 671L460 747C463 761 467 779 472 792L388 794C389 782 387 764 386 746L378 669L310 " +
        "668C267 668 180 675 145 681L147 606C188 603 266 599 309 599L370 600C366 553 361 503 " +
        "359 453C221 389 109 258 109 129C109 44 161 3 227 3C282 3 342 25 397 58L413 2L485 " +
        "24C477 49 469 76 461 105C546 177 627 288 684 430C777 403 828 335 828 259C828 129 716 " +
        "36 535 17L578 -50C810 -13 905 111 905 255C905 365 831 457 706 490L707 494C712 510 " +
        "721 537 727 551ZM356 378V360C356 285 366 204 380 133C329 97 281 80 242 80C204 80 185 " +
        "101 185 142C185 224 259 323 356 378Z"
).toPath()

private val KanaKaPath: Path = PathParser().parsePathString(
    "M855 579 799 607C782 604 762 602 735 602H497C499 635 501 669 502 705C503 729 505 764 " +
        "508 787H414C418 763 421 726 421 704C421 668 419 634 417 602H241C203 602 162 604 127 " +
        "608V523C162 527 203 527 242 527H410C383 321 311 196 212 106C182 77 141 49 109 32L182 " +
        "-27C349 88 453 240 489 527H769C769 420 756 174 718 98C707 73 689 65 660 65C618 65 565 " +
        "69 511 76L521 -7C573 -10 631 -14 682 -14C737 -14 769 5 789 47C834 143 846 434 850 " +
        "530C850 543 852 562 855 579Z"
).toPath()
