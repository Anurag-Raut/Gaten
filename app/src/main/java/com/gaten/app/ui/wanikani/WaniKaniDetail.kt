/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.wanikani

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gaten.app.MainActivity
import com.gaten.app.R
import com.gaten.app.ui.components.pressOpacity
import com.gaten.app.ui.lookup.InlineStatusRail
import com.gaten.app.ui.lookup.RailAction
import com.gaten.app.ui.lookup.RailGlyph
import com.gaten.app.ui.lookup.RailTone
import com.gaten.app.ui.lookup.SheetType
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.wanikani.WaniKaniComponent
import com.gaten.app.wanikani.WaniKaniFailure
import com.gaten.app.wanikani.WaniKaniLookup
import com.gaten.app.wanikani.WaniKaniRepository
import com.gaten.app.wanikani.WaniKaniSubject
import com.gaten.app.wanikani.WaniKaniTarget

/** The box a WaniKani radical image is drawn in. */
private val RadicalImageSize = 72.dp
private val RadicalImageSizeCompact = 56.dp

/** Every control on the page is this big, whatever the glyph inside it measures. */
private val PageActionTarget = 48.dp
private val PageActionGlyph = 20.dp

/**
 * The usable width below which the page's title and its controls stop sharing a row. The targets keep
 * their 48 dp; the title moves to a line of its own rather than being cut to fit beside them.
 */
private val ConstrainedHeaderWidth = 300.dp

/** The blocks the panel's loading skeleton stands in with: a line, a chip, a label, a paragraph end. */
private val SkeletonLine = 14.dp
private val SkeletonChip = 28.dp
private val SkeletonLabelWidth = 72.dp
private val SkeletonLineTail = 160.dp

/**
 * The WaniKani page for one kanji or radical, drawn in place of the dictionary entry inside the
 * result sheet: its own row with the way back and the way out to WaniKani, then meanings, readings,
 * the radicals a kanji is made of, and WaniKani's mnemonics.
 *
 * It lays out at its natural height and never scrolls itself: the sheet's one body scroller is what
 * moves it, so a long mnemonic scrolls the same way the entry above it does. Nothing here is a card
 * either — sections are captions, prose and hairlines, like the rest of the sheet.
 *
 * Every state the lookup can be in answers for itself: still looking, found (fresh or a saved
 * copy), no entry, above the account's levels, no token, a rejected token, or unreachable. Nothing
 * is shown that WaniKani did not send, and the word being read is never re-read — Back returns to
 * its dictionary entry.
 *
 * [target] is the whole key of the page: a new target restarts the lookup, and the panel is
 * recreated with it, so a radical opened over a kanji starts from its own answer.
 *
 * [onOpenSettings] is where the two states answered in the app — no token, a rejected one — send the
 * reader. The shell passes its own route when it has one; without it the app is opened the way the
 * overlay always opened it, on the WaniKani page.
 */
