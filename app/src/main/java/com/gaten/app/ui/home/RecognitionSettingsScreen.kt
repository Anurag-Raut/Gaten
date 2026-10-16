/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gaten.app.R
import com.gaten.app.ocr.MangaOcrCatalog
import com.gaten.app.ocr.MangaOcrDownloader
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The optional manga-ocr model: what is installed, the models the catalog offers, and the ways onto
 * the phone.
 *
 * There is no switch: manga-ocr is the reader as soon as its model is on the phone, and with none
 * installed the built-in reader (ML Kit) reads the screen — the Help dialog says so, because the
 * sentence is longer than a row. Downloading is one of the app's two network uses, and it only
 * happens after the size has been shown and confirmed; importing opens a document picker for a phone
 * with no network or a model fetched elsewhere. Both end in the same directory, and Remove takes the
 * model off again.
 *
 * All of that state lives in [RecognitionViewModel], which the activity owns rather than this page:
 * leaving the page, opening another one or rotating the phone neither cancels a running download nor
 * throws away what it has done.
 */
@Composable
fun RecognitionSettingsScreen(
    onClose: () -> Unit,
    viewModel: RecognitionViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The entry the confirm dialog is asking about. The download itself belongs to the view model.
    var confirmDownload by remember { mutableStateOf<MangaOcrCatalog.Entry?>(null) }
    val downloading = viewModel.downloading
    val busy = viewModel.busy

    val pickModelFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importFiles(uris) }

    // The model directory can change while this page is not looking — a debug build copies its
    // bundled model out on the first lookup, which the accessibility service runs with the app in
    // the background — so what is installed is re-read on every arrival and every resume. The view
    // model drops the ask while a download or an import owns the directory.
    LaunchedEffect(Unit) { viewModel.refreshInstalled() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The three long explanations this page used to carry in its flow, kept word for word in the
    // toolbar's Help dialog: a reader who wants them still gets every sentence, and the page itself
    // stays rows and one-clause notes.
    SettingsPageScaffold(
        title = R.string.settings_recognition_title,
        onClose = onClose,
        help = listOf(
            stringResource(R.string.settings_recognition_status_none_help),
            stringResource(R.string.settings_recognition_help),
            stringResource(R.string.settings_recognition_network_note)
        )
    ) {
        Spacer(modifier = Modifier.height(Spacing.s))

        RecognitionStatusCard(
            installed = viewModel.installed,
            installedId = viewModel.installedId,
            sizeBytes = viewModel.sizeBytes
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        SettingsGroup(title = stringResource(R.string.settings_recognition_group_models)) {
            MangaOcrCatalog.entries.forEachIndexed { index, entry ->
                if (index > 0) SettingsRowDivider()
                ModelRow(
                    entry = entry,
                    installed = viewModel.installed && viewModel.installedId == entry.id,
                    busy = busy,
                    onDownload = { confirmDownload = entry }
                )
            }
        }

        // The download in flight, in place: it is the same thing the Download button started, and
        // Cancel is the same downloader the view model holds.
        downloading?.let { entry ->
            Spacer(modifier = Modifier.height(Spacing.l))
            DownloadingCard(
                entry = entry,
                progress = viewModel.progress,
                onCancel = { viewModel.cancelDownload() }
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(
                onClick = { pickModelFiles.launch(arrayOf("*/*")) },
                enabled = !busy
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(R.string.settings_recognition_import))
            }
            if (viewModel.installed) {
                OutlinedButton(
                    onClick = { viewModel.removeModel() },
                    enabled = !busy
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.settings_recognition_remove))
                }
            }
        }
    }

    // The size is shown before anything is fetched, and on mobile data the wording says so: the
    // download must never start as a surprise on a metered connection.
    confirmDownload?.let { entry ->
        val metered = remember(entry) { isMetered(context) }
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(stringResource(R.string.settings_recognition_confirm_title, entry.label)) },
            text = {
                Text(
                    stringResource(
                        if (metered) {
                            R.string.settings_recognition_confirm_metered
                        } else {
                            R.string.settings_recognition_confirm_body
                        },
                        entry.approxSizeMb
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = null
                    viewModel.download(entry)
                }) {
                    Text(stringResource(R.string.settings_recognition_download))
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

/**
 * What is on the phone: the model's name and size, in the accent's container while it is installed
 * and on the neutral surface while it is not.
 *
 * One row, like every other row on these pages — the icon, what the card is, and what it currently
 * amounts to on the right. The value is a whole sentence ("manga-ocr (int8) installed (117 MB)")
 * rather than a short label, so it takes the rest of the line and wraps under itself rather than
 * pushing the title out of the way, and it wears the container's own on-colour. What the built-in
 * reader does with no model installed used to sit under this card; it is longer than a row, so it
 * lives in the page's Help dialog instead, unchanged.
 */
@Composable
private fun RecognitionStatusCard(installed: Boolean, installedId: String?, sizeBytes: Long) {
    val container = if (installed) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (installed) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val value = if (installed) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Memory,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(
                text = stringResource(R.string.settings_recognition_title),
                style = MaterialTheme.typography.titleSmall,
                color = content
            )
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(
                text = recognitionStatusText(installed, installedId, sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = value,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * One catalog entry: what it is, how big it is, and the one thing that can be done with it.
 *
 * The size is the row's value on the right of the name — the one fact that makes two entries
 * comparable — rather than a line of its own, which is what the rest of these pages do with a
 * row's current state.
 */
@Composable
private fun ModelRow(
    entry: MangaOcrCatalog.Entry,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top
    ) {
        SettingsRowIcon(Icons.Outlined.Download)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = stringResource(R.string.settings_recognition_size, entry.approxSizeMb),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.s))
            if (installed) {
                Text(
                    text = stringResource(R.string.settings_recognition_chip_installed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                OutlinedButton(onClick = onDownload, enabled = !busy) {
                    Text(stringResource(R.string.settings_recognition_download))
                }
            }
        }
    }
}

/** The download in flight: which entry, how far it has got, and the way to stop it. */
@Composable
private fun DownloadingCard(
    entry: MangaOcrCatalog.Entry,
    progress: MangaOcrDownloader.Progress?,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(
                text = stringResource(R.string.settings_recognition_downloading, entry.label),
                style = MaterialTheme.typography.titleSmall,
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
            Spacer(modifier = Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = downloadProgressText(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_recognition_cancel))
                }
            }
        }
    }
}

