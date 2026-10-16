/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon as TileIcon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.LookupTileService
import com.gaten.app.LookupTriggerReceiver
import com.gaten.app.R
import com.gaten.app.media.hasNotificationAccess
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.Spacing

/**
 * Look up: whether Gaten may read the screen, the three ways a lookup can start, and the optional
 * pause of whatever is playing behind the overlay.
 *
 * The permissions this page reports on are the platform's own — the enabled accessibility services
 * and the enabled notification listeners — and both are read again on every resume, because both are
 * granted in system Settings, outside the app. Nothing here is asked for by itself: the disclosure
 * comes first, and only then the system screen.
 *
 * Every row states what it currently amounts to on the right — the switch itself for the floating
 * button, the tile's presence once it is added, whether notification access has been granted — so the
 * page can be read down its right edge, and the sentences underneath stay one clause each.
 */
@Composable
fun LookupSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    var screenAccess by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var floatingEnabled by remember {
        mutableStateOf(TriggerPreferences.floatingButtonEnabled(context))
    }
    var tileAdded by remember { mutableStateOf(TriggerPreferences.tileAdded(context)) }
    val feedback = rememberFeedback()
    val requestScreenAccess = rememberScreenAccessRequest()

    // Tile callbacks can arrive while this page stays open under the Quick Settings shade.
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("gaten_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TriggerPreferences.KEY_TILE_ADDED) {
                tileAdded = TriggerPreferences.tileAdded(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                screenAccess = isAccessibilityServiceEnabled(context)
                notificationAccess = hasNotificationAccess(context)
                floatingEnabled = TriggerPreferences.floatingButtonEnabled(context)
                tileAdded = TriggerPreferences.tileAdded(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPageScaffold(title = R.string.settings_lookup_title, onClose = onClose) {
        Spacer(modifier = Modifier.height(Spacing.s))

        ScreenAccessCard(enabled = screenAccess, onTurnOn = requestScreenAccess)

        Spacer(modifier = Modifier.height(Spacing.xl))

        SettingsGroup(title = stringResource(R.string.settings_lookup_group_start)) {
            FloatingButtonRow(
                enabled = floatingEnabled,
                screenAccess = screenAccess,
                onToggle = { checked ->
                    // A choice that landed: the switch answers the finger with the app's tick, the
                    // same one every other toggle change gives.
                    feedback.haptic(FeedbackHaptic.Tick)
                    floatingEnabled = checked
                    TriggerPreferences.setFloatingButtonEnabled(context, checked)
                    // Explicit package: a manifest receiver only receives implicit broadcasts that
                    // name it (or its package) on API 26+.
                    context.sendBroadcast(
                        Intent(TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED)
                            .setPackage(context.packageName)
                    )
                }
            )
            SettingsRowDivider()
            TileRow(
                added = tileAdded,
                onAdded = {
                    tileAdded = true
                    TriggerPreferences.setTileAdded(context, true)
                }
            )
            SettingsRowDivider()
            KeyMapperRow(
                onCopy = {
                    clipboard.setText(AnnotatedString(LookupTriggerReceiver.ACTION_TRIGGER_LOOKUP))
                }
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        SettingsGroup(title = stringResource(R.string.settings_lookup_group_media)) {
            MediaPauseRow(
                granted = notificationAccess,
                onGrant = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_notification_settings_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }
}

/**
 * The on-screen bubble. The switch only records the choice; the accessibility service owns the
 * window and reacts to [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED], which is why the row
 * says what is missing while screen access is off.
 *
 * The switch is this row's value — on or off is the whole state — so it is given the row's own name
 * as its content description: it is the one label a switch sitting this far from its title can
 * carry for a screen reader.
 */
@Composable
private fun FloatingButtonRow(enabled: Boolean, screenAccess: Boolean, onToggle: (Boolean) -> Unit) {
    val switchLabel = stringResource(R.string.settings_lookup_floating_title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Outlined.TouchApp)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_lookup_floating_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    if (screenAccess) {
                        R.string.settings_lookup_floating_support
                    } else {
                        R.string.settings_lookup_floating_needs_access
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = switchLabel },
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/**
 * The Quick Settings tile: its presence comes from platform callbacks, and Android owns removal in
 * its own editor, so the row either offers the system's add request (API 33+) or says where the
 * editor is.
 *
 * Once it is there, "Added to Quick Settings" is the row's value on the right — the same place every
 * other row on these pages states its state — and what remains below is the one clause about taking
 * it out again.
 */
@Composable
private fun TileRow(added: Boolean, onAdded: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top
    ) {
        SettingsRowIcon(Icons.Outlined.Widgets)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_lookup_tile_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (added) {
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(
                        text = stringResource(R.string.settings_lookup_tile_added),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    if (added) {
                        R.string.settings_lookup_tile_remove_help
                    } else {
                        R.string.settings_lookup_tile_support
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!added && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.settings_lookup_tile_manual),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!added && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.height(Spacing.s))
                OutlinedButton(onClick = {
                    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, LookupTileService::class.java),
                        context.getString(R.string.tile_label),
                        TileIcon.createWithResource(context, R.drawable.ic_qs_lookup),
                        context.mainExecutor
                    ) { result ->
                        when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> onAdded()

                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit

                            else -> Toast.makeText(
                                context,
                                R.string.settings_lookup_tile_add_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.settings_lookup_tile_add))
                }
            }
        }
    }
}

/** Optional hardware-key instructions: collapsed until the reader needs the broadcast action. */
@Composable
private fun KeyMapperRow(onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top
    ) {
        SettingsRowIcon(Icons.Outlined.Key)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_lookup_key_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) {
                                R.string.settings_lookup_key_hide
                            } else {
                                R.string.settings_lookup_key_setup
                            }
                        )
                    )
                }
            }
            if (!expanded) return@Column
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.settings_lookup_key_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = LookupTriggerReceiver.ACTION_TRIGGER_LOOKUP,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.settings_lookup_cd_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * The optional media pause: notification access is what lets Gaten reach the media sessions, so the
 * row says whether it has it on the right, where every other row states its value, and is the only
 * place the grant is offered — nothing else in the app needs it. Without it Gaten still pauses, by
 * sending a media key instead.
 */
@Composable
private fun MediaPauseRow(granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top
    ) {
        SettingsRowIcon(Icons.Outlined.PauseCircle)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_lookup_media_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = stringResource(
                        if (granted) {
                            R.string.settings_lookup_media_granted
                        } else {
                            R.string.settings_lookup_media_denied
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    if (granted) {
                        R.string.settings_lookup_media_support_on
                    } else {
                        R.string.settings_lookup_media_support_off
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted) {
                Spacer(modifier = Modifier.height(Spacing.s))
                OutlinedButton(onClick = onGrant) {
                    Text(stringResource(R.string.settings_action_turn_on))
                }
            }
        }
    }
}
