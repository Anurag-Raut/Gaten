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

/** One assignment of a `GET /assignments` collection: which subject is due, and when. */
internal data class DecodedAssignment(
    /** The assignment's own id, the tie-breaker when two subjects became available at once. */
    val id: Int,
    val subjectId: Int,
    /** When the assignment came up, in epoch milliseconds; 0 when WaniKani sent an unreadable one. */
    val availableAt: Long
)

/**
 * Every assignment in a `GET /assignments` collection, hidden ones dropped. Null when the body is not
 * a collection at all - the same "try again" a malformed subject body means, never an empty queue.
 *
 * This is the count-only reads' fuller sibling (see `WaniKaniStats`): the same collection, but here
 * the ids are what the queue needs, and WaniKani's own ordering is left alone so the caller can sort
 * the assignments the way it shows them.
 */
internal fun decodeAssignmentCollection(text: String): List<DecodedAssignment>? {
    val items = jsonObject(text)?.optJSONArray("data") ?: return null
    val assignments = ArrayList<DecodedAssignment>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val data = item.optJSONObject("data") ?: continue
        if (data.optBoolean("hidden", false)) continue
        val id = item.optInt("id", 0)
        val subjectId = data.optInt("subject_id", 0)
        if (id <= 0 || subjectId <= 0) continue
        assignments.add(
            DecodedAssignment(id, subjectId, instantMillis(data.optionalText("available_at")) ?: 0L)
        )
    }
    return assignments
}

/**
 * Every subject in a `GET /subjects?ids=…` collection as a review item; items of a kind the review
 * does not quiz are dropped, as are subjects that carry nothing to be quizzed on. Null when the body
 * is not a collection at all.
 */
internal fun decodeReviewSubjectCollection(text: String): List<WaniKaniReviewItem>? {
    val items = jsonObject(text)?.optJSONArray("data") ?: return null
    val subjects = ArrayList<WaniKaniReviewItem>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        decodeReviewSubject(item)?.let { subjects.add(it) }
    }
    return subjects
}

/** One review item in a subject resource, or null when it is not a kind the review quizzes. */
private fun decodeReviewSubject(resource: JSONObject): WaniKaniReviewItem? {
    val id = resource.optInt("id", 0)
    if (id <= 0) return null
    val type = resource.optionalText("object") ?: return null
    if (type !in REVIEW_TYPES) return null
    val data = resource.optJSONObject("data") ?: return null

    // A subject whose every meaning is rejected and which has no whitelisted auxiliary meaning still
    // has its slug, which is WaniKani's own short name for it: the screen always has an answer to
    // judge against, exactly as the reference decode falls back for a subject's primary meaning.
    val meanings = decodeReviewMeanings(data)
    val accepted = meanings.ifEmpty { data.optionalText("slug")?.let { listOf(it) } ?: return null }
    val readings = decodeReviewReadings(data.optJSONArray("readings"))
    return WaniKaniReviewItem(
        subjectId = id,
        type = type,
        characters = data.optionalText("characters"),
        meanings = accepted,
        primaryMeaning = accepted.first(),
        readings = readings,
        primaryReading = readings.firstOrNull(),
        level = data.optInt("level", 0),
        meaningMnemonic = plainWaniKaniText(data.optionalText("meaning_mnemonic") ?: ""),
        readingMnemonic = plainWaniKaniText(data.optionalText("reading_mnemonic") ?: ""),
        contextSentences = decodeContextSentences(data.optJSONArray("context_sentences"))
    )
}

/**
 * The accepted meanings of one subject, the primary one first, the whitelist auxiliary meanings after
 * them, without repeats.
 *
 * The primary meaning is placed first rather than left where the array holds it, so a `meanings[]`
 * entry marked non-primary cannot be offered as the one the subject is taught as.
 */
private fun decodeReviewMeanings(data: JSONObject): List<String> {
    val meanings = data.optJSONArray("meanings")
    val primary = ArrayList<String>(1)
    val rest = ArrayList<String>(meanings?.length() ?: 0)
    if (meanings != null) {
        for (index in 0 until meanings.length()) {
            val entry = meanings.optJSONObject(index) ?: continue
            if (!entry.optBoolean("accepted_answer", false)) continue
            val meaning = entry.optionalText("meaning") ?: continue
            if (entry.optBoolean("primary", false) && primary.isEmpty()) primary.add(meaning) else rest.add(meaning)
        }
    }
    val auxiliary = data.optJSONArray("auxiliary_meanings")
    if (auxiliary != null) {
        for (index in 0 until auxiliary.length()) {
            val entry = auxiliary.optJSONObject(index) ?: continue
            // Only the whitelist: a blacklist auxiliary meaning is deliberately wrong, and accepting
            // it here would mark a wrong answer right.
            if (entry.optionalText("type") != "whitelist") continue
            entry.optionalText("meaning")?.let { rest.add(it) }
        }
    }
    return (primary + rest).distinct()
}