/** "manga-ocr (int8) installed (117 MB)", or just what is there when the entry is not known. */
@Composable
private fun recognitionStatusText(installed: Boolean, installedId: String?, sizeBytes: Long): String {
    val size = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    val label = MangaOcrCatalog.byId(installedId)?.label
    return when {
        !installed -> stringResource(R.string.settings_recognition_status_none)
        label != null -> stringResource(R.string.settings_recognition_status_installed, label, size)
        else -> stringResource(R.string.settings_row_recognition_unknown, size)
    }
}

/** "42% · decoder.onnx", or just the file name when the entry declares no sizes. */
@Composable
private fun downloadProgressText(progress: MangaOcrDownloader.Progress?): String {
    val percent = progress?.fraction?.let { (it * 100).roundToInt() }
    return when {
        progress == null -> stringResource(R.string.settings_recognition_progress_starting)
        percent == null -> progress.fileName
        else -> stringResource(R.string.settings_recognition_progress, percent, progress.fileName)
    }
}

/** True when the active network is metered, i.e. a download would run on mobile data. */
private fun isMetered(context: Context): Boolean = try {
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
} catch (_: Exception) {
    false
}

/**
 * The optional model, and the state of getting one onto the phone.
 *
 * The download lives here rather than in the page because the page is disposed the moment the reader
 * leaves it or rotates the phone, and [viewModelScope] — the activity's — is what carries a running
 * download across both. It is deliberately not a service: nothing continues once the app is gone,
 * and [cancelDownload] is what stops one early.
 *
 * Progress arrives on [kotlinx.coroutines.Dispatchers.IO], where the downloader streams the body —
 * Compose snapshot writes are thread-safe — and everything after a download returns (the toast and
 * the re-read of what is installed) is back on the main dispatcher.
 */
