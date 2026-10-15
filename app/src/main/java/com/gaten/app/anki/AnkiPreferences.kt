/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import android.content.Context
import java.security.MessageDigest
import java.util.LinkedHashSet

/**
 * AnkiDroid joins the fields of a note with this character (`FLDS`, `FIELD_NAMES`), so no field
 * value, field name or persisted field list can carry it. ASCII unit separator.
 */
internal const val ANKI_FIELD_SEPARATOR = '\u001f'

/** The Gaten value that fills one field of an Anki note type. */
enum class AnkiField {
    EXPRESSION,
    READING,
    MEANING,
    SENTENCE,

    /** Leave the field empty on every note Gaten adds. */
    EMPTY
}

/** Why [AnkiSettings.validationError] refused a configuration; the customise screen shows one message per value. */
enum class AnkiSettingsError { DECK, TAGS, FRONT, BACK, CSS, MAPPING }

/**
 * Everything "Add to Anki" needs beyond the word itself: where a note goes, how it is tagged, and
 * what its fields hold.
 *
 * Two modes, told apart by [modelId]:
 *
 * - **Gaten's own note type** ([modelId] null): the card goes through [front], [back] and [css], and
 *   AnkiDroid creates a note type carrying them. Its name follows the templates
 *   ([AnkiExporter]'s `managedModelName`), so editing a layout allocates a new note type instead of
 *   rewriting templates that the cards already in the collection were built from.
 * - **A note type the user picked** ([modelId] set): it keeps its own templates, and every one of
 *   its fields is filled from the [AnkiField] mapped to it in [fieldSources], one entry per name in
 *   [modelFields] — the field names AnkiDroid reported for that note type, in Anki's order. The
 *   first field is both the field AnkiDroid checks for duplicates and therefore the field that has
 *   to be filled.
 */
