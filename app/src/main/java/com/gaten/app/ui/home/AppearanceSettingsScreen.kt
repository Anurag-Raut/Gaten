/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackPreferences
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.AccentColor
import com.gaten.app.ui.theme.Palette
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.theme.ThemeMode
import com.gaten.app.ui.theme.accentRoles
import com.gaten.app.ui.theme.paletteNeutrals

/** One accent swatch, and the inset its selected ring and its colour circle share. */
private val SwatchSize = 40.dp
private val SwatchInset = 5.dp

/**
 * The swatch's tap area: the circle drawn at [SwatchSize] centred inside it, so a fingertip has the
 * 48 dp the platform asks for without the colour itself growing past the size the other swatches use.
 */
private val SwatchTargetSize = 48.dp

/**
 * Appearance: dark or light, the paper the app is written on, and the one accent in it.
 *
 * Every choice applies the moment it is tapped — the theme is applied above the shell, so a tap here
 * recolours this very page — and is stored by [com.gaten.app.ui.theme.ThemePreferences], which the
 * overlay and the floating bubble read too.
 */
@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    accent: AccentColor,
    onAccentChange: (AccentColor) -> Unit,
    onClose: () -> Unit
) {
    // What the swatches below sit on: the reader's choice, not their device's.
    val dark = themeMode.isDark(isSystemInDarkTheme())

    SettingsPageScaffold(title = R.string.settings_appearance_title, onClose = onClose) {
        Spacer(modifier = Modifier.height(Spacing.s))

        ChoiceCard(
            title = R.string.settings_appearance_theme_title,
            support = R.string.settings_appearance_theme_support
        ) {
            ThemeChoices(mode = themeMode, onChange = onThemeModeChange)
        }

        Spacer(modifier = Modifier.height(Spacing.l))

        ChoiceCard(
            title = R.string.settings_appearance_palette_title,
            support = R.string.settings_appearance_palette_support
        ) {
            PaletteChoices(dark = dark, selected = palette, onSelect = onPaletteChange)
        }

        Spacer(modifier = Modifier.height(Spacing.l))

        ChoiceCard(
            title = R.string.settings_appearance_accent_title,
            support = R.string.settings_appearance_accent_support
        ) {
            AccentChoices(dark = dark, selected = accent, onSelect = onAccentChange)
        }

        Spacer(modifier = Modifier.height(Spacing.l))

        // Feedback is last: it is the one group here that changes what the app does rather than how
        // it looks, and it is the only place either cue can be turned on.
        SettingsGroup(title = stringResource(R.string.feedback_group)) {
            SettingsRow(
                icon = Icons.Outlined.Vibration,
                title = R.string.feedback_haptics_title,
                summary = stringResource(R.string.feedback_haptics_summary)
            )
            SettingsRowDivider()
            SoundsRow()
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

/** The theme choice: follow the device, or force light or dark. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChoices(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val modes = ThemeMode.entries
        modes.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = entry == mode,
                onClick = { onChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(entry.labelRes())) }
            )
        }
    }
}

/**
 * Named paper-and-ink previews for each palette, in the mode the reader's choice resolves to. No
 * accent is drawn: the palette controls neutrals only, and its preview must not look like another
 * accent picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteChoices(dark: Boolean, selected: Palette, onSelect: (Palette) -> Unit) {
    // Solves each palette's neutral colours once per mode, not once per drawn frame.
    val swatches = remember(dark) {
        Palette.entries.associateWith { entry -> paletteNeutrals(entry, dark) }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        for (entry in Palette.entries) {
            val isSelected = entry == selected
            val name = stringResource(entry.labelRes())
            val tones = swatches.getValue(entry)
            Column(
                modifier = Modifier
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(entry) }
                    )
                    .semantics { contentDescription = name },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(tones.background),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_appearance_palette_sample),
                        style = MaterialTheme.typography.titleMedium,
                        color = tones.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * The accent: the one colour in the app, behind every dot, chip, button, outline and lasso. Each
 * swatch is drawn in the accent's own tone for the current mode, so what is picked is what will be
 * drawn.
 *
 * The swatches wrap four to a line, which lays all seven out as a 4 + 3 block rather than the ragged
 * row a plain wrap produced, and each carries its own name underneath: seven unlabelled circles leave
 * the reader guessing which is which. The selection is the ring the swatch already drew, so a tap
 * that lands on the accent already chosen changes nothing and gets no tick for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentChoices(dark: Boolean, selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val feedback = rememberFeedback()
    FlowRow(
        maxItemsInEachRow = 4,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        for (accent in AccentColor.entries) {
            val isSelected = accent == selected
            val name = stringResource(accent.labelRes())
            val interactionSource = remember { MutableInteractionSource() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .pressScale(interactionSource)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSelected) feedback.haptic(FeedbackHaptic.Tick)
                            onSelect(accent)
                        }
                    )
                    .semantics { contentDescription = name }
            ) {
                Box(
                    modifier = Modifier.size(SwatchTargetSize),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(SwatchSize)
                            .clip(CircleShape)
                            // The ring sits in the swatch's own inset, so it is drawn beside the colour
                            // rather than on top of it and stays readable on every accent.
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .padding(SwatchInset)
                            .clip(CircleShape)
                            .background(accentRoles(accent, dark).primary)
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * One appearance choice: what it is, what it does, and the controls under it.
 *
 * It wears the same surface as every group in Settings — medium corners, `surface` on the page's
 * `background`, one step of elevation, no border — so the three choices read as one list rather than
 * three differently-styled panels.
 */
@Composable
private fun ChoiceCard(
    @StringRes title: Int,
    @StringRes support: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.m))
            content()
        }
    }
}

