/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.SelectedLine
import com.gaten.app.ocr.SelectionText
import com.gaten.app.translate.SentenceTranslation
import org.junit.Rule
import org.junit.Test

/**
 * The sheet's two halves, over a device: what a reader does — read the words, switch to the
 * sentence, read the translation — and what must never happen, which is one half hiding the other.
 *
 * The data is the sheet's own public input, so none of this needs OCR, a dictionary or a model, and
 * every case here is deterministic. What it does not cover — and cannot — is ML Kit's translation
 * itself, which needs the downloaded models.
 */
class ResultSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val source = "猫が水を飲む"

    private fun sentence(text: String) = SelectionText(
        text,
        listOf(SelectedLine(0, 0, text.length, Box(0, 100, 40 * text.length, 160), vertical = false))
    )

    private fun show(result: LookupResult) {
        compose.setContent {
            ResultSheet(
                result = result,
                onDismiss = {},
                onSelectAgain = {},
                layout = SheetLayout(SheetDock.Bottom, BOTTOM_SHEET_HEIGHT_FRACTION)
            )
        }
    }

    @Test
    fun aDictionaryFailureDoesNotHideTheTranslation() {
        show(
            LookupResult(
                ocrText = source,
                words = emptyList(),
                error = "no dictionary",
                sentence = sentence(source),
                translation = SentenceTranslation.Done("The cat drinks water.")
            )
        )

        // Words first, and the failure belongs to that half alone.
        compose.onNodeWithText("Dictionary unavailable: no dictionary").assertIsDisplayed()
        compose.onAllNodesWithText("The cat drinks water.").assertCountEquals(0)

        compose.onNodeWithText("Sentence").performClick()

        compose.onNodeWithText(source).assertIsDisplayed()
        compose.onNodeWithText("The cat drinks water.").assertIsDisplayed()
    }

    @Test
    fun aSentenceWithNoDictionaryWordsStillShowsItsTranslation() {
        show(
            LookupResult(
                ocrText = source,
                words = emptyList(),
                sentence = sentence(source),
                translation = SentenceTranslation.Done("The cat drinks water.")
            )
        )

        compose.onNodeWithText("No dictionary words found").assertIsDisplayed()

        compose.onNodeWithText("Sentence").performClick()

        compose.onNodeWithText("The cat drinks water.").assertIsDisplayed()
    }

    @Test
    fun theSentenceHalfShowsTheSourceAndTheAttribution() {
        show(
            LookupResult(
                ocrText = "別の行",
                words = emptyList(),
                sentence = sentence(source),
                translation = SentenceTranslation.Done("The cat drinks water.")
            )
        )

        compose.onNodeWithText("Sentence").performClick()

        // The source is not the header's text: the sentence the gesture selected is its own.
        compose.onNodeWithText(source).assertIsDisplayed()
        compose.onNodeWithText("Translated with Google Translate").assertIsDisplayed()
    }

    @Test
    fun aSentenceWithoutAModelSaysSoAndStillShowsTheSource() {
        show(
            LookupResult(
                ocrText = source,
                words = emptyList(),
                sentence = sentence(source),
                translation = SentenceTranslation.None
            )
        )

        compose.onNodeWithText("Sentence").performClick()

        compose.onNodeWithText(source).assertIsDisplayed()
        compose.onNodeWithText(
            "Translation is not installed. Add the offline translation models in Gaten."
        ).assertIsDisplayed()
    }

    @Test
    fun aLookupWithNothingAtAllCollapsesAndOffersNoSentence() {
        show(LookupResult(ocrText = "", words = emptyList()))

        compose.onNodeWithText("No text found").assertIsDisplayed()
        compose.onAllNodesWithText("Sentence").assertCountEquals(0)
    }
}
