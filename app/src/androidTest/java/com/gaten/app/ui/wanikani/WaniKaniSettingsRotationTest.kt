/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.wanikani

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaten.app.R
import com.gaten.app.wanikani.WaniKaniRepository
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The token field is plain `remember`, not `rememberSaveable`: a configuration change (rotation, a
 * process recreation) must not carry what was typed into the saved instance state, where Android
 * writes it out unencrypted.
 *
 * Nothing is connected here. The dummy text is typed into the field only, and never verified,
 * stored or sent anywhere - the test asserts on the field's own state through the Connect button,
 * which is enabled exactly while the field holds something.
 */
@RunWith(AndroidJUnit4::class)
class WaniKaniSettingsRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    @Test
    fun typedTokenIsNotRestoredWhenScreenIsRecreated() {
        assumeFalse(WaniKaniRepository.isConfigured(context))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WaniKaniSettingsScreen(onClose = {}) }

        val connect = compose.onNodeWithText(string(R.string.wk_settings_connect))
        connect.assertIsNotEnabled()
        fieldIsEmpty()

        compose.onNode(hasSetTextAction()).performTextInput("dummy-token-not-a-real-one")
        connect.assertIsEnabled()

        // What an activity recreation does with the saved instance state: the composition is
        // disposed and rebuilt from it. A token held in a plain `remember` is gone.
        restoration.emulateSavedInstanceStateRestore()

        connect.assertIsNotEnabled()
        fieldIsEmpty()
    }

    private fun fieldIsEmpty() = compose.onNode(hasSetTextAction()).assert(
        SemanticsMatcher("the token field holds nothing") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
        }
    )
}