/**
 * The accepted readings of one subject, folded to hiragana, the primary one first, without repeats.
 *
 * An empty list means the subject is not quizzed on its reading at all - a radical or a kana
 * vocabulary has no `readings` array, and neither gets one built here - so the UI can tell "no
 * reading to ask for" from "a reading the reader has not answered yet".
 */
private fun decodeReviewReadings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val primary = ArrayList<String>(1)
    val rest = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        if (!entry.optBoolean("accepted_answer", false)) continue
        val reading = entry.optionalText("reading") ?: continue
        val folded = readingAnswerHiragana(reading)
        if (folded.isEmpty()) continue
        if (entry.optBoolean("primary", false)) primary.add(folded) else rest.add(folded)
    }
    return (primary + rest).distinct()
}

/**
 * Every subject in a `GET /subjects?ids=…` collection as a lesson item, for all four kinds WaniKani
 * teaches, paired with its assignment id through [assignmentIds] (subject id to assignment id).
 * Subjects with nothing to quiz on, and subjects the map does not name, are dropped; null is returned
 * when the body is not a collection at all - a malformed answer and an empty one mean different
 * things to the caller.
 */
internal fun decodeLessonSubjectCollection(
    text: String,
    assignmentIds: Map<Int, Int>
): List<WaniKaniLessonItem>? {
    val items = jsonObject(text)?.optJSONArray("data") ?: return null
    val subjects = ArrayList<WaniKaniLessonItem>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        // The subjects endpoint carries no assignment id, so a subject without one in the map is not
        // part of this queue - the assignments are the queue, and the subjects are only its content.
        val assignmentId = assignmentIds[item.optInt("id", 0)] ?: continue
        decodeLessonSubject(item, assignmentId)?.let { subjects.add(it) }
    }
    return subjects
}

/**
 * One lesson item in a subject resource, or null when it is not a kind WaniKani teaches. The
 * assignment id is paired in by [decodeLessonSubjectCollection], which is the side that read the
 * assignments: the subject endpoint does not carry it.
 */
internal fun decodeLessonSubject(resource: JSONObject, assignmentId: Int): WaniKaniLessonItem? {
    val id = resource.optInt("id", 0)
    if (id <= 0) return null
    val type = resource.optionalText("object") ?: return null
    if (type !in REVIEW_TYPES) return null
    val data = resource.optJSONObject("data") ?: return null

    val meanings = decodeReviewMeanings(data)
    val accepted = meanings.ifEmpty { data.optionalText("slug")?.let { listOf(it) } ?: return null }
    val readings = decodeReviewReadings(data.optJSONArray("readings"))
    return WaniKaniLessonItem(
        assignmentId = assignmentId,
        subjectId = id,
        type = type,
        characters = data.optionalText("characters"),
        level = data.optInt("level", 0),
        lessonPosition = data.optInt("lesson_position", 0),
        meanings = accepted,
        primaryMeaning = accepted.first(),
        readings = readings,
        primaryReading = readings.firstOrNull(),
        readingMnemonic = plainWaniKaniText(data.optionalText("reading_mnemonic") ?: ""),
        meaningMnemonic = plainWaniKaniText(data.optionalText("meaning_mnemonic") ?: ""),
        components = decodeIds(data.optJSONArray("component_subject_ids"))
            .map { WaniKaniComponent(it, null, null) },
        contextSentences = decodeContextSentences(data.optJSONArray("context_sentences")),
        documentUrl = decodeDocumentUrl(data.optionalText("document_url"))
    )
}

/** The `ja`/`en` pairs of a subject's `context_sentences`, dropping any entry missing either half. */
private fun decodeContextSentences(array: JSONArray?): List<WaniKaniContextSentence> {
    if (array == null) return emptyList()
    val sentences = ArrayList<WaniKaniContextSentence>(array.length())
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        val japanese = entry.optionalText("ja") ?: continue
        val english = entry.optionalText("en") ?: continue
        sentences.add(WaniKaniContextSentence(japanese, english))
    }
    return sentences
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

/** The four kinds WaniKani quizzes; any other `object` is not a review item. */
private val REVIEW_TYPES = setOf("radical", "kanji", "vocabulary", "kana_vocabulary")

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