data class AnkiSettings(
    val deckName: String = DEFAULT_DECK_NAME,
    val tags: String = "",
    val modelId: Long? = null,
    val modelFields: List<String> = emptyList(),
    val fieldSources: List<AnkiField> = emptyList(),
    val front: String = DEFAULT_FRONT,
    val back: String = DEFAULT_BACK,
    val css: String = DEFAULT_CSS
) {
    companion object {
        /** Deck name — and note type name, while the layout is untouched — Gaten creates on first use. */
        const val DEFAULT_DECK_NAME = "Gaten"

        /** Front of Gaten's note type: the word alone. */
        const val DEFAULT_FRONT = "{{Expression}}"

        /** Back: reading, meaning and the sentence, under the front's own rendering. */
        const val DEFAULT_BACK =
            "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}<br><small>{{Sentence}}</small>"

        /** Centred and readable; Anki puts the `.nightMode` class on the card in dark mode. */
        val DEFAULT_CSS = """
            .card { font-family: sans-serif; font-size: 24px; text-align: center; color: #111; background: #fff; }
            .nightMode .card { color: #eee; background: #222; }
            small { font-size: 16px; color: #666; }
            .nightMode small { color: #aaa; }
        """.trimIndent()

        /** The fields Gaten's own note type is created with, in Anki's order. */
        val NOTE_FIELDS = listOf("Expression", "Reading", "Meaning", "Sentence")

        /** Limits the customise screen tells the user about; Anki itself is far more permissive. */
        private const val MAX_DECK_NAME = 200
        private const val MAX_TAGS = 1000
        private const val MAX_TEMPLATE = 32000

        /** Anki's name for the question side of a card, usable on the answer side only. */
        private const val FRONT_SIDE = "FrontSide"

        /** One `{{...}}` substitution body; braces Anki uses otherwise are unsupported syntax. */
        private val MARKER = Regex("\\{\\{([^{}]*)\\}\\}")

        /** Tags are whitespace separated, as in AnkiDroid's own editor. */
        private val TAG_SEPARATOR = Regex("\\s+")

        /**
         * The whitespace a tag list may hold. Java counts U+001C–U+001F as whitespace while its
         * `\s` — and AnkiDroid's own tag splitting — do not, so the separators are listed explicitly
         * and every other control character, including NUL and the field separator, is refused.
         */
        private const val TAG_SEPARATORS = " \t\n\u000B\u000C\r"
    }

    /**
     * What makes these settings unusable for an add, or null when they are ready, in
     * [AnkiSettingsError] order. Templates are only checked while Gaten's own note type is used: a
     * chosen note type renders its own templates, which Gaten never touches.
     */
    fun validationError(): AnkiSettingsError? = when {
        !validDeckName(deckName) -> AnkiSettingsError.DECK
        !validTags(tags) -> AnkiSettingsError.TAGS
        modelId != null -> if (validMapping()) null else AnkiSettingsError.MAPPING
        !validTemplate(front, frontSideAllowed = false) -> AnkiSettingsError.FRONT
        !validTemplate(back, frontSideAllowed = true) -> AnkiSettingsError.BACK
        !validCss(css) -> AnkiSettingsError.CSS
        else -> null
    }

    /** The tags AnkiDroid gets: whitespace separated, without blanks and without repeats. */
    fun tagSet(): Set<String> = tags.split(TAG_SEPARATOR).filterTo(LinkedHashSet()) { it.isNotEmpty() }

    /**
     * [card]'s values in the order the target note type expects them: Gaten's four fields in Anki's
     * order, or one value per mapped field of a chosen note type.
     *
     * The duplicate check and the added note both serialize this same array, so both see the same
     * values, and the character AnkiDroid joins note fields with cannot survive inside a value —
     * a word's OCR sentence may contain one.
     */
    fun fields(card: AnkiCard): Array<String> {
        val values = if (modelId == null) {
            listOf(card.expression, card.reading, card.meaning, card.sentence)
        } else {
            fieldSources.map { source -> fieldValue(source, card) }
        }
        return Array(values.size) { sanitizeField(values[it]) }
    }

    /**
     * A SHA-256 of the templates alone — deck, tags and mapping stay out of it — so the note type
     * carrying this exact layout can be cached and found again, and so two layouts never share a
     * cached id.
     */
    fun templateKey(): String = templateFingerprint(front, back, css)

    /** True when the templates are Gaten's defaults, the layout older installs cached under one fixed id. */
    internal fun isDefaultTemplates(): Boolean =
        front == DEFAULT_FRONT && back == DEFAULT_BACK && css == DEFAULT_CSS

    private fun fieldValue(source: AnkiField, card: AnkiCard): String = when (source) {
        AnkiField.EXPRESSION -> card.expression
        AnkiField.READING -> card.reading
        AnkiField.MEANING -> card.meaning
        AnkiField.SENTENCE -> card.sentence
        AnkiField.EMPTY -> ""
    }

    private fun validDeckName(deckName: String): Boolean {
        val name = deckName.trim()
        if (name.isEmpty() || name.length > MAX_DECK_NAME) return false
        if (name.any { Character.isISOControl(it) }) return false
        // "Parent::Child" nesting: an empty section is not a deck AnkiDroid would create.
        return name.split("::").none { it.isBlank() }
    }

    /** Tabs and newlines may separate tags; NUL, the field separator and every other control are refused. */
    private fun validTags(tags: String): Boolean =
        tags.length <= MAX_TAGS && tags.none { Character.isISOControl(it) && it !in TAG_SEPARATORS }

    private fun validTemplate(template: String, frontSideAllowed: Boolean): Boolean {
        if (template.isBlank() || template.length > MAX_TEMPLATE) return false
        if (template.contains('\u0000') || template.contains(ANKI_FIELD_SEPARATOR)) return false
        // What is left after the markers: an unclosed `{{`, a stray `}}`, `{{=<% %>=}}`.
        val leftovers = MARKER.replace(template, "")
        if (leftovers.contains("{{") || leftovers.contains("}}")) return false
        return sectionsAreBalanced(template, frontSideAllowed)
    }

    /**
     * Walks the `{{...}}` bodies in order, one `{{#Field}}` or `{{^Field}}` per `{{/Field}}`, nested
     * and closed in order: an unbalanced section renders nothing in AnkiDroid, so it is refused while
     * the template is still being edited rather than becoming the note type cards are built from.
     */
    private fun sectionsAreBalanced(template: String, frontSideAllowed: Boolean): Boolean {
        val open = ArrayDeque<String>()
        for (match in MARKER.findAll(template)) {
            val body = match.groupValues[1].trim()
            val section = body.startsWith("#") || body.startsWith("^") || body.startsWith("/")
            val name = if (section) body.drop(1).trim() else body
            if (name == FRONT_SIDE) {
                if (section || !frontSideAllowed) return false
                continue
            }
            if (name !in NOTE_FIELDS) return false
            when {
                body.startsWith("/") -> if (open.removeLastOrNull() != name) return false
                section -> open.addLast(name)
            }
        }
        return open.isEmpty()
    }

    /** An existing note type needs one source per field it reports, the first filled, Expression mapped somewhere. */
    private fun validMapping(): Boolean =
        modelFields.isNotEmpty() &&
            fieldSources.size == modelFields.size &&
            fieldSources.first() != AnkiField.EMPTY &&
            AnkiField.EXPRESSION in fieldSources &&
            modelFields.none { it.isBlank() }

    /** CSS may be blank; it cannot carry the two characters a field-carrying string never may. */
    private fun validCss(css: String): Boolean =
        css.length <= MAX_TEMPLATE &&
            !css.contains('\u0000') &&
            !css.contains(ANKI_FIELD_SEPARATOR)
}

