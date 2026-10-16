/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One subject as decoded from a resource response, together with that response re-serialised so the
 * client can cache it verbatim. [subject] is the public shape, except that its `svg` is always null:
 * only the client knows the image cache.
 */
internal data class DecodedSubject(
    val id: Int,
    val level: Int,
    /** The SVG url of `character_images`, or null when the subject has no image. */
    val imageUrl: String?,
    val subject: WaniKaniSubject,
    /** The resource as JSON, to be cached verbatim and decoded again on the next read. */
    val json: String
)

/** The subject in one `GET /subjects/<id>` response, or null when it is not a kanji or a radical. */
internal fun decodeSubjectResource(text: String): DecodedSubject? {
    val resource = jsonObject(text) ?: return null
    return decodeSubject(resource)
}

/** What one `GET /subjects/<id>` body turned out to be. */
internal sealed interface SubjectBody {

    data class Subject(val decoded: DecodedSubject) : SubjectBody

    /** Readable, but a kind of subject Gaten does not show: there is nothing for this id. */
    data object NotASubject : SubjectBody

    /** Not JSON at all - a captive portal, an error page. A failed answer, never a missing subject. */
    data object Malformed : SubjectBody
}

/**
 * The by-id body, with the one distinction a single resource needs and [decodeSubjectResource] cannot
 * express: a body that is not JSON means "try again", while a body of another subject kind means
 * "WaniKani has nothing to show for this id".
 */
internal fun decodeSubjectBody(text: String): SubjectBody {
    val resource = jsonObject(text) ?: return SubjectBody.Malformed
    val decoded = decodeSubject(resource) ?: return SubjectBody.NotASubject
    return SubjectBody.Subject(decoded)
}

/**
 * Every subject in a `GET /subjects` collection response; items of other kinds are dropped.
 *
 * Null when the body is not a collection at all: a malformed answer and an empty one mean different
 * things to the caller - "try again" against "WaniKani has no such kanji" - and must not be confused.
 */
internal fun decodeSubjectCollection(text: String): List<DecodedSubject>? {
    val items = jsonObject(text)?.optJSONArray("data") ?: return null
    val subjects = ArrayList<DecodedSubject>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        decodeSubject(item)?.let { subjects.add(it) }
    }
    return subjects
}

/**
 * The subject's page on wanikani.com, or null when [url] is not one - see
 * [WaniKaniSubject.documentUrl]. Applied on every decode, so a cached value is re-checked on the way
 * out and can never point the UI anywhere the API would not have.
 */
internal fun decodeDocumentUrl(url: String?): String? = if (WaniKaniPolicy.isAllowedDocument(url)) url else null

/**
 * The text at [name], or null when the key is missing, JSON null, or empty.
 *
 * `optString` alone is not enough: AOSP's org.json answers a JSON null with the literal string
 * "null" (only the JVM implementation returns the fallback), so a radical's absent `characters` or a
 * free account's absent `period_ends_at` would otherwise read as text that is not there.
 */
