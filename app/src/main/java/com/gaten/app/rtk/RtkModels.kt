/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rtk

import org.json.JSONArray
import org.json.JSONException

/**
 * One community (Kanji Koohii) story for a kanji, as `tools/build_rtk_db.py` parsed it out of the
 * source cell: its own slot number, the author's handle and profile link, the date it was posted,
 * its score and the prose.
 *
 * [profileUrl] is null when the stored link is not a page on kanji.koohii.com, so nothing the app
 * offers to open can point anywhere else.
 */
data class RtkMnemonic(
    val index: Int,
    val author: String,
    val profileUrl: String?,
    val date: String,
    val score: Int,
    val body: String
)

/**
 * One Remembering the Kanji row, keyed by the character the reader tapped.
 *
 * [story] is the Heisig story and [primitive] the Heisig primitive comment; both are empty when the
 * dataset has none, and the UI hides those sections rather than inventing them. Emphasis markers
 * (`**bold**`, `_italic_`) are kept verbatim: the React Native renderer interprets them.
 */
data class RtkEntry(
    val character: String,
    val keyword: String,
    val story: String,
    val primitive: String,
    val mnemonics: List<RtkMnemonic>
)

/**
 * True when [url] is an `http(s)` page on `kanji.koohii.com` or one of its subdomains — the only
 * host the dataset attributes community stories to, and the only link the UI may offer to open.
 */
internal fun isAllowedProfileUrl(url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val rest = when {
        url.startsWith("https://") -> url.removePrefix("https://")
        url.startsWith("http://") -> url.removePrefix("http://")
        else -> return false
    }
    val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = if (hostEnd == -1) rest else rest.substring(0, hostEnd)
    return host == "kanji.koohii.com" || host.endsWith(".kanji.koohii.com")
}

/**
 * The `mnemonics` column: a JSON array of `{index, author, profileUrl, date, score, body}`.
 *
 * A row that is not JSON, or an entry missing its body, is skipped instead of failing the lookup —
 * a damaged story must never cost the reader the kanji it belongs to. Entries are ordered by the
 * slot number the story itself carries, which is what survives the source's occasional gap.
 */
internal fun decodeMnemonics(json: String): List<RtkMnemonic> {
    if (json.isEmpty()) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (e: JSONException) {
        return emptyList()
    }
    val mnemonics = ArrayList<RtkMnemonic>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val body = item.optString("body")
        if (body.isEmpty()) continue
        mnemonics.add(
            RtkMnemonic(
                index = item.optInt("index", index + 1),
                author = item.optString("author"),
                profileUrl = item.optString("profileUrl").takeIf { isAllowedProfileUrl(it) },
                date = item.optString("date"),
                score = item.optInt("score", 0),
                body = body
            )
        )
    }
    return mnemonics.sortedBy { it.index }
}

/** One row of the `rtk` table; kept internal so only [RtkRepository] decides what a lookup is. */
internal fun rtkEntry(
    character: String,
    keyword: String,
    story: String,
    primitive: String,
    mnemonicsJson: String
): RtkEntry = RtkEntry(character, keyword, story, primitive, decodeMnemonics(mnemonicsJson))