/**
 * [value] without the characters AnkiDroid's note serialization cannot carry inside a field: the
 * field separator itself, and NUL. Replaced by spaces rather than dropped, so the words around them
 * stay apart. The common case allocates nothing.
 */
private fun sanitizeField(value: String): String =
    if (value.none { it == ANKI_FIELD_SEPARATOR || it == '\u0000' }) {
        value
    } else {
        value.replace(ANKI_FIELD_SEPARATOR, ' ').replace('\u0000', ' ')
    }

/**
 * Hashes all three templates for the note type name and cache key. The separator cannot appear
 * in a validated template, so different divisions of the same text produce different hashes.
 */
private fun templateFingerprint(front: String, back: String, css: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val parts = "$front$ANKI_FIELD_SEPARATOR$back$ANKI_FIELD_SEPARATOR$css"
    return digest.digest(parts.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Reads and writes the Anki half of the shared `gaten_settings` file (`ocr/OcrPreferences` uses the
 * same one).
 *
 * Every value has its own key, so adding one later cannot drop the others, and a saved
 * configuration that no longer parses is loaded as it is: [AnkiSettings.validationError] then
 * reports the broken mapping, instead of the settings quietly falling back to Gaten's own note type
 * and sending the user's words to another deck.
 *
 * [save] normalises the deck name and the tags (trimmed, whitespace separated, without repeats).
 * Everything else is stored as given: judging it is [AnkiSettings.validationError]'s job.
 */
object AnkiPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_DECK_NAME = "anki_deck_name"
    private const val KEY_TAGS = "anki_tags"
    private const val KEY_MODEL_ID = "anki_existing_model_id"
    private const val KEY_MODEL_FIELDS = "anki_model_fields"
    private const val KEY_FIELD_SOURCES = "anki_field_sources"
    private const val KEY_FRONT = "anki_front"
    private const val KEY_BACK = "anki_back"
    private const val KEY_CSS = "anki_css"

    /** Sentinel for "no note type chosen": AnkiDroid's model ids are positive. */
    private const val NO_MODEL = -1L

    fun load(context: Context): AnkiSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AnkiSettings(
            deckName = prefs.getString(KEY_DECK_NAME, null) ?: AnkiSettings.DEFAULT_DECK_NAME,
            tags = prefs.getString(KEY_TAGS, null).orEmpty(),
            modelId = prefs.getLong(KEY_MODEL_ID, NO_MODEL).takeIf { it != NO_MODEL },
            modelFields = splitFieldNames(prefs.getString(KEY_MODEL_FIELDS, null).orEmpty()),
            fieldSources = splitFieldSources(prefs.getString(KEY_FIELD_SOURCES, null).orEmpty()),
            front = prefs.getString(KEY_FRONT, null) ?: AnkiSettings.DEFAULT_FRONT,
            back = prefs.getString(KEY_BACK, null) ?: AnkiSettings.DEFAULT_BACK,
            css = prefs.getString(KEY_CSS, null) ?: AnkiSettings.DEFAULT_CSS
        )
    }

    fun save(context: Context, settings: AnkiSettings) {
        val modelId = settings.modelId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_DECK_NAME, settings.deckName.trim())
            putString(KEY_TAGS, settings.tagSet().joinToString(" "))
            putString(KEY_FRONT, settings.front)
            putString(KEY_BACK, settings.back)
            putString(KEY_CSS, settings.css)
            if (modelId == null) {
                remove(KEY_MODEL_ID)
                remove(KEY_MODEL_FIELDS)
                remove(KEY_FIELD_SOURCES)
            } else {
                putLong(KEY_MODEL_ID, modelId)
                putString(KEY_MODEL_FIELDS, settings.modelFields.joinToString(ANKI_FIELD_SEPARATOR.toString()))
                putString(KEY_FIELD_SOURCES, settings.fieldSources.joinToString(",") { it.name })
            }
        }.apply()
    }
}

/** Field names as persisted, with the separator AnkiDroid uses and a field name cannot contain. */
private fun splitFieldNames(names: String): List<String> =
    if (names.isEmpty()) emptyList() else names.split(ANKI_FIELD_SEPARATOR)

/**
 * The mapping as persisted: [AnkiField] names. A name this build does not know — a downgrade, or a
 * hand-edited file — drops out and leaves the mapping shorter than the field list, which
 * [AnkiSettings.validationError] reports rather than papering over.
 */
private fun splitFieldSources(sources: String): List<AnkiField> =
    sources.split(',').mapNotNull { name -> AnkiField.entries.firstOrNull { it.name == name } }