@Composable
internal fun WaniKaniDetailPanel(
    target: WaniKaniTarget,
    onBack: () -> Unit,
    onRadical: (Int) -> Unit,
    type: SheetType,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // Null while the answer is on its way: the repository answers once, so "loading" is the UI's own
    // state, and [retry] is what asks it again.
    var lookup by remember(target) { mutableStateOf<WaniKaniLookup?>(null) }
    var retry by remember(target) { mutableStateOf(0) }

    LaunchedEffect(target, retry) {
        lookup = null
        // A retry goes to WaniKani itself: the cached answer it is replacing is already on screen.
        lookup = WaniKaniRepository.lookup(
            context.applicationContext,
            target,
            refresh = retry > 0
        )
    }

    val answer = lookup
    val subject = (answer as? WaniKaniLookup.Available)?.subject
    // A token is typed on a real keyboard, which the overlay cannot offer, so the two token states
    // point at the WaniKani settings instead of asking for one here. The shell's own route wins when
    // it has one; otherwise the app is brought up, already on that page.
    val openSettings: () -> Unit = onOpenSettings ?: { openApp(context) }
    // The heading: a kanji's own character is this page's anchor, so it is drawn at the sheet's
    // headword size. A radical's anchor is its drawing, which lives in the body, so its name stays a
    // modest title here instead of shouting at the same size.
    val heading = when {
        subject is WaniKaniSubject.Kanji -> subject.character
        subject is WaniKaniSubject.Radical -> subject.primaryMeaning
        target is WaniKaniTarget.Kanji -> target.character
        else -> stringResource(R.string.wk_radical)
    }
    val headingIsCharacter = subject is WaniKaniSubject.Kanji || target is WaniKaniTarget.Kanji

    Column(modifier = modifier.fillMaxWidth()) {
        PageHeader(
            heading = heading,
            headingIsCharacter = headingIsCharacter,
            showRefresh = subject != null,
            pageUrl = subject?.documentUrl,
            onBack = onBack,
            onRefresh = { retry++ },
            onOpen = { url -> openWanikaniPage(context, url) },
            type = type
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s, bottom = Spacing.xl)
        ) {
            // The skeleton gives way to the answer with an opacity change and nothing else: it does
            // not shimmer, so this is the only thing that marks the moment the page arrived, and a
            // refresh failure leaves the saved page exactly where it was.
            Crossfade(
                targetState = answer,
                animationSpec = tween(Motion.SHORT, easing = Motion.emphasized),
                label = "wanikaniState"
            ) { state ->
                when (state) {
                    null -> Skeleton()

                    is WaniKaniLookup.Available -> SubjectPage(
                        subject = state.subject,
                        cached = state.cached,
                        stale = state.stale,
                        cacheFailure = state.cacheFailure,
                        type = type,
                        compact = compact,
                        onRadical = onRadical
                    )

                    WaniKaniLookup.NoEntry -> InlineStatusRail(
                        text = when (target) {
                            is WaniKaniTarget.Kanji ->
                                stringResource(R.string.wk_no_entry_kanji, target.character)

                            is WaniKaniTarget.Radical ->
                                stringResource(R.string.wk_no_entry_radical)
                        },
                        glyph = RailGlyph.SearchOff,
                        tone = RailTone.Neutral,
                        supporting = null,
                        action = null
                    )

                    // Nothing to retry: the subject is real, the account simply does not include it.
                    WaniKaniLookup.Restricted -> InlineStatusRail(
                        text = when (target) {
                            is WaniKaniTarget.Kanji ->
                                stringResource(R.string.wk_restricted_kanji, target.character)

                            is WaniKaniTarget.Radical ->
                                stringResource(R.string.wk_restricted_radical)
                        },
                        glyph = RailGlyph.Info,
                        tone = RailTone.Neutral,
                        supporting = null,
                        action = null
                    )

                    // A token is typed on a real keyboard, which the overlay cannot offer, so both of
                    // these point at the WaniKani settings instead of asking for one here.
                    WaniKaniLookup.NeedsToken -> InlineStatusRail(
                        text = stringResource(R.string.wk_needs_token),
                        glyph = RailGlyph.Info,
                        tone = RailTone.Neutral,
                        supporting = null,
                        action = RailAction(stringResource(R.string.wk_open_app), openSettings)
                    )

                    WaniKaniLookup.AuthRejected -> InlineStatusRail(
                        text = stringResource(R.string.wk_auth),
                        glyph = RailGlyph.Error,
                        tone = RailTone.Error,
                        supporting = null,
                        action = RailAction(stringResource(R.string.wk_open_app), openSettings)
                    )

                    is WaniKaniLookup.Unreachable -> InlineStatusRail(
                        text = stringResource(R.string.wk_unreachable),
                        glyph = RailGlyph.Error,
                        tone = RailTone.Error,
                        supporting = stringResource(wanikaniFailureLabel(state.failure)),
                        action = RailAction(stringResource(R.string.wk_retry)) { retry++ }
                    )
                }
            }
        }
    }
}

/**
 * The page's own row: the way back, the character or name being read, and the two things the reader
 * can do with the page itself — ask WaniKani again, or open it there.
 *
 * At a constrained measure the title and the controls take a row each instead of the targets
 * shrinking: a 48 dp control is the part of this row that has to survive a large text scale.
 */
