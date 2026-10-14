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
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.gaten.app.ocr.MangaOcrCatalog
import com.gaten.app.ocr.MangaOcrDownloader
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ocr.OcrPreferences
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.components.AccessibilityDisclosureDialog
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html"
private const val CIRCLETOSEARCH_URL = "https://github.com/AKS-Labs/CircleToSearch"
private const val MEIKI_MODEL_URL = "https://huggingface.co/rtr46/meiki.text.detect.v0"

/**
 * Home screen: service status, lookup triggers, the optional manga-ocr model, Anki settings, and
 * the legal footer. The app is used from the overlay, so this stays a plain setup page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    var showAnkiSettings by rememberSaveable { mutableStateOf(false) }
    if (showAnkiSettings) {
        AnkiSettingsScreen(onClose = { showAnkiSettings = false })
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Service state, re-read on every resume because the user grants it in Settings.
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var showDisclosure by remember { mutableStateOf(false) }
    var floatingEnabled by remember {
        mutableStateOf(TriggerPreferences.floatingButtonEnabled(context))
    }
    var mangaInstalled by remember { mutableStateOf(MangaOcrModel.isInstalled(context)) }
    var mangaSizeBytes by remember { mutableStateOf(MangaOcrModel.installedSizeBytes(context)) }
    var mangaCatalogId by remember { mutableStateOf(MangaOcrModel.installedCatalogId(context)) }
    var showCharBoxes by remember { mutableStateOf(OcrPreferences.showCharBoxes(context)) }
    // The entry the confirm dialog is asking about, the one downloading, and its progress.
    var confirmDownload by remember { mutableStateOf<MangaOcrCatalog.Entry?>(null) }
    var downloading by remember { mutableStateOf<MangaOcrCatalog.Entry?>(null) }
    var downloadProgress by remember { mutableStateOf<MangaOcrDownloader.Progress?>(null) }
    val downloader = remember { MangaOcrDownloader(MangaOcrModel.dir(context)) }

    fun refreshMangaModel() {
        mangaInstalled = MangaOcrModel.isInstalled(context)
        mangaSizeBytes = MangaOcrModel.installedSizeBytes(context)
        mangaCatalogId = MangaOcrModel.installedCatalogId(context)
    }

    /** Runs the download the user confirmed, keeping the progress bar and the outcome message in sync. */
    fun startDownload(entry: MangaOcrCatalog.Entry) {
        confirmDownload = null
        downloading = entry
        downloadProgress = null
        scope.launch {
            // The progress callback fires on Dispatchers.IO; Compose snapshot writes are thread-safe.
            val outcome = downloader.download(entry) { downloadProgress = it }
            downloading = null
            downloadProgress = null
            refreshMangaModel()
            val message = when (outcome) {
                MangaOcrDownloader.Outcome.Installed ->
                    context.getString(R.string.home_manga_download_ok, entry.label)

                MangaOcrDownloader.Outcome.Cancelled ->
                    context.getString(R.string.home_manga_download_cancelled)

                is MangaOcrDownloader.Outcome.Failed ->
                    context.getString(R.string.home_manga_download_failed, outcome.reason)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // The other way onto the device: the three files, from wherever the user got them.
    val pickModelFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val installed = MangaOcrModel.importFrom(context, uris)
            refreshMangaModel()
            Toast.makeText(
                context,
                context.getString(
                    if (installed) R.string.home_manga_import_ok else R.string.home_manga_import_failed
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isAccessibilityServiceEnabled(context)
                notificationAccess = hasNotificationAccess(context)
                floatingEnabled = TriggerPreferences.floatingButtonEnabled(context)
                refreshMangaModel()
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

            SectionLabel(text = stringResource(R.string.home_section_manga))
            MangaOcrSection(
                installed = mangaInstalled,
                installedId = mangaCatalogId,
                sizeBytes = mangaSizeBytes,
                downloading = downloading,
                progress = downloadProgress,
                onDownload = { entry -> confirmDownload = entry },
                onCancelDownload = { downloader.cancel() },
                onImport = { pickModelFiles.launch(arrayOf("*/*")) },
                onRemove = {
                    MangaOcrModel.remove(context)
                    refreshMangaModel()
                }
            )

            Spacer(modifier = Modifier.height(Spacing.m))
            DebugBoxesRow(
                enabled = showCharBoxes,
                onToggle = { checked ->
                    showCharBoxes = checked
                    OcrPreferences.setShowCharBoxes(context, checked)
                }
            )

            Spacer(modifier = Modifier.height(Spacing.xxl))
            SectionLabel(text = stringResource(R.string.anki_settings_title))
            Text(
                text = stringResource(R.string.anki_settings_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.s))
            OutlinedButton(onClick = { showAnkiSettings = true }) {
                Text(stringResource(R.string.anki_settings_open))
            }

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

    // The size is shown before anything is fetched, and on mobile data the wording says so: the
    // download must never start as a surprise on a metered connection.
    confirmDownload?.let { entry ->
        val metered = remember(entry) { isMetered(context) }
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(stringResource(R.string.home_manga_confirm_title, entry.label)) },
            text = {
                Text(
                    stringResource(
                        if (metered) {
                            R.string.home_manga_confirm_metered
                        } else {
                            R.string.home_manga_confirm_body
                        },
                        entry.approxSizeMb
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { startDownload(entry) }) {
                    Text(stringResource(R.string.home_manga_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
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

/**
 * The optional manga-ocr model: what is installed, the models the catalog offers, and the ways onto
 * the device.
 *
 * There is no switch: manga-ocr is the reader as soon as its model is on the phone. With no model
 * installed the built-in reader (ML Kit) reads the screen, and the card says so.
 *
 * Downloading is the app's only network request, and it only happens after the size has been shown
 * and confirmed ([confirmDownload] in the screen above); importing opens a document picker for a
 * phone that has no network or a model fetched elsewhere. Both end up in the same place, and Remove
 * takes the model off again.
 */
@Composable
private fun MangaOcrSection(
    installed: Boolean,
    installedId: String?,
    sizeBytes: Long,
    downloading: MangaOcrCatalog.Entry?,
    progress: MangaOcrDownloader.Progress?,
    onDownload: (MangaOcrCatalog.Entry) -> Unit,
    onCancelDownload: () -> Unit,
    onImport: () -> Unit,
    onRemove: () -> Unit
) {
    val installedLabel = MangaOcrCatalog.byId(installedId)?.label
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
    ) {
        Text(
            text = mangaStatusText(installed, installedLabel, sizeBytes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!installed) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.home_manga_builtin_reader),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s))
        Text(
            text = stringResource(R.string.home_manga_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        for (entry in MangaOcrCatalog.entries) {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.home_manga_entry_size, entry.approxSizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.m))
                if (installed && installedId == entry.id) {
                    Text(
                        text = stringResource(R.string.home_manga_installed_chip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Button(
                        onClick = { onDownload(entry) },
                        enabled = downloading == null
                    ) {
                        Text(stringResource(R.string.home_manga_download))
                    }
                }
            }
        }

        downloading?.let { entry ->
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_manga_downloading, entry.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Spacing.s))
                    val fraction = progress?.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = mangaProgressText(progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.m))
                TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.home_manga_cancel))
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onImport, enabled = downloading == null) {
                Text(stringResource(R.string.home_manga_import))
            }
            if (installed) {
                Spacer(modifier = Modifier.width(Spacing.m))
                OutlinedButton(onClick = onRemove, enabled = downloading == null) {
                    Text(stringResource(R.string.home_manga_remove))
                }
            }
        }
    }
}

/**
 * Debug aid: draws the recognised boxes - every line's bounds and every character box - over the
 * frozen frame, so a box that is off its text is visible. The accessibility service reads the
 * preference for each overlay it opens, so a toggle here applies to the next lookup.
 */
@Composable
private fun DebugBoxesRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.home_debug_boxes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** "Installed: manga-ocr (int8) (117 MB)", "Installed (117 MB)", or "Not installed". */
@Composable
private fun mangaStatusText(installed: Boolean, installedLabel: String?, sizeBytes: Long): String {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, sizeBytes)
    return when {
        !installed -> stringResource(R.string.home_manga_status_missing)
        installedLabel != null -> stringResource(R.string.home_manga_status_installed, installedLabel, size)
        else -> stringResource(R.string.home_manga_status_installed_unknown, size)
    }
}

/** "42% · decoder.onnx", or just the file name when the entry does not declare its sizes. */
@Composable
private fun mangaProgressText(progress: MangaOcrDownloader.Progress?): String {
    val percent = progress?.fraction?.let { (it * 100).roundToInt() }
    return when {
        progress == null -> stringResource(R.string.home_manga_progress_starting)
        percent == null -> stringResource(R.string.home_manga_progress_unknown, progress.fileName)
        else -> stringResource(R.string.home_manga_progress, percent, progress.fileName)
    }
}

/** True when the active network is metered, i.e. a download would run on mobile data. */
private fun isMetered(context: Context): Boolean = try {
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
} catch (_: Exception) {
    false
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

/** Small muted legal footer; the attribution lines open their pages in a browser. */
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
    Spacer(modifier = Modifier.height(Spacing.s))
    Text(
        text = stringResource(R.string.home_about_meiki),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { openUrl(context, MEIKI_MODEL_URL) }
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
