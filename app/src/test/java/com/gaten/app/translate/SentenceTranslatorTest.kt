/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.translate

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one thing about translation that is pure enough to test off-device: what a failure means.
 *
 * A model that was never downloaded must read as "nothing to show here" rather than as an error, or
 * every lookup by a reader who never asked for translation would report a failure. The mapping is
 * tested on the error *code* rather than on an `MlKitException`, which cannot be constructed off a
 * device: its constructor calls into `android.text.TextUtils`.
 */
class SentenceTranslatorTest {

    @Test
    fun aModelThatWasNeverDownloadedIsNothingToShow() {
        val state = SentenceTranslator.stateForCode(MlKitException.NOT_FOUND, "Model not downloaded")

        assertEquals(SentenceTranslation.None, state)
    }

    @Test
    fun anyOtherMlKitFailureCarriesItsReason() {
        val state = SentenceTranslator.stateForCode(MlKitException.INVALID_ARGUMENT, "boom")

        assertEquals(SentenceTranslation.Failed("boom"), state)
    }

    @Test
    fun aFailureFromOutsideMlKitCarriesItsReason() {
        val state = SentenceTranslator.stateFor(IllegalStateException("boom"))

        assertEquals(SentenceTranslation.Failed("boom"), state)
    }
}
