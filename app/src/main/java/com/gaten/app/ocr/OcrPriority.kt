/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Coordinates short user initiated OCR work with background work between native units. */
internal object OcrPriority {
    private val foregroundWaiters = MutableStateFlow(0)

    fun beginForeground() { foregroundWaiters.update { it + 1 } }

    fun endForeground() { foregroundWaiters.update { (it - 1).coerceAtLeast(0) } }

    fun foregroundPending(): Boolean = foregroundWaiters.value != 0

    /** Background work yields between native units; it never holds a lock while yielding. */
    suspend fun awaitBackgroundTurn() {
        foregroundWaiters.first { it == 0 }
    }
}
