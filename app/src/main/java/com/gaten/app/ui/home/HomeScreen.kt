/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as TileIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.LookupTileService
import com.gaten.app.LookupTriggerReceiver
import com.gaten.app.R
import com.gaten.app.GatenAccessibilityService
import com.gaten.app.media.hasNotificationAccess
import com.gaten.app.ocr.OcrEngine
import com.gaten.app.ocr.OcrPreferences
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.components.AccessibilityDisclosureDialog
import com.gaten.app.ui.theme.Spacing

private const val EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html"
private const val CIRCLETOSEARCH_URL = "https://github.com/AKS-Labs/CircleToSearch"

/**
 * Home screen: service status, the two ways to start a lookup, the OCR engine setting and the
 * legal footer. The app is used from the overlay, so this stays a plain setup page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current

    // Service state, re-read on every resume because the user grants it in Settings.
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var showDisclosure by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(OcrPreferences.engine(context)) }
    var floatingEnabled by remember {
        mutableStateOf(TriggerPreferences.floatingButtonEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isAccessibilityServiceEnabled(context)
                notificationAccess = hasNotificationAccess(context)
                floatingEnabled = TriggerPreferences.floatingButtonEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            StatusRow(
                enabled = serviceEnabled,
                onTurnOn = { showDisclosure = true }
            )

            if (!notificationAccess) {
                Spacer(modifier = Modifier.height(Spacing.m))
                MediaAccessRow(
                    onGrant = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
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

            Spacer(modifier = Modifier.height(Spacing.xl))

            SectionLabel(text = stringResource(R.string.home_section_start))
            FloatingButtonRow(
                enabled = floatingEnabled,
                onToggle = { checked ->
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
            Spacer(modifier = Modifier.height(Spacing.m))
            TileRow()
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline
            )
            KeyMapperRow(
                onCopy = {
                    clipboard.setText(AnnotatedString(LookupTriggerReceiver.ACTION_TRIGGER_LOOKUP))
                }
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            SectionLabel(text = stringResource(R.string.home_section_ocr))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = engine == OcrEngine.MLKIT,
                    onClick = {
                        engine = OcrEngine.MLKIT
                        OcrPreferences.setEngine(context, OcrEngine.MLKIT)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.home_engine_mlkit))
                }
                SegmentedButton(
                    selected = engine == OcrEngine.TESSERACT,
                    onClick = {
                        engine = OcrEngine.TESSERACT
                        OcrPreferences.setEngine(context, OcrEngine.TESSERACT)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.home_engine_tesseract))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.s))
            Text(
                text = stringResource(
                    if (engine == OcrEngine.MLKIT) {
                        R.string.home_engine_hint_mlkit
                    } else {
                        R.string.home_engine_hint_tesseract
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.xxl))
            AboutFooter()
            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showDisclosure = false
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_accessibility_settings_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = { showDisclosure = false }
        )
    }
}

/** Full-width status banner: a coloured dot plus either a "Turn on" button or nothing. */
@Composable
private fun StatusRow(enabled: Boolean, onTurnOn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
        )
        Spacer(modifier = Modifier.width(Spacing.m))
        Text(
            text = stringResource(
                if (enabled) R.string.home_status_on else R.string.home_status_off
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!enabled) {
            Button(onClick = onTurnOn) {
                Text(stringResource(R.string.home_action_turn_on))
            }
        }
    }
}

/**
 * Shown only while notification access is missing: the system answers media-session reads only
 * for a granted listener, so without this the overlay cannot stop the video playing behind it.
 */
@Composable
private fun MediaAccessRow(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.home_media_access_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(Spacing.m))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.home_action_turn_on))
        }
    }
}

/**
 * Start-a-lookup row: the on-screen floating bubble. The switch only records the choice; the
 * accessibility service owns the window and reacts to [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED].
 */
@Composable
private fun FloatingButtonRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_start_floating_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.home_start_floating_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Muted uppercase-style section header followed by a small gap. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(Spacing.s))
}

/**
 * First lookup row: offers adding the Quick Settings tile. On API 33+ it asks the system to show
 * the tile-add dialog; below 33 there is no such API, so the row is inert.
 */
@Composable
private fun TileRow() {
    val context = LocalContext.current
    val clickModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Modifier.clickable {
            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(context, LookupTileService::class.java),
                context.getString(R.string.tile_label),
                TileIcon.createWithResource(context, R.drawable.ic_qs_lookup),
                context.mainExecutor
            ) { _ -> }
        }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(vertical = Spacing.l),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_start_tile_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.home_start_tile_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Second lookup row: the broadcast action a key remapper must send, with a copy button. */
@Composable
private fun KeyMapperRow(onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.l),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_start_key_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.home_start_key_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.s))
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
                        contentDescription = stringResource(R.string.home_cd_copy_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** Small muted legal footer; the two attribution lines open their pages in a browser. */
@Composable
private fun AboutFooter() {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    SectionLabel(text = stringResource(R.string.home_about))
    Text(
        text = stringResource(R.string.home_about_version, versionName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(Spacing.s))
    Text(
        text = stringResource(R.string.home_about_dict),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { openUrl(context, EDRDG_LICENCE_URL) }
    )
    Spacer(modifier = Modifier.height(Spacing.s))
    Text(
        text = stringResource(R.string.home_about_license),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { openUrl(context, CIRCLETOSEARCH_URL) }
    )
}

/** Opens a URL in the browser; a no-op when no browser can handle it (the app is offline). */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser available; nothing sensible to do.
    }
}

/** True when [GatenAccessibilityService] appears in the enabled-services secure setting. */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, GatenAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)

    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName)
            return true
    }
    return false
}
