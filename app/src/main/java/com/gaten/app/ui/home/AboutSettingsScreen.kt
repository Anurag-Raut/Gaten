/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.theme.Spacing

/** Where each attribution and licence lives. Opened in the browser, which may be nowhere. */
private const val EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html"
private const val MANGA_OCR_URL = "https://github.com/kha-white/manga-ocr"
private const val MLKIT_TERMS_URL = "https://developers.google.com/ml-kit/terms"
private const val WANIKANI_URL = "https://www.wanikani.com/"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val CIRCLETOSEARCH_URL = "https://github.com/AKS-Labs/CircleToSearch"
private const val MANROPE_URL = "https://github.com/googlefonts/manrope"
private const val WANAKANA_URL = "https://github.com/esnaultdev/wanakana-kt"

/**
 * About: the version, what the app does with the screen, the only two things it ever sends anywhere,
 * and who the parts belong to.
 *
 * The two claims here are the app's contract with the reader — the screenshot and the looked-up text
 * stay on the device, and the network is used only for the model download and for WaniKani when a
 * token is connected — so they are stated as plainly as they can be. Every attribution is a row that
 * opens the licence or the project it names; with no browser to open, the tap does nothing.
 */
@Composable
fun AboutSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context) { aboutVersionName(context) }

    SettingsPageScaffold(title = R.string.settings_about_title, onClose = onClose) {
        Spacer(modifier = Modifier.height(Spacing.s))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.settings_about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.settings_about_version, versionName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        AboutCard(
            icon = Icons.Outlined.Shield,
            title = R.string.settings_about_offline_title,
            body = R.string.settings_about_offline_body
        )
        Spacer(modifier = Modifier.height(Spacing.l))
        AboutCard(
            icon = Icons.Outlined.CloudOff,
            title = R.string.settings_about_network_title,
            body = R.string.settings_about_network_body
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        SettingsGroup(title = stringResource(R.string.settings_about_attribution_title)) {
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_dict,
                summary = stringResource(R.string.settings_about_dict),
                value = "edrdg.org"
            ) { openUrl(context, EDRDG_LICENCE_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_ocr,
                summary = stringResource(R.string.settings_about_ocr),
                value = "github.com/kha-white/manga-ocr"
            ) { openUrl(context, MANGA_OCR_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_mlkit,
                summary = stringResource(R.string.settings_about_mlkit),
                value = "developers.google.com/ml-kit"
            ) { openUrl(context, MLKIT_TERMS_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_wanikani,
                summary = stringResource(R.string.settings_about_wanikani),
                value = "wanikani.com"
            ) { openUrl(context, WANIKANI_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_manrope,
                summary = stringResource(R.string.settings_about_manrope),
                value = "github.com/googlefonts/manrope"
            ) { openUrl(context, MANROPE_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Public,
                title = R.string.settings_about_attr_wanakana,
                summary = stringResource(R.string.settings_about_wanakana),
                value = "github.com/esnaultdev/wanakana-kt"
            ) { openUrl(context, WANAKANA_URL) }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        SettingsGroup(title = stringResource(R.string.settings_about_licence_title)) {
            GroupNote(text = R.string.settings_about_licence_body)
            SettingsRow(
                icon = Icons.Outlined.Gavel,
                title = R.string.settings_about_link_licence,
                summary = "gnu.org/licenses/gpl-3.0"
            ) { openUrl(context, GPL_URL) }
            SettingsRowDivider()
            SettingsRow(
                icon = Icons.Outlined.Gavel,
                title = R.string.settings_about_link_source,
                summary = "github.com/AKS-Labs/CircleToSearch"
            ) { openUrl(context, CIRCLETOSEARCH_URL) }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

/**
 * One paragraph card: a policy, the icon that stands for it, and the sentence that says it.
 *
 * It is the same surface the groups use — medium corners, `surface` on the page's `background`, one
 * step of elevation, no border — so a policy reads as a panel of the page rather than a separate
 * kind of object.
 */
@Composable
private fun AboutCard(icon: ImageVector, @StringRes title: Int, @StringRes body: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s))
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A sentence inside a group, above the rows that act on it. */
@Composable
private fun GroupNote(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)
    )
}

/** The app's version name, or an empty string when the package manager cannot answer. */
private fun aboutVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

/** Opens a URL in the browser; a no-op when no browser can handle it (the app is offline). */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser available; there is nothing sensible to do about it.
    }
}
