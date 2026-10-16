/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.GatenAccessibilityService
import com.gaten.app.R
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.ocr.MangaOcrCatalog
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ui.components.AccessibilityDisclosureDialog
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.theme.AccentColor
import com.gaten.app.ui.theme.Palette
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.theme.ThemeMode
import com.gaten.app.ui.wanikani.WaniKaniSettingsScreen
import com.gaten.app.wanikani.WaniKaniRepository

/** Nothing in the app is wider than this, however wide the screen is. */
private val MaxContentWidth = 840.dp

/** Settings hub; the Activity navigation host owns its detail destinations. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    accent: AccentColor,
    onOpenPage: (String) -> Unit
) {
    SettingsHub(rememberScrollState(), themeMode, accent, onOpenPage)
}

/** Renders a settings destination without owning navigation or destination motion. */
@Composable
fun SettingsDetailScreen(
    page: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    accent: AccentColor,
    onAccentChange: (AccentColor) -> Unit,
    onClose: () -> Unit,
    recognitionViewModel: RecognitionViewModel,
    isCurrentDestination: Boolean = true
) {
    when (page) {
        SettingsRoutes.LOOKUP -> LookupSettingsScreen(onClose)
        SettingsRoutes.RECOGNITION -> RecognitionSettingsScreen(onClose, recognitionViewModel)
        SettingsRoutes.ANKI -> AnkiSettingsScreen(onClose, isCurrentDestination)
        SettingsRoutes.WANIKANI -> WaniKaniSettingsScreen(onClose)
        SettingsRoutes.APPEARANCE -> AppearanceSettingsScreen(
            themeMode, onThemeModeChange, palette, onPaletteChange,
            accent, onAccentChange, onClose
        )
        else -> AboutSettingsScreen(onClose)
    }
}

/**
 * The hub: what screen access is, then a row per page under the group it belongs to.
 *
 * Everything on it is a summary read from wherever the truth lives — the platform's enabled-services
 * setting, the model directory, AnkiDroid, the stored token — and read again on every arrival and
 * every resume, because all of it can change while the reader is elsewhere. Nothing here asks for
 * anything: a missing permission or model is stated, and the page that owns it is where it is asked
 * for.
 */
@Composable
private fun SettingsHub(
    scrollState: ScrollState,
    themeMode: ThemeMode,
    accent: AccentColor,
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenAccess by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var modelInstalled by remember { mutableStateOf(MangaOcrModel.isInstalled(context)) }
    var modelId by remember { mutableStateOf(MangaOcrModel.installedCatalogId(context)) }
    var ankiStatus by remember { mutableStateOf(AnkiExporter.status(context)) }
    var ankiDeck by remember { mutableStateOf(AnkiPreferences.load(context).deckName) }
    var wanikaniConnected by remember { mutableStateOf(WaniKaniRepository.isConfigured(context)) }
    val versionName = remember(context) { appVersionName(context) }
    val requestScreenAccess = rememberScreenAccessRequest()

    // Screen access is granted outside Gaten and a model or a token changes inside a page, so every
    // resume re-reads what the rows say.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                screenAccess = isAccessibilityServiceEnabled(context)
                modelInstalled = MangaOcrModel.isInstalled(context)
                modelId = MangaOcrModel.installedCatalogId(context)
                ankiStatus = AnkiExporter.status(context)
                ankiDeck = AnkiPreferences.load(context).deckName
                wanikaniConnected = WaniKaniRepository.isConfigured(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The bar below owns the bottom inset while it is on screen, so only the top and the
            // sides are taken here, and nothing above the bar is padded twice.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .verticalScroll(scrollState),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
        ) {
            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            ScreenAccessCard(enabled = screenAccess, onTurnOn = requestScreenAccess)

            Spacer(modifier = Modifier.height(Spacing.xl))

            SettingsGroup(title = stringResource(R.string.settings_group_lookup)) {
                SettingsRow(
                    icon = Icons.Outlined.Search,
                    title = R.string.settings_row_lookup_title,
                    summary = stringResource(R.string.settings_row_lookup_support),
                    value = stringResource(
                        if (screenAccess) R.string.settings_value_on else R.string.settings_value_off
                    )
                ) { onOpen(SettingsRoutes.LOOKUP) }
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.TextSnippet,
                    title = R.string.settings_row_recognition_title,
                    summary = stringResource(R.string.settings_row_recognition_support),
                    value = recognitionValue(modelInstalled, modelId)
                ) { onOpen(SettingsRoutes.RECOGNITION) }
            }

            Spacer(modifier = Modifier.height(Spacing.l))

            SettingsGroup(title = stringResource(R.string.settings_group_study)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = R.string.settings_row_anki_title,
                    summary = ankiSupport(ankiStatus),
                    value = ankiDeck.takeIf { ankiStatus == AnkiStatus.Ready }
                ) { onOpen(SettingsRoutes.ANKI) }
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Outlined.Translate,
                    title = R.string.settings_row_wanikani_title,
                    summary = stringResource(R.string.settings_row_wanikani_support),
                    value = stringResource(
                        if (wanikaniConnected) {
                            R.string.settings_row_wanikani_on
                        } else {
                            R.string.settings_row_wanikani_off
                        }
                    )
                ) { onOpen(SettingsRoutes.WANIKANI) }
            }

            Spacer(modifier = Modifier.height(Spacing.l))

            SettingsGroup(title = stringResource(R.string.settings_group_app)) {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = R.string.settings_row_appearance_title,
                    summary = stringResource(R.string.settings_row_appearance_support),
                    value = stringResource(
                        R.string.settings_row_appearance_value,
                        stringResource(themeMode.labelRes()),
                        stringResource(accent.labelRes())
                    )
                ) { onOpen(SettingsRoutes.APPEARANCE) }
                SettingsRowDivider()
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = R.string.settings_row_about_title,
                    summary = stringResource(R.string.settings_row_about_support),
                    value = versionName
                ) { onOpen(SettingsRoutes.ABOUT) }
            }

            Spacer(modifier = Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * One row of a group: the icon in the accent, what the page is, and what it currently amounts to.
 *
 * The value sits on the right in one line, so the states down a group can be read in a single
 * sweep, and the chevron after it is drawn only when the row goes somewhere: a row that only
 * states something is not made to look tappable.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    @StringRes title: Int,
    summary: String,
    value: String? = null,
    onClick: (() -> Unit)? = null
) {
    // One source for both the press animation and the click, so the row scales under a finger
    // without either one swallowing the other's gesture.
    val interactionSource = remember { MutableInteractionSource() }
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(summary) },
        leadingContent = { SettingsRowIcon(icon) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp)
                    )
                    if (onClick != null) Spacer(modifier = Modifier.width(Spacing.s))
                }
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pressScale(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = onClick
                        )
                        .semantics(mergeDescendants = true) {}
                }
            )
    )
}

