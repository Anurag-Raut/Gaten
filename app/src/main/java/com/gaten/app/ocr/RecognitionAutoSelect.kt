/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * The one automatic mode change Gaten makes: after the Enhanced pack finishes installing, select it
 * for a reader who has not chosen a mode since that download began.
 *
 * The rule is kept separate from the preference store and from Play so it can be pinned by a JVM
 * test. The caller supplies the download's outcome and the window's bookkeeping; this object only
 * decides whether the stored mode should change, never writes it.
 */
internal object RecognitionAutoSelect {
    /**
     * True when a completed pack should become the selected engine.
     *
     * [downloadWindowOpen] is what ties the decision to *this* download: the native bridge opens one
     * when Play starts transferring (or when the reader asks for the install) and closes it once the
     * completion has been handled. A completion outside such a window — an old pack Play delivered
     * before this process ever watched it — changes nothing. [manualChoiceSinceDownloadBegan] is the
     * reader's veto: any mode they pick while the window is open wins, even if it is the bundled
     * engine. An already-selected Enhanced mode is left alone, which also makes repeat completion
     * reports harmless.
     */
    fun shouldSelectEnhanced(
        downloadCompleted: Boolean,
        downloadWindowOpen: Boolean,
        manualChoiceSinceDownloadBegan: Boolean,
        currentMode: RecognitionMode
    ): Boolean = downloadCompleted &&
        downloadWindowOpen &&
        !manualChoiceSinceDownloadBegan &&
        currentMode != RecognitionMode.PP_MEDIUM
}
