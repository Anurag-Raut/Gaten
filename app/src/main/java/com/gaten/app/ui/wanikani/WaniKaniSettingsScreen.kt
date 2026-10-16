/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.wanikani

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.wanikani.WaniKaniConnection
import com.gaten.app.wanikani.WaniKaniRepository
import kotlinx.coroutines.launch

/** WaniKani's own level count; an account granted all of them needs no note about what is covered. */
private const val WANIKANI_LEVELS = 60

/** Where a token is created, opened in the browser from this screen. */
private const val WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens"

/**
 * The WaniKani token: what connecting does, the token itself, and the way back out.
 *
 * The screen holds no token of its own. The field is plain [remember], not [rememberSaveable], so a
 * rotation clears it instead of writing a secret into the saved instance state, and it is emptied
 * again the moment a token is accepted — a saved token is never shown a second time.
 *
 * Connecting verifies before it stores ([WaniKaniRepository.connect]), so a token on the device is
 * always one WaniKani has accepted; a rejected or unreachable token is only reported, and the
 * device is left exactly as it was. Disconnect is the reverse and takes the cache with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaniKaniSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(WaniKaniRepository.isConfigured(context)) }
    var busy by remember { mutableStateOf(false) }
    // The last answer from WaniKani, and the outcome of a disconnect: null while nothing was
    // disconnected, true when the token and the whole cache went, false when part of it did not.
    var answer by remember { mutableStateOf<WaniKaniConnection?>(null) }
    var cleared by remember { mutableStateOf<Boolean?>(null) }

    val message = when (val current = answer) {
        null -> if (cleared == null) {
            null
        } else {
            stringResource(
                if (cleared == true) {
                    R.string.wk_settings_disconnected
                } else {
                    R.string.wk_settings_disconnect_failed
                }
            )
        }
        is WaniKaniConnection.Connected -> if (current.maxLevelGranted < WANIKANI_LEVELS) {
            stringResource(R.string.wk_settings_connected_levels, current.maxLevelGranted)
        } else {
            stringResource(R.string.wk_settings_connected)
        }

        WaniKaniConnection.InvalidToken -> stringResource(R.string.wk_settings_invalid)
        is WaniKaniConnection.Unavailable -> stringResource(
            R.string.wk_settings_unreachable,
            stringResource(wanikaniFailureLabel(current.failure))
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset belongs to the scroller below, which is the only thing that knows how
        // much content is on the page; taking it here as well would pad the page twice.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wk_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wk_settings_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState())
                // The page's own bottom inset, taken once by the content it scrolls with, plus the
                // 16 dp gap that keeps the last action out of the gesture band.
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(top = Spacing.xl, bottom = Spacing.l)
        ) {
            WaniKaniCard {
                Text(
                    stringResource(R.string.wk_settings_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.m))
                // The hub's row pattern, one line into the page: what the account amounts to on the
                // left, the token's state on the right, in the same one-word vocabulary the hub's
                // rows use for their own state.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (connected) {
                                R.string.wk_settings_status_saved
                            } else {
                                R.string.wk_settings_status_none
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        stringResource(
                            if (connected) {
                                R.string.settings_value_on
                            } else {
                                R.string.settings_value_none
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(Spacing.l))
            WaniKaniCard {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.wk_settings_token_label)) },
                    placeholder = { Text(stringResource(R.string.wk_settings_token_placeholder)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(R.string.wk_settings_token_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Where the token is made, one tap away rather than a page name to go looking for.
                // The overlay cannot open this screen, so this is the reader's only way to the token
                // page.
                TextButton(onClick = { openWanikaniPage(context, WANIKANI_TOKEN_URL) }) {
                    Text(stringResource(R.string.wk_settings_token_page))
                }
            }

            Spacer(Modifier.height(Spacing.l))
            Button(
                enabled = !busy && token.isNotBlank(),
                onClick = {
                    busy = true
                    cleared = null
                    scope.launch {
                        val result = WaniKaniRepository.connect(context.applicationContext, token)
                        if (result is WaniKaniConnection.Connected) token = ""
                        // Read back rather than assumed: a storage failure clears what was there, so
                        // an answer that is not Connected can leave the device without a token.
                        connected = WaniKaniRepository.isConfigured(context)
                        answer = result
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.wk_settings_checking else R.string.wk_settings_connect
                    )
                )
            }

            // Also shown after a disconnect that could not clear everything: the token may be gone
            // while part of the cache is not, and then Disconnect is the way to finish the job.
            if (connected || cleared == false) {
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.fillMaxWidth()) {
                    // Rechecking re-reads the subscription: a paid entitlement that has lapsed is
                    // what turns a paid account into the free levels, and this is where it shows.
                    OutlinedButton(
                        enabled = !busy && connected,
                        onClick = {
                            busy = true
                            cleared = null
                            scope.launch {
                                answer = WaniKaniRepository.reconnect(context.applicationContext)
                                // As after connect: a failed recheck may have left nothing stored.
                                connected = WaniKaniRepository.isConfigured(context)
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.wk_settings_recheck)) }
                    Spacer(Modifier.width(Spacing.s))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            answer = null
                            scope.launch {
                                // False when the token, or part of the cache, could not be cleared:
                                // the screen says so, and what is left is read back rather than
                                // assumed gone.
                                cleared = WaniKaniRepository.disconnect(context.applicationContext)
                                connected = WaniKaniRepository.isConfigured(context)
                                token = ""
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.wk_settings_disconnect)) }
                }
            }

            message?.let {
                Spacer(Modifier.height(Spacing.l))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(Spacing.l))
            // What the pages cover and where they come from: reference rather than part of the form
            // above it, so it reads as one card of its own rather than as the page running out.
            WaniKaniCard {
                Text(
                    stringResource(R.string.wk_settings_coverage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.wk_settings_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One section of the page, on its own card: the reader's eye lands on the field or the paragraph it
 * is about rather than on a flat column of copy, and the page background stays the ground between
 * them. The status is the first of these — it is the state of the account, not part of the token the
 * reader types into — and the token, its help and the way to WaniKani's own page share the second,
 * because they are all about that one field.
 */
@Composable
private fun WaniKaniCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(Spacing.l), content = content)
    }
}