/**
 * The badge a row's icon sits in: the accent's own colour, the same size on every row and page, so
 * a group reads as one list rather than a pile of controls.
 */
@Composable
internal fun SettingsRowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** A named group of rows on one surface, with the name in the accent's own colour. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(Spacing.s))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content
    )
}

/** The hairline between two rows of a group, inset to the row's text rather than to its icon. */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp)
    )
}

/**
 * A standard settings app bar and scrolling body: the page's title, the way back, and — when the
 * page has long explanations of its own — the one toolbar action that holds them, so every row
 * below stays one line.
 *
 * The navigation host handles system Back; the toolbar delegates to the same destination pop.
 *
 * The scroller's bottom is the navigation bar's inset, taken once here by the scroller itself, plus
 * the 16 dp gap that keeps a page's last action out of the gesture band. The scaffold is told not to
 * spend the bottom inset as well, so nothing is padded twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    @StringRes title: Int,
    onClose: () -> Unit,
    help: List<String>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset belongs to the scroller below, which is the only thing that knows how
        // much content there is; taking it here as well would pad every page twice.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_cd_back)
                        )
                    }
                },
                actions = { if (help != null) SettingsHelpAction(help) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.l),
                content = content
            )
        }
    }
}

/**
 * The one toolbar action a page with long explanations carries: a question mark that opens them in
 * a dialog, so the page itself stays rows instead of paragraphs. What it shows is the page's own
 * text, moved whole rather than rewritten.
 */
@Composable
internal fun SettingsHelpAction(paragraphs: List<String>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.settings_cd_help)
        )
    }
    if (open) SettingsHelpDialog(paragraphs = paragraphs, onDismiss = { open = false })
}

/** A page's own long explanation in a dialog, scrolling when it is longer than the dialog is. */
@Composable
internal fun SettingsHelpDialog(paragraphs: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                paragraphs.forEachIndexed { index, paragraph ->
                    if (index > 0) Spacer(modifier = Modifier.height(Spacing.m))
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

/**
 * The state of screen access: the neutral surface while it is on, the error container while it is
 * off. Missing access affects screen lookup; dictionary search and study remain available.
 */
@Composable
internal fun ScreenAccessCard(enabled: Boolean, onTurnOn: () -> Unit) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val heading = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val body = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
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
                        if (enabled) R.string.settings_status_on else R.string.settings_status_off
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = heading,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s))
            Text(
                text = stringResource(
                    if (enabled) {
                        R.string.settings_status_on_support
                    } else {
                        R.string.settings_status_off_support
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = body
            )
            if (!enabled) {
                Spacer(modifier = Modifier.height(Spacing.m))
                Button(onClick = onTurnOn) {
                    Text(stringResource(R.string.settings_action_turn_on))
                }
            }
        }
    }
}

/**
 * The flow behind every "Turn on": the accessibility disclosure first, and system settings only once
 * the reader has agreed to it. Returns the action that opens it, so a caller needs no state of its
 * own and the dialog is the same one wherever it is raised from.
 */
@Composable
internal fun rememberScreenAccessRequest(): () -> Unit {
    val context = LocalContext.current
    var showDisclosure by remember { mutableStateOf(false) }
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
    return { showDisclosure = true }
}

/** The name of the model in place, shown on the right of the recognition row. */
@Composable
private fun recognitionValue(installed: Boolean, installedId: String?): String = when {
    !installed -> stringResource(R.string.settings_value_none)
    else -> MangaOcrCatalog.byId(installedId)?.label
        ?: stringResource(R.string.settings_value_none)
}

/**
 * What the Anki row says under its name: the page's own business while the deck is readable, and
 * which of AnkiDroid's other two states it is in when it is not. The deck's name is the row's value
 * label, so it is not repeated here.
 */
@Composable
private fun ankiSupport(status: AnkiStatus): String = when (status) {
    AnkiStatus.NotInstalled -> stringResource(R.string.settings_row_anki_missing)
    AnkiStatus.NeedsPermission -> stringResource(R.string.settings_row_anki_permission)
    AnkiStatus.Ready -> stringResource(R.string.settings_row_anki_support)
}

/** The app's version name, or an empty string when the package manager cannot answer. */
private fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

/**
 * True when [GatenAccessibilityService] is one of the platform's enabled accessibility services,
 * which is the only honest answer to "can Gaten read the screen" that does not depend on the service
 * being alive at this moment.
 */
internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, GatenAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val services = TextUtils.SimpleStringSplitter(':')
    services.setString(enabled)
    while (services.hasNext()) {
        if (ComponentName.unflattenFromString(services.next()) == expected) return true
    }
    return false
}