@Composable
private fun PageHeader(
    heading: String,
    headingIsCharacter: Boolean,
    showRefresh: Boolean,
    pageUrl: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    type: SheetType
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val stacked = maxWidth.value / fontScale < ConstrainedHeaderWidth.value
        val title: @Composable (Modifier) -> Unit = { placement ->
            Text(
                text = heading,
                // The page's own title step: the radical's name. A kanji's heading is a glyph and
                // keeps the sheet's headword size instead.
                style = if (headingIsCharacter) type.headword else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = placement
            )
        }

        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = PageActionTarget),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PageAction(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        R.string.wk_cd_back,
                        onBack
                    )
                    if (showRefresh) {
                        PageAction(Icons.Rounded.Refresh, R.string.wk_cd_refresh, onRefresh)
                    }
                    if (pageUrl != null) {
                        PageAction(Icons.AutoMirrored.Rounded.OpenInNew, R.string.wk_cd_open) { onOpen(pageUrl) }
                    }
                }
                title(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = PageActionTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageAction(Icons.AutoMirrored.Filled.ArrowBack, R.string.wk_cd_back, onBack)
                title(Modifier.weight(1f))
                if (showRefresh) {
                    PageAction(Icons.Rounded.Refresh, R.string.wk_cd_refresh, onRefresh)
                }
                if (pageUrl != null) {
                    PageAction(Icons.AutoMirrored.Rounded.OpenInNew, R.string.wk_cd_open) { onOpen(pageUrl) }
                }
            }
        }
    }
}

/** One header control: a bare neutral glyph in a 48 dp target, in both layouts. */
@Composable
private fun PageAction(icon: ImageVector, @StringRes description: Int, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.size(PageActionTarget).pressOpacity(interactions)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(PageActionGlyph)
        )
    }
}

/** One subject's page: its glyph, meanings, readings, the radicals behind it, and its mnemonics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectPage(
    subject: WaniKaniSubject,
    cached: Boolean,
    stale: Boolean,
    cacheFailure: WaniKaniFailure?,
    type: SheetType,
    compact: Boolean,
    onRadical: (Int) -> Unit
) {
    when (subject) {
        is WaniKaniSubject.Kanji -> {
            // The character is already the panel's heading, so the page starts with its meaning.
            Meanings(subject.primaryMeaning, subject.meanings, type)
            if (subject.onyomi.isNotEmpty()) {
                Readings(stringResource(R.string.wk_onyomi), subject.onyomi, type)
            }
            if (subject.kunyomi.isNotEmpty()) {
                Readings(stringResource(R.string.wk_kunyomi), subject.kunyomi, type)
            }
            if (subject.components.isNotEmpty()) {
                Caption(stringResource(R.string.wk_radicals), type)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    subject.components.forEach { component ->
                        ComponentAction(component, type, onClick = { onRadical(component.id) })
                    }
                }
            }
            Mnemonic(R.string.wk_meaning_mnemonic, subject.meaningMnemonic, type)
            Mnemonic(R.string.wk_reading_mnemonic, subject.readingMnemonic, type)
        }

        is WaniKaniSubject.Radical -> {
            val image = subject.svg
            when {
                // The image is WaniKani's own drawing of the radical, and the only form some
                // radicals have: those are exactly the ones a system font cannot draw.
                image != null -> RadicalImage(
                    svg = image,
                    size = if (compact) RadicalImageSizeCompact else RadicalImageSize,
                    description = subject.character ?: subject.primaryMeaning
                )

                subject.character != null -> Text(
                    text = subject.character,
                    style = type.headword,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Image-only and nothing cached: the name is all there is to draw, and it is what
                // WaniKani calls the radical anyway.
                else -> Text(
                    text = subject.primaryMeaning,
                    style = type.headword,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Meanings(subject.primaryMeaning, subject.meanings, type)
            Mnemonic(R.string.wk_meaning_mnemonic, subject.meaningMnemonic, type)
        }
    }

    // A page that could not be written to the device is here for this session only: saying so up
    // front is what stops the reader finding out offline. Distinct from a stale copy, which is the
    // saved one being shown because a refresh failed — so both can be true, and both are said.
    if (cacheFailure != null) {
        InlineStatusRail(
            text = stringResource(R.string.wk_not_saved),
            glyph = RailGlyph.Error,
            tone = RailTone.Error,
            supporting = null,
            action = null
        )
    }
    if (stale) {
        InlineStatusRail(
            text = stringResource(R.string.wk_stale),
            glyph = RailGlyph.Info,
            tone = RailTone.Neutral,
            supporting = stringResource(R.string.wk_stale_support),
            action = null
        )
    } else if (cached) {
        InlineStatusRail(
            text = stringResource(R.string.wk_saved),
            glyph = RailGlyph.Info,
            tone = RailTone.Neutral,
            supporting = null,
            action = null
        )
    }
}

/**
 * The panel while its answer is on its way: the shape the page will take, so switching between two
 * kanji holds the layout still instead of blanking out and filling in a beat later. Plain blocks in
 * the surface's own colour, square and unanimated — no shimmer, no radius — and the real page drops
 * into their place.
 *
 * The heading is not part of this: the header row already shows the character being looked up. A
 * failure, an empty answer or a token that needs connecting each draw their own state; this is the
 * in-flight one only.
 */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.wk_loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The state is still announced, even though it is drawn as shape rather than words.
            .semantics { contentDescription = loading }
    ) {
        SkeletonLabel()
        SkeletonBlock()
        SkeletonLabel()
        SkeletonBlock(width = SkeletonLabelWidth)
        SkeletonLabel()
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            repeat(4) { SkeletonBlock(width = SkeletonChip, height = SkeletonChip) }
        }
        SkeletonLabel()
        SkeletonParagraph(lines = 3)
        SkeletonLabel()
        SkeletonParagraph(lines = 2)
    }
}

