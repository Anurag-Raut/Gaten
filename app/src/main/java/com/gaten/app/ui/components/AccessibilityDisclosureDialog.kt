/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.theme.Spacing

/**
 * Disclosure for the accessibility permission: what the service does, and the required checkbox
 * before "Turn on" is enabled. Confirming hands control back to the caller, which opens Settings.
 *
 * The reader decides on a lead sentence and a checkbox; the full text — the app's own, unchanged —
 * is one tap away behind "Read the details", where it scrolls inside the dialog instead of pushing
 * the buttons off the screen. The checkbox's whole row is the toggle, so the consent sentence and
 * the box are one target.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    var hasAgreed by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.title_accessibility_permission),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.body_accessibility_permission_lead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.l))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The row is the control; the box inside draws the state only, so a finger
                        // on either one agrees, and both read as one checkbox to a screen reader.
                        .toggleable(
                            value = hasAgreed,
                            role = Role.Checkbox,
                            onValueChange = { hasAgreed = it }
                        )
                ) {
                    Checkbox(checked = hasAgreed, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(
                        text = stringResource(R.string.label_accessibility_agree),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s))
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(
                        stringResource(
                            if (showDetails) {
                                R.string.action_hide_details
                            } else {
                                R.string.action_read_details
                            }
                        )
                    )
                }
                if (showDetails) {
                    Text(
                        text = stringResource(R.string.body_accessibility_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = hasAgreed
            ) {
                Text(stringResource(R.string.settings_action_turn_on))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