private fun JSONObject.optionalText(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * The content entitlement in a `GET /user` response. Only `subscription.max_level_granted`, `.type`
 * and `.period_ends_at` are read: the account's level, username, preferences and progress are not
 * even looked at, let alone kept.
 *
 * A recurring subscription whose `period_ends_at` is missing or unparseable is read as already
 * ended, so a body we cannot make sense of can never grant more than a free account.
 */
internal fun decodeEntitlement(text: String): WaniKaniEntitlement? {
    val resource = jsonObject(text) ?: return null
    if (resource.optionalText("object") != "user") return null
    val subscription = resource.optJSONObject("data")?.optJSONObject("subscription") ?: return null
    val type = when (subscription.optionalText("type")?.lowercase()) {
        "free" -> WaniKaniEntitlement.Type.FREE
        "recurring" -> WaniKaniEntitlement.Type.RECURRING
        "lifetime" -> WaniKaniEntitlement.Type.LIFETIME
        else -> WaniKaniEntitlement.Type.UNKNOWN
    }
    val periodEndsAt = if (type == WaniKaniEntitlement.Type.RECURRING) {
        instantMillis(subscription.optionalText("period_ends_at")) ?: 0L
    } else {
        null
    }
    return WaniKaniEntitlement(
        maxLevelGranted = subscription.optInt("max_level_granted", WaniKaniEntitlement.FREE_LEVEL),
        type = type,
        periodEndsAt = periodEndsAt
    )
}

/**
 * The entitlement as [WaniKaniEntitlement.toJson] wrote it, or null when the cached file is not
 * readable as one - which is treated as "no entitlement", never as a grant.
 */
internal fun decodeCachedEntitlement(text: String): WaniKaniEntitlement? {
    val cached = jsonObject(text) ?: return null
    val granted = cached.optInt("max_level_granted", WaniKaniEntitlement.FREE_LEVEL)
    val type = when (cached.optionalText("type")) {
        "free" -> WaniKaniEntitlement.Type.FREE
        "recurring" -> WaniKaniEntitlement.Type.RECURRING
        "lifetime" -> WaniKaniEntitlement.Type.LIFETIME
        else -> WaniKaniEntitlement.Type.UNKNOWN
    }
    val periodEndsAt = if (cached.isNull("period_ends_at")) null else cached.optLong("period_ends_at", 0L)
    return WaniKaniEntitlement(granted, type, periodEndsAt)
}

/**
 * WaniKani's mnemonics are decorated with its own markup (`<radical>`, `<kanji>`, `<reading>`, ...)
 * and HTML entities, and they are remote text on its way into the UI: every tag is removed -
 * leaving the words it wrapped - and the entities are decoded, so what is left is plain text with
 * no markup of any kind to inject into a renderer.
 */
internal fun plainWaniKaniText(text: String): String {
    if (text.isEmpty()) return text
    val withoutTags = if ('<' in text) MARKUP.replace(text, "") else text
    val plain = if ('&' in withoutTags) ENTITY.replace(withoutTags) { decodeEntity(it.groupValues[1]) } else withoutTags
    return plain.trim()
}

private fun jsonObject(text: String): JSONObject? = try {
    JSONObject(text)
} catch (e: JSONException) {
    null
}

/** The public subject in one resource object, or null when it is not a kanji or a radical. */
private fun decodeSubject(resource: JSONObject): DecodedSubject? {
    val id = resource.optInt("id", 0)
    if (id <= 0) return null
    val data = resource.optJSONObject("data") ?: return null
    val kind = resource.optionalText("object")
    if (kind != "kanji" && kind != "radical") return null

    val meanings = decodeMeanings(data.optJSONArray("meanings"))
    val characters = data.optionalText("characters")
    // A subject with no meanings at all is not expected, and a radical's slug is its meaning
    // downcased: it is the last thing to fall back on so a chip is never blank.
    val primaryMeaning = meanings.firstOrNull() ?: data.optionalText("slug") ?: return null

    val level = data.optInt("level", 0)
    val documentUrl = decodeDocumentUrl(data.optionalText("document_url"))
    val meaningMnemonic = plainWaniKaniText(data.optionalText("meaning_mnemonic") ?: "")
    val subject = if (kind == "kanji") {
        val character = characters ?: return null
        WaniKaniSubject.Kanji(
            id = id,
            character = character,
            level = level,
            onyomi = decodeReadings(data.optJSONArray("readings"), "onyomi"),
            kunyomi = decodeReadings(data.optJSONArray("readings"), "kunyomi"),
            // Only the ids: the client fills the character, name and image in from the cache, so a
            // subject can be shown while its components are still unknown.
            components = decodeIds(data.optJSONArray("component_subject_ids"))
                .map { WaniKaniComponent(it, null, null) },
            meanings = meanings,
            primaryMeaning = primaryMeaning,
            meaningMnemonic = meaningMnemonic,
            readingMnemonic = plainWaniKaniText(data.optionalText("reading_mnemonic") ?: ""),
            documentUrl = documentUrl
        )
    } else {
        WaniKaniSubject.Radical(
            id = id,
            character = characters,
            level = level,
            meanings = meanings,
            primaryMeaning = primaryMeaning,
            meaningMnemonic = meaningMnemonic,
            svg = null,
            documentUrl = documentUrl
        )
    }
    return DecodedSubject(id, level, decodeImageUrl(data.optJSONArray("character_images")), subject, resource.toString())
}

/** The meanings, the primary one first, without repeats. */
private fun decodeMeanings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val primary = ArrayList<String>(1)
    val rest = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        val meaning = entry.optionalText("meaning") ?: continue
        if (entry.optBoolean("primary", false) && primary.isEmpty()) primary.add(meaning) else rest.add(meaning)
    }
    return (primary + rest).distinct()
}

/** The readings of [type] (`onyomi`, `kunyomi`), the primary one first, without repeats. */
private fun decodeReadings(array: JSONArray?, type: String): List<String> {
    if (array == null) return emptyList()
    val primary = ArrayList<String>(1)
    val rest = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        if (entry.optionalText("type") != type) continue
        val reading = entry.optionalText("reading") ?: continue
        if (entry.optBoolean("primary", false)) primary.add(reading) else rest.add(reading)
    }
    return (primary + rest).distinct()
}

/** The ids in a subject-id array, dropping anything that is not a positive number. */
private fun decodeIds(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    val ids = ArrayList<Int>(array.length())
    for (index in 0 until array.length()) {
        val id = array.optInt(index, 0)
        if (id > 0) ids.add(id)
    }
    return ids
}

/**
 * The url of the subject's SVG image. The API only ever delivers `image/svg+xml`, and an image-only
 * radical has no character at all, so this is the only way such a radical can be drawn.
 */
private fun decodeImageUrl(array: JSONArray?): String? {
    if (array == null) return null
    for (index in 0 until array.length()) {
        val image = array.optJSONObject(index) ?: continue
        if (image.optionalText("content_type") != "image/svg+xml") continue
        val url = image.optionalText("url")
        if (url != null) return url
    }
    return null
}

private fun instantMillis(text: String?): Long? {
    if (text.isNullOrEmpty()) return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    } catch (e: ArithmeticException) {
        null
    }
}

private val MARKUP = Regex("<[^>]*>")

private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

/** The entities WaniKani's mnemonics use. Anything else is left as it was written. */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "mdash" to "\u2014",
    "ndash" to "\u2013",
    "hellip" to "\u2026",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "ldquo" to "\u201c",
    "rdquo" to "\u201d"
)

private fun decodeEntity(body: String): String = when {
    body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2), 16)
    body.startsWith("#") -> codePoint(body.substring(1), 10)
    else -> NAMED_ENTITIES[body]
} ?: "&$body;"

private fun codePoint(digits: String, radix: Int): String? = try {
    val value = digits.toInt(radix)
    if (value in 1..0x10FFFF) String(Character.toChars(value)) else null
} catch (e: NumberFormatException) {
    null
}