class RecognitionViewModel(private val app: Application) : AndroidViewModel(app) {
    private val downloader = MangaOcrDownloader(MangaOcrModel.dir(app))

    /** What is on the phone now. */
    var installed by mutableStateOf(MangaOcrModel.isInstalled(app))
        private set
    var installedId by mutableStateOf(MangaOcrModel.installedCatalogId(app))
        private set
    var sizeBytes by mutableStateOf(MangaOcrModel.installedSizeBytes(app))
        private set

    /** The entry being fetched, while one is, and how far it has got. */
    var downloading by mutableStateOf<MangaOcrCatalog.Entry?>(null)
        private set
    var progress by mutableStateOf<MangaOcrDownloader.Progress?>(null)
        private set

    /**
     * True while the model directory is being written to or cleared — a download, an import or a
     * removal. One at a time is the rule: a second one would write over what the first is staging.
     */
    var busy by mutableStateOf(false)
        private set

    /** Fetches [entry], whose size the reader has already confirmed. One mutation at a time. */
    fun download(entry: MangaOcrCatalog.Entry) {
        if (busy) return
        downloading = entry
        progress = null
        mutate {
            val outcome = downloader.download(entry) { progress = it }
            downloading = null
            progress = null
            // Read back rather than assumed: a cancelled or failed download leaves whatever was
            // installed before it exactly as it was.
            readInstalled()
            val message = when (outcome) {
                MangaOcrDownloader.Outcome.Installed -> app.getString(
                    R.string.settings_recognition_toast_installed,
                    entry.label
                )

                MangaOcrDownloader.Outcome.Cancelled ->
                    app.getString(R.string.settings_recognition_toast_cancelled)

                is MangaOcrDownloader.Outcome.Failed -> app.getString(
                    R.string.settings_recognition_toast_failed,
                    outcome.reason
                )
            }
            toast(message)
        }
    }

    /** Asks the download in flight to stop; nothing is installed and the staged files are deleted. */
    fun cancelDownload() {
        downloader.cancel()
    }

    /**
     * Copies the picked files in, each recognised by the role its name stands for. A picker that
     * came back while a download was running, or a second tap, is dropped rather than queued: the
     * model directory is written by one thing at a time.
     */
    fun importFiles(uris: List<Uri>) {
        if (busy || uris.isEmpty()) return
        mutate {
            val done = MangaOcrModel.importFrom(app, uris)
            readInstalled()
            toast(
                app.getString(
                    if (done) {
                        R.string.settings_recognition_toast_imported
                    } else {
                        R.string.settings_recognition_toast_import_failed
                    }
                )
            )
        }
    }

    /** Takes the model off the phone; nothing else about the app changes. */
    fun removeModel() {
        if (busy) return
        mutate {
            // The deletion is a recursive one on the filesystem, so it stays off the main thread —
            // and what is left is read back rather than assumed, because a directory that cannot be
            // deleted leaves the model exactly where it was.
            withContext(Dispatchers.IO) { MangaOcrModel.remove(app) }
            readInstalled()
            toast(
                app.getString(
                    if (installed) {
                        R.string.settings_recognition_toast_remove_failed
                    } else {
                        R.string.settings_recognition_toast_removed
                    }
                )
            )
        }
    }

    /**
     * Runs [block] as the one mutation in flight, with [busy] held for as long as it takes. The work
     * is on the view model's scope, so leaving the page or rotating the phone does not interrupt it.
     */
    private fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    /**
     * Re-reads what is on the phone, unless a mutation is under way: a download, an import or a
     * removal owns the model directory until it finishes, and it re-reads the directory itself when
     * it does. An active download's progress is not touched either way.
     *
     * The page calls this on every arrival and every resume, because the directory can change while
     * it is not looking — a debug build copies its bundled model out on the first lookup, which the
     * accessibility service runs with the app in the background.
     */
    fun refreshInstalled() {
        if (busy) return
        readInstalled()
    }

    /** Reads the model directory into the state above; the end of every mutation is one of these. */
    private fun readInstalled() {
        installed = MangaOcrModel.isInstalled(app)
        installedId = MangaOcrModel.installedCatalogId(app)
        sizeBytes = MangaOcrModel.installedSizeBytes(app)
    }

    private fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