/** A label-sized block, in the same rhythm a real caption keeps: more gap above it than below. */
@Composable
private fun SkeletonLabel() {
    Spacer(Modifier.height(Spacing.xl))
    SkeletonBlock(width = SkeletonLabelWidth)
    Spacer(Modifier.height(Spacing.s))
}

/** One placeholder block: the surface's own colour, square, and with nothing to shimmer. */
@Composable
private fun SkeletonBlock(width: Dp? = null, height: Dp = SkeletonLine) {
    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** A paragraph's worth of lines, the last one short like a real paragraph's end. */
@Composable
private fun SkeletonParagraph(lines: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(lines) { index ->
            if (index > 0) Spacer(Modifier.height(Spacing.s))
            SkeletonBlock(width = if (index == lines - 1) SkeletonLineTail else null)
        }
    }
}

/** The meanings: WaniKani's own first, the alternates behind it. */
@Composable
private fun Meanings(primary: String, all: List<String>, type: SheetType) {
    Caption(stringResource(R.string.wk_meaning), type)
    Text(
        text = primary,
        style = type.gloss,
        color = MaterialTheme.colorScheme.onSurface
    )
    val alternates = all.filterNot { it == primary }
    if (alternates.isNotEmpty()) {
        Text(
            text = alternates.joinToString(" · "),
            style = type.gloss,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One set of readings — WaniKani keeps on'yomi and kun'yomi apart, so they are shown apart.
 *
 * Readings are secondary to the meaning, so they keep the reading role in the quiet ink: the accent
 * on this page belongs to the characters that are actions, not to a heading.
 */
@Composable
private fun Readings(label: String, readings: List<String>, type: SheetType) {
    Caption(label, type)
    Text(
        text = readings.joinToString("・"),
        style = type.reading,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * One mnemonic: WaniKani's own prose, with its markup already stripped by the repository, so this
 * is plain text — readable, and the only thing the reader can do with it is select it.
 */
@Composable
private fun Mnemonic(@StringRes label: Int, text: String, type: SheetType) {
    if (text.isBlank()) return
    Caption(stringResource(label), type)
    SelectionContainer {
        Text(
            text = text,
            style = type.gloss,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * One radical a kanji is made of: the glyph when it has one, WaniKani's name for it when it has
 * none. Unfilled and in the accent, like the kanji strip in the entry: the name is what the reader
 * can act on either way, and the drawing itself is on the radical's own page, one tap away. A
 * component whose cache is empty (a first look, offline) shows as unknown and still opens its page,
 * which may have it.
 */
@Composable
private fun ComponentAction(component: WaniKaniComponent, type: SheetType, onClick: () -> Unit) {
    val glyph = component.character
    val label = glyph ?: component.name ?: "…"
    val description = stringResource(R.string.wk_cd_radical, label)
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = PageActionTarget, minHeight = PageActionTarget)
            .pressOpacity(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .semantics { contentDescription = description }
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs)
    ) {
        Text(
            text = label,
            style = if (glyph != null) type.headword else type.reading,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The small muted label above one block of the page, with the rhythm the whole panel follows: 20 dp
 * above it — the separation between two sections — and 8 dp below, so a label reads as belonging to
 * what follows it.
 */
@Composable
private fun Caption(text: String, type: SheetType) {
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = text,
        style = type.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.s))
}

/**
 * A WaniKani radical image, drawn from the SVG the repository cached.
 *
 * The whole document is that SVG: the view it is loaded in runs no JavaScript, has no file or
 * content access, and has its network switched off, so nothing it holds can load, run or navigate
 * anywhere — the drawing WaniKani sent is all that can happen. The view is destroyed with the page
 * so a sheet that is opened and closed many times does not keep WebView instances alive.
 */
@Composable
private fun RadicalImage(svg: String, size: Dp, description: String) {
    val dark = isSystemInDarkTheme()
    val html = remember(svg, dark) { svgDocument(svg, dark) }
    AndroidView(
        modifier = Modifier.size(size),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isClickable = false
                isFocusable = false
                // Display only, and often inside a scrolling parent: a disabled view neither takes
                // the touch nor changes how the drawing looks.
                isEnabled = false
                contentDescription = description
                webViewClient = object : WebViewClient() {
                    // One cached drawing and nothing else: a tap can never navigate away from it.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = true
                }
            }
        },
        update = { view -> view.loadDocumentOnce(html) },
        // Detach before destroying: the WebView must not be torn down while it is still in the view
        // tree, which is where a released Compose view can be for one more frame.
        onRelease = { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    )
}

/** Loads [document] unless this view already holds it, so recomposing never reloads the image. */
private fun WebView.loadDocumentOnce(document: String) {
    if (tag == document) return
    tag = document
    loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
}

/**
 * [svg] as a standalone document: centred, transparent, and referring to nothing but itself.
 *
 * JavaScript, file access, content access and the network are all off in the view, so the SVG's own
 * markup is the only thing that can act. WaniKani's radical art is single-colour black, which a
 * dark theme would swallow, so it is inverted there.
 */
private fun svgDocument(svg: String, dark: Boolean): String {
    val invert = if (dark) "svg{filter:invert(1)}" else ""
    return "<!doctype html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;height:100%;background:transparent}" +
        "body{display:flex;align-items:center;justify-content:center}" +
        "svg{width:100%;height:100%}$invert</style></head><body>" + svg + "</body></html>"
}

/**
 * Opens a WaniKani page in the browser — a subject's page, or the token settings the connect step
 * needs. The host has to be WaniKani's own: the helper is the one place a URL leaves the app, so a
 * URL that is not on `wanikani.com` is simply never opened. A no-op when no browser can take it.
 *
 * The overlay is a service window, so the intent needs a task of its own.
 */
internal fun openWanikaniPage(context: Context, url: String) {
    val uri = Uri.parse(url)
    val host = uri.host ?: return
    if (host != WANIKANI_HOST && !host.endsWith(".$WANIKANI_HOST")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser available; the page stays where it is.
    }
}

/** WaniKani's own site; the only host the app ever hands to a browser. */
private const val WANIKANI_HOST = "wanikani.com"

/** The extra the shell reads to open the app on a given settings page, and its WaniKani value. */
private const val SETTINGS_PAGE_EXTRA = "settings_page"
private const val SETTINGS_PAGE_WANIKANI = "wanikani"

/**
 * Brings the setup app up from the overlay, where there is no task to put it in, and asks for the
 * WaniKani page: the two states that call this one are both about a token, so landing on Home would
 * only make the reader find the page themselves. The shell decides what the flag means; a caller
 * that ignores it still lands on the app's own start screen.
 */
private fun openApp(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SETTINGS_PAGE_EXTRA, SETTINGS_PAGE_WANIKANI)
    )
}

/** The reason a WaniKani request could not be answered, in the reader's words. */
@StringRes
internal fun wanikaniFailureLabel(failure: WaniKaniFailure): Int = when (failure) {
    WaniKaniFailure.OFFLINE -> R.string.wk_failure_offline
    WaniKaniFailure.TIMEOUT -> R.string.wk_failure_timeout
    WaniKaniFailure.RATE_LIMITED -> R.string.wk_failure_rate_limited
    WaniKaniFailure.SERVER -> R.string.wk_failure_server
    WaniKaniFailure.TOO_LARGE -> R.string.wk_failure_too_large
    WaniKaniFailure.MALFORMED -> R.string.wk_failure_malformed
    WaniKaniFailure.STORAGE -> R.string.wk_failure_storage
}
