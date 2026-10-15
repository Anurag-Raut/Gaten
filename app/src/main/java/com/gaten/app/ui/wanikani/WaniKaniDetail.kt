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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gaten.app.MainActivity
import com.gaten.app.R
import com.gaten.app.ui.lookup.SheetType
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

/** The blocks the panel's loading skeleton stands in with: a line, a chip, a label, a paragraph end. */
private val SkeletonLine = 14.dp
private val SkeletonChip = 28.dp
private val SkeletonLabelWidth = 72.dp
private val SkeletonLineTail = 160.dp

/**
 * The distinct kanji of [text], in order, one string per code point.
 *
 * Iterating code points rather than Chars is what keeps a supplementary-plane kanji such as 𠮷
 * whole: as Chars it is a surrogate pair, and `Character.isIdeographic` on half of it says no.
 */
internal fun kanjiIn(text: String): List<String> {
    val found = LinkedHashSet<String>()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isIdeographic(codePoint)) found.add(String(Character.toChars(codePoint)))
        index += Character.charCount(codePoint)
    }
    return found.toList()
}

/**
 * The kanji of the word the entry is showing, each one a tap away from its WaniKani page.
 *
 * Drawn from the headword the entry is already headed with, and only when it has a kanji at all: a
 * kana-only word has nothing to look up. Every kanji is tappable whatever WaniKani covers, because
 * what WaniKani has is not known before the tap; a character it does not cover says so there.
 */
@Composable
internal fun KanjiStrip(
    headword: String,
    onKanji: (String) -> Unit,
    type: SheetType,
    modifier: Modifier = Modifier
) {
    KanjiStrip(remember(headword) { kanjiIn(headword) }, onKanji, type, modifier = modifier)
}

/**
 * The same strip for a caller that already knows which characters to offer — the review screen,
 * which keeps only the kanji its question side already shows, so a tap can never reveal part of the
 * answer. The characters are drawn in the order given, one chip each, and [selected] marks the one
 * whose details are open underneath: the chip that opened them is also the chip that closes them.
 *
 * [modifier] lands on the chip row itself, which is what a caller wanting to scroll the strip into
 * view measures; the label above it is part of the strip's own spacing.
 */
@Composable
internal fun KanjiStrip(
    characters: List<String>,
    onKanji: (String) -> Unit,
    type: SheetType,
    selected: String? = null,
    modifier: Modifier = Modifier
) {
    if (characters.isEmpty()) return

    Spacer(Modifier.height(Spacing.s))
    Text(
        text = stringResource(R.string.wk_strip_label),
        style = type.small,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xs))
    Row(
        modifier = Modifier.fillMaxWidth().then(modifier).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        characters.forEach { character ->
            val description = stringResource(R.string.wk_cd_kanji, character)
            val open = character == selected
            Surface(
                onClick = { onKanji(character) },
                shape = MaterialTheme.shapes.medium,
                color = if (open) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                border = BorderStroke(
                    1.dp,
                    if (open) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                Text(
                    text = character,
                    style = type.headword,
                    color = if (open) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
                )
            }
        }
    }
}

/**
 * The WaniKani page for one kanji or radical, drawn in place of the dictionary entry inside the
 * result sheet: a pinned row with the way back and the way out to WaniKani, then meanings, readings,
 * the radicals a kanji is made of, and WaniKani's mnemonics, scrolling on its own so a long
 * mnemonic never pushes the back row off a short sheet.
 *
 * Every state the lookup can be in answers for itself: still looking, found (fresh or a saved
 * copy), no entry, above the account's levels, no token, a rejected token, or unreachable. Nothing
 * is shown that WaniKani did not send, and the word being read is never re-read — Back returns to
 * its dictionary entry.
 *
 * [target] is the whole key of the page: a new target restarts the lookup, and the panel is
 * recreated with it, so a radical opened over a kanji starts from its own answer.
 *
 * [showBack] is for a caller that has somewhere to go back to, which is the sheet's page stack. The
 * review screen hides it: its inline panel is closed by the same chip that opened it, so an arrow
 * there would only be clutter beside the heading.
 *
 * [scrollable] is for a caller that has nowhere to put a long page: the sheet caps its panel, so the
 * page scrolls inside it. The review screen scrolls the whole deck space instead, and its panel is
 * laid out at its natural height with nothing scrolling inside it.
 */