/**
 * The sounds switch: the app's own cues, which are off until the reader asks for them.
 *
 * The row states its own value and is the only control in the group, so it carries the switch rather
 * than a chevron. Toggling reads and writes the same preference the rest of the app consults before
 * it plays anything, so a change here is what makes the next cue audible — the tick is given only
 * when the value actually moved.
 */
@Composable
private fun SoundsRow() {
    val context = LocalContext.current
    val feedback = rememberFeedback()
    val title = stringResource(R.string.feedback_sounds_title)
    var enabled by remember { mutableStateOf(FeedbackPreferences.soundsEnabled(context)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Outlined.VolumeUp)
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.feedback_sounds_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(
            checked = enabled,
            onCheckedChange = { next ->
                if (next != enabled) {
                    enabled = next
                    FeedbackPreferences.setSoundsEnabled(context, next)
                    feedback.haptic(FeedbackHaptic.Tick)
                }
            },
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/** The label on each theme option; the enum itself is storage, not copy. */
@StringRes
internal fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.System -> R.string.settings_appearance_theme_system
    ThemeMode.Light -> R.string.settings_appearance_theme_light
    ThemeMode.Dark -> R.string.settings_appearance_theme_dark
}

/** The name on each palette swatch; the enum itself is storage, not copy. */
@StringRes
internal fun Palette.labelRes(): Int = when (this) {
    Palette.Gaten -> R.string.settings_appearance_palette_gaten
    Palette.Mono -> R.string.settings_appearance_palette_mono
    Palette.Cool -> R.string.settings_appearance_palette_cool
    Palette.Warm -> R.string.settings_appearance_palette_warm
}

/** The name on each accent swatch; the enum itself is storage, not copy. */
@StringRes
internal fun AccentColor.labelRes(): Int = when (this) {
    AccentColor.Indigo -> R.string.settings_appearance_accent_indigo
    AccentColor.Blue -> R.string.settings_appearance_accent_blue
    AccentColor.Violet -> R.string.settings_appearance_accent_violet
    AccentColor.Teal -> R.string.settings_appearance_accent_teal
    AccentColor.Green -> R.string.settings_appearance_accent_green
    AccentColor.Rose -> R.string.settings_appearance_accent_rose
    AccentColor.Graphite -> R.string.settings_appearance_accent_graphite
}
