/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.R
import com.gaten.app.anki.AnkiCollection
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiField
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiSettings
import com.gaten.app.anki.AnkiSettingsError
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.ui.theme.Spacing
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.launch

// Preserve unsaved template text and the ordered field mapping across activity recreation.
private val AnkiSettingsSaver = listSaver<AnkiSettings, String>(
    save = {
        listOf(it.deckName, it.tags, it.modelId?.toString().orEmpty(), it.front, it.back, it.css,
            it.modelFields.size.toString()) + it.modelFields + it.fieldSources.map(AnkiField::name)
    },
    restore = {
        val count = it[6].toInt()
        AnkiSettings(
            deckName = it[0], tags = it[1], modelId = it[2].toLongOrNull(),
            front = it[3], back = it[4], css = it[5],
            modelFields = it.subList(7, 7 + count),
            fieldSources = it.drop(7 + count).map(AnkiField::valueOf)
        )
    }
)

/**
 * Edits a local draft and saves it only on Save. Collection/field reads happen off the main thread;
 * selecting an existing note type never changes its templates or any notes in AnkiDroid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(stateSaver = AnkiSettingsSaver) {
        mutableStateOf(AnkiPreferences.load(context))
    }
    var attemptedSave by rememberSaveable { mutableStateOf(false) }
    var showTemplates by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf(AnkiExporter.status(context)) }
    var collection by remember { mutableStateOf<AnkiCollection?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingFields by remember { mutableStateOf(false) }
    var readFailed by remember { mutableStateOf(false) }
    // What is on disk, to tell an edited draft from an untouched one. The draft itself is
    // saveable, so this comparison survives a rotation the same way the edits do.
    val saved = remember { AnkiPreferences.load(context) }
    val dirty = draft != saved
    var confirmDiscard by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = AnkiExporter.status(context)
        refresh++
        if (!it) Toast.makeText(context, R.string.anki_error_permission, Toast.LENGTH_LONG).show()
    }

    /** Leaves, asking first only when the draft differs from what is saved. */
    fun leave() {
        if (dirty) confirmDiscard = true else onClose()
    }

    BackHandler(onBack = { leave() })

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = AnkiExporter.status(context)
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status, refresh) {
        readFailed = false
        loading = false
        if (status == AnkiStatus.Ready) {
            loading = true
            AnkiExporter.collection(context).fold(
                onSuccess = { collection = it },
                onFailure = { readFailed = true }
            )
            loading = false
        } else {
            collection = null
        }
    }

    val error = if (attemptedSave) draft.validationError() else null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.anki_settings_open)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.anki_settings_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = !loadingFields,
                        onClick = {
                            attemptedSave = true
                            val invalid = draft.validationError()
                            if (invalid == null) {
                                AnkiPreferences.save(context, draft)
                                Toast.makeText(context, R.string.anki_settings_saved, Toast.LENGTH_SHORT).show()
                                onClose()
                            } else {
                                Toast.makeText(context, settingsErrorLabel(invalid), Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Text(stringResource(R.string.anki_settings_save)) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(Spacing.xl)
        ) {
            when (status) {
                AnkiStatus.NotInstalled -> Text(stringResource(R.string.anki_settings_not_installed))
                AnkiStatus.NeedsPermission -> {
                    Text(stringResource(R.string.anki_settings_permission))
                    Spacer(Modifier.height(Spacing.s))
                    Button(onClick = { permission.launch(AddContentApi.READ_WRITE_PERMISSION) }) {
                        Text(stringResource(R.string.anki_settings_connect))
                    }
                }
                AnkiStatus.Ready -> {
                    OutlinedButton(enabled = !loading && !loadingFields, onClick = { refresh++ }) {
                        Text(stringResource(R.string.anki_settings_refresh))
                    }
                }
            }
            if (loading || loadingFields) Text(stringResource(R.string.anki_settings_loading))
            if (readFailed) {
                Text(stringResource(R.string.anki_settings_load_failed), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Spacing.l))
            OutlinedTextField(
                value = draft.deckName, onValueChange = { draft = draft.copy(deckName = it) },
                label = { Text(stringResource(R.string.anki_settings_deck)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.anki_settings_deck_help), style = MaterialTheme.typography.bodySmall)
            collection?.decks?.takeIf { it.isNotEmpty() }?.let { decks ->
                Spacer(Modifier.height(Spacing.s))
                AnkiChoice(
                    label = stringResource(R.string.anki_settings_choose_deck),
                    value = draft.deckName, options = decks.map { it to it }
                ) { draft = draft.copy(deckName = it) }
            }
            Spacer(Modifier.height(Spacing.l))
            OutlinedTextField(
                value = draft.tags, onValueChange = { draft = draft.copy(tags = it) },
                label = { Text(stringResource(R.string.anki_settings_tags)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.anki_settings_tags_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(Spacing.l))
            val models = collection?.models.orEmpty()
            val managedLabel = stringResource(R.string.anki_settings_managed)
            AnkiChoice(
                label = stringResource(R.string.anki_settings_note_type),
                value = if (draft.modelId == null) managedLabel else
                    draft.modelId?.let(models::get) ?: stringResource(R.string.anki_settings_unavailable_model),
                options = listOf(null to managedLabel) + models.map { (id, name) -> id to name },
                enabled = !loadingFields
            ) { modelId ->
                if (loadingFields) return@AnkiChoice
                if (modelId == null) {
                    draft = draft.copy(modelId = null, modelFields = emptyList(), fieldSources = emptyList())
                } else {
                    loadingFields = true
                    scope.launch {
                        AnkiExporter.modelFields(context, modelId).fold(
                            onSuccess = { fields ->
                                val previous = if (draft.modelId == modelId)
                                    draft.modelFields.zip(draft.fieldSources).toMap() else emptyMap()
                                draft = draft.copy(
                                    modelId = modelId, modelFields = fields,
                                    fieldSources = fields.map { name ->
                                        previous[name] ?: AnkiField.entries.firstOrNull {
                                            it != AnkiField.EMPTY && it.name.equals(name, ignoreCase = true)
                                        } ?: AnkiField.EMPTY
                                    }
                                )
                            },
                            onFailure = {
                                Toast.makeText(context, R.string.anki_settings_load_failed, Toast.LENGTH_LONG).show()
                            }
                        )
                        loadingFields = false
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s))
            if (draft.modelId != null) {
                Text(stringResource(R.string.anki_settings_existing_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.anki_settings_mapping_help), style = MaterialTheme.typography.bodySmall)
                val sources = AnkiField.entries.map { it to stringResource(fieldLabel(it)) }
                draft.modelFields.forEachIndexed { index, name ->
                    Spacer(Modifier.height(Spacing.s))
                    val source = draft.fieldSources.getOrElse(index) { AnkiField.EMPTY }
                    AnkiChoice(
                        label = name, value = stringResource(fieldLabel(source)),
                        options = sources, enabled = !loadingFields
                    ) { selected ->
                        val mapping = draft.modelFields.indices.map {
                            if (it == index) selected else draft.fieldSources.getOrElse(it) { AnkiField.EMPTY }
                        }
                        draft = draft.copy(fieldSources = mapping)
                    }
                }
            } else {
                Text(stringResource(R.string.anki_settings_template_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showTemplates = !showTemplates }) {
                    Text(stringResource(R.string.anki_settings_templates))
                }
                if (showTemplates) {
                    AnkiTemplateEditor(stringResource(R.string.anki_settings_front), draft.front) {
                        draft = draft.copy(front = it)
                    }
                    AnkiTemplateEditor(stringResource(R.string.anki_settings_back_template), draft.back) {
                        draft = draft.copy(back = it)
                    }
                    AnkiTemplateEditor(stringResource(R.string.anki_settings_css), draft.css) {
                        draft = draft.copy(css = it)
                    }
                    TextButton(onClick = {
                        draft = draft.copy(
                            front = AnkiSettings.DEFAULT_FRONT, back = AnkiSettings.DEFAULT_BACK,
                            css = AnkiSettings.DEFAULT_CSS
                        )
                    }) { Text(stringResource(R.string.anki_settings_reset_templates)) }
                }
            }
            error?.let {
                Spacer(Modifier.height(Spacing.l))
                Text(
                    stringResource(settingsErrorLabel(it)),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    // The draft is lost on leaving, so an edited one asks first: Save is the only way it is kept,
    // and discarding is the reader's decision, never the back button's.
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.anki_settings_discard_title)) },
            text = { Text(stringResource(R.string.anki_settings_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.anki_settings_discard_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.anki_settings_discard_stay))
                }
            }
        )
    }
}

/** Picker shared by the deck, note type, and per-field source rows. */
@Composable
private fun <T> AnkiChoice(
    label: String, value: String, options: List<Pair<T, String>>,
    enabled: Boolean = true, onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun AnkiTemplateEditor(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        minLines = 3, maxLines = 8,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m)
    )
}

private fun fieldLabel(field: AnkiField): Int = when (field) {
    AnkiField.EXPRESSION -> R.string.anki_settings_expression
    AnkiField.READING -> R.string.anki_settings_reading
    AnkiField.MEANING -> R.string.anki_settings_meaning
    AnkiField.EMPTY -> R.string.anki_settings_empty
}

private fun settingsErrorLabel(error: AnkiSettingsError): Int = when (error) {
    AnkiSettingsError.DECK -> R.string.anki_settings_invalid_deck
    AnkiSettingsError.TAGS -> R.string.anki_settings_invalid_tags
    AnkiSettingsError.FRONT -> R.string.anki_settings_invalid_front
    AnkiSettingsError.BACK -> R.string.anki_settings_invalid_back
    AnkiSettingsError.CSS -> R.string.anki_settings_invalid_css
    AnkiSettingsError.MAPPING -> R.string.anki_settings_invalid_mapping
}