@Composable
internal fun WaniKaniDetailPanel(
    target: WaniKaniTarget,
    onBack: () -> Unit,
    onRadical: (Int) -> Unit,
    type: SheetType,
    compact: Boolean,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    scrollable: Boolean = true
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
    val iconSize = if (compact) 18.dp else 20.dp
    // The heading: a kanji's own character is this page's anchor, so it is drawn at the sheet's
    // headword size in the primary text colour. A radical's anchor is its drawing, which lives in
    // the body, so its name stays a modest title here instead of shouting at the same size.
    val heading = when {
        subject is WaniKaniSubject.Kanji -> subject.character
        subject is WaniKaniSubject.Radical -> subject.primaryMeaning
        target is WaniKaniTarget.Kanji -> target.character
        else -> stringResource(R.string.wk_radical)
    }
    val headingIsCharacter = subject is WaniKaniSubject.Kanji || target is WaniKaniTarget.Kanji

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(
                    onClick = onBack,
                    modifier = if (compact) Modifier.size(36.dp) else Modifier
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.wk_cd_back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
            Text(
                text = heading,
                style = if (headingIsCharacter) type.headword else type.reading,
                color = if (headingIsCharacter) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (subject != null) {
                // The one way to ask WaniKani again: a saved page never expires on its own. A
                // refresh that cannot reach WaniKani leaves the saved page where it is.
                IconButton(
                    onClick = { retry++ },
                    modifier = if (compact) Modifier.size(36.dp) else Modifier
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.wk_cd_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                }
                // A subject whose page WaniKani did not name with an https URL on its own host has
                // none to offer, and then no link is drawn.
                val page = subject.documentUrl
                if (page != null) {
                    IconButton(
                        onClick = { openWanikaniPage(context, page) },
                        modifier = if (compact) Modifier.size(36.dp) else Modifier
                    ) {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.wk_cd_open),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
            }
        }

        // The page's own scrolling part when the caller wants one: the sheet caps its panel, so a
        // long mnemonic scrolls here instead of pushing the header off. The review screen passes
        // scrollable = false and scrolls the whole deck space instead, so this lays out at its
        // natural height there and nothing scrolls inside anything else.
        val pageScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (scrollable) {
                        Modifier.weight(1f, fill = false).verticalScroll(pageScroll)
                    } else {
                        Modifier
                    }
                )
                .padding(
                    top = if (compact) Spacing.xs else Spacing.s,
                    bottom = if (compact) Spacing.l else Spacing.xl
                )
        ) {
            when (answer) {
                null -> Skeleton(compact = compact)

                is WaniKaniLookup.Available -> SubjectPage(
                    subject = answer.subject,
                    cached = answer.cached,
                    stale = answer.stale,
                    cacheFailure = answer.cacheFailure,
                    type = type,
                    compact = compact,
                    onRadical = onRadical
                )

                WaniKaniLookup.NoEntry -> Notice(
                    message = when (target) {
                        is WaniKaniTarget.Kanji ->
                            stringResource(R.string.wk_no_entry_kanji, target.character)

                        is WaniKaniTarget.Radical ->
                            stringResource(R.string.wk_no_entry_radical)
                    },
                    type = type
                )

                // Nothing to retry: the subject is real, the account simply does not include it.
                WaniKaniLookup.Restricted -> Notice(
                    message = when (target) {
                        is WaniKaniTarget.Kanji ->
                            stringResource(R.string.wk_restricted_kanji, target.character)

                        is WaniKaniTarget.Radical ->
                            stringResource(R.string.wk_restricted_radical)
                    },
                    type = type
                )

                // A token is typed on a real keyboard, which the overlay cannot offer, so both of
                // these point at the app instead of asking for one here.
                WaniKaniLookup.NeedsToken -> OpenAppNotice(
                    message = stringResource(R.string.wk_needs_token),
                    type = type,
                    onOpen = { openApp(context) }
                )

                WaniKaniLookup.AuthRejected -> OpenAppNotice(
                    message = stringResource(R.string.wk_auth),
                    type = type,
                    onOpen = { openApp(context) }
                )

                is WaniKaniLookup.Unreachable -> Notice(
                    message = stringResource(
                        R.string.wk_unreachable,
                        stringResource(wanikaniFailureLabel(answer.failure))
                    ),
                    type = type,
                    actionLabel = stringResource(R.string.wk_retry),
                    onAction = { retry++ }
                )
            }
        }
    }
}

/** One subject's page: its glyph, meanings, readings, the radicals behind it, and its mnemonics. */
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
            Meanings(subject.primaryMeaning, subject.meanings, type, compact)
            if (subject.onyomi.isNotEmpty()) {
                Readings(stringResource(R.string.wk_onyomi), subject.onyomi, type, compact)
            }
            if (subject.kunyomi.isNotEmpty()) {
                Readings(stringResource(R.string.wk_kunyomi), subject.kunyomi, type, compact)
            }
            if (subject.components.isNotEmpty()) {
                Caption(stringResource(R.string.wk_radicals), type, compact)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    subject.components.forEach { component ->
                        ComponentChip(component, type, onClick = { onRadical(component.id) })
                    }
                }
            }
            Mnemonic(R.string.wk_meaning_mnemonic, subject.meaningMnemonic, type, compact)
            Mnemonic(R.string.wk_reading_mnemonic, subject.readingMnemonic, type, compact)
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
                    color = MaterialTheme.colorScheme.primary
                )

                // Image-only and nothing cached: the name is all there is to draw, and it is what
                // WaniKani calls the radical anyway.
                else -> Text(
                    text = subject.primaryMeaning,
                    style = type.headword,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Meanings(subject.primaryMeaning, subject.meanings, type, compact)
            Mnemonic(R.string.wk_meaning_mnemonic, subject.meaningMnemonic, type, compact)
        }
    }

    // A page that could not be written to the device is here for this session only: saying so up
    // front is what stops the reader finding out offline. Distinct from a stale copy, which is the
    // saved one being shown because a refresh failed.
    if (cacheFailure != null) {
        Spacer(Modifier.height(Spacing.s))
        Note(stringResource(R.string.wk_not_saved), type, warning = true)
    }
    if (cached) {
        Spacer(Modifier.height(Spacing.s))
        Note(stringResource(R.string.wk_saved), type)
    }
    if (stale) {
        Spacer(Modifier.height(Spacing.s))
        Note(stringResource(R.string.wk_stale), type)
    }
}

/**
 * The panel while its answer is on its way: the shape the page will take, so switching between two
 * kanji holds the layout still instead of blanking out and filling in a beat later. Plain blocks in
 * the surface's own colour — no shimmer, no animation — and the real page drops into their place.
 *
 * The heading is not part of this: the header row already shows the character being looked up. A
 * failure, an empty answer or a token that needs connecting each draw their own state; this is the
 * in-flight one only, and it is the panel's loading state in the sheet as much as inline.
 */
@Composable
private fun Skeleton(compact: Boolean) {
    val loading = stringResource(R.string.wk_loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The state is still announced, even though it is drawn as shape rather than words.
            .semantics { contentDescription = loading }
    ) {
        SkeletonLabel(compact)
        SkeletonBlock()
        SkeletonLabel(compact)
        SkeletonBlock(width = SkeletonLabelWidth)
        SkeletonLabel(compact)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            repeat(4) { SkeletonBlock(width = SkeletonChip, height = SkeletonChip) }
        }
        SkeletonLabel(compact)
        SkeletonParagraph(lines = 3)
        SkeletonLabel(compact)
        SkeletonParagraph(lines = 2)
    }
}

/** A label-sized block, in the same rhythm a real caption keeps: more gap above it than below. */
@Composable
private fun SkeletonLabel(compact: Boolean) {
    Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
    SkeletonBlock(width = SkeletonLabelWidth)
    Spacer(Modifier.height(Spacing.xs))
}

/** One placeholder block: the surface's own colour, nothing to shimmer, no animation at all. */
@Composable
private fun SkeletonBlock(width: Dp? = null, height: Dp = SkeletonLine) {
    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
    )
}

/** A paragraph's worth of lines, the last one short like a real paragraph's end. */
@Composable
private fun SkeletonParagraph(lines: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(lines) { index ->
            if (index > 0) Spacer(Modifier.height(Spacing.xs))
            SkeletonBlock(width = if (index == lines - 1) SkeletonLineTail else null)
        }
    }
}

/** The meanings: WaniKani's own first, the alternates behind it. */
@Composable
private fun Meanings(primary: String, all: List<String>, type: SheetType, compact: Boolean) {
    Caption(stringResource(R.string.wk_meaning), type, compact)
    Text(
        text = primary,
        style = type.gloss,
        color = MaterialTheme.colorScheme.onSurface
    )
    val alternates = all.filterNot { it == primary }
    if (alternates.isNotEmpty()) {
        Text(
            text = alternates.joinToString(" · "),
            style = type.sub,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One set of readings — WaniKani keeps on'yomi and kun'yomi apart, so they are shown apart. */
@Composable
private fun Readings(label: String, readings: List<String>, type: SheetType, compact: Boolean) {
    Caption(label, type, compact)
    Text(
        text = readings.joinToString("・"),
        style = type.reading,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * One mnemonic: WaniKani's own prose, with its markup already stripped by the repository, so this
 * is plain text — readable, and the only thing the reader can do with it is select it.
 */
@Composable
private fun Mnemonic(@StringRes label: Int, text: String, type: SheetType, compact: Boolean) {
    if (text.isBlank()) return
    Caption(stringResource(label), type, compact)
    SelectionContainer {
        Text(
            text = text,
            style = type.menu,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * One radical a kanji is made of: the glyph when it has one, WaniKani's name for it when it has
 * none. The name is what the reader can act on either way; the drawing itself is on the radical's
 * own page, one tap away. A component whose cache is empty (a first look, offline) shows as
 * unknown and still opens its page, which may have it.
 */
@Composable
private fun ComponentChip(component: WaniKaniComponent, type: SheetType, onClick: () -> Unit) {
    val glyph = component.character
    val label = glyph ?: component.name ?: "…"
    val description = stringResource(R.string.wk_cd_radical, label)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Text(
            text = label,
            style = type.chip,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
        )
    }
}

/**
 * The small muted label above one block of the page, with the rhythm the whole panel follows: a
 * wider gap above it than below, so a label reads as belonging to what follows it.
 */
@Composable
private fun Caption(text: String, type: SheetType, compact: Boolean) {
    Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
    Text(
        text = text,
        style = type.small,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xs))
}

/** One muted line inside the page, for the notes about how a page was answered. */
@Composable
private fun Note(text: String, type: SheetType, warning: Boolean = false) {
    Text(
        text = text,
        style = type.small,
        color = if (warning) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/**
 * A state with no page to show: what happened, and — when something can be done about it — the one
 * action that can. Nothing is offered where nothing would change anything.
 */
@Composable
private fun Notice(
    message: String,
    type: SheetType,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s, bottom = Spacing.l)) {
        Text(
            text = message,
            style = type.menu,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.m))
            OutlinedButton(onClick = onAction) { Text(actionLabel, style = type.button) }
        }
    }
}

/** The two notices that are answered in the app: a token is typed there, never in the overlay. */
@Composable
private fun OpenAppNotice(message: String, type: SheetType, onOpen: () -> Unit) {
    Notice(
        message = message,
        type = type,
        actionLabel = stringResource(R.string.wk_open_app),
        onAction = onOpen
    )
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

/** Brings the setup app up from the overlay, where there is no task to put it in. */
private fun openApp(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
