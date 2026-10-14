/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiSettingsTest {

    private val card = AnkiCard(
        expression = "食べる",
        reading = "たべる",
        meaning = "to eat",
        sentence = "毎日<b>食べる</b>。"
    )

    private val mapped = AnkiSettings(
        modelId = 5L,
        modelFields = listOf("Expression", "Reading", "Meaning"),
        fieldSources = listOf(AnkiField.SENTENCE, AnkiField.EMPTY, AnkiField.EXPRESSION)
    )

    @Test
    fun defaultsAreTheNoteTypeOlderGatenCreated() {
        // A new Gaten writes exactly the note type the previous release did, so an installed one is
        // reused and every card already in the collection keeps rendering as it did.
        assertEquals("{{Expression}}", AnkiSettings.DEFAULT_FRONT)
        assertEquals(
            "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}<br><small>{{Sentence}}</small>",
            AnkiSettings.DEFAULT_BACK
        )
        assertEquals(
            """
            .card { font-family: sans-serif; font-size: 24px; text-align: center; color: #111; background: #fff; }
            .nightMode .card { color: #eee; background: #222; }
            small { font-size: 16px; color: #666; }
            .nightMode small { color: #aaa; }
            """.trimIndent(),
            AnkiSettings.DEFAULT_CSS
        )
        assertEquals(listOf("Expression", "Reading", "Meaning", "Sentence"), AnkiSettings.NOTE_FIELDS)
        assertEquals("Gaten", AnkiSettings().deckName)
        assertNull(AnkiSettings().validationError())
        assertTrue(AnkiSettings().isDefaultTemplates())
    }

    @Test
    fun builtInFieldsFollowTheCardsOwnOrder() {
        assertArrayEquals(
            arrayOf("食べる", "たべる", "to eat", "毎日<b>食べる</b>。"),
            AnkiSettings().fields(card)
        )
    }

    @Test
    fun mappedFieldsFollowTheMappingAndLeaveEmptiesBlank() {
        assertArrayEquals(
            arrayOf("毎日<b>食べる</b>。", "", "食べる"),
            mapped.fields(card)
        )
    }

    @Test
    fun duplicateKeyIsTheFirstMappedField() {
        assertEquals(card.sentence, duplicateKey(mapped.fields(card)))
        assertEquals(card.expression, duplicateKey(AnkiSettings().fields(card)))

        val sentenceLast = mapped.copy(fieldSources = listOf(AnkiField.EXPRESSION, AnkiField.EMPTY, AnkiField.SENTENCE))
        assertEquals(card.expression, duplicateKey(sentenceLast.fields(card)))
    }

    @Test
    fun aFieldValueCannotCarryAnkiDroidsFieldSeparator() {
        val hostile = AnkiCard(
            expression = "食べる",
            reading = "たべる",
            meaning = "to eat\u0000and drink",
            sentence = "毎日\u001f食べる"
        )
        assertArrayEquals(
            arrayOf("食べる", "たべる", "to eat and drink", "毎日 食べる"),
            AnkiSettings().fields(hostile)
        )
        // The duplicate key is serialized the same way as the note it is checked against.
        assertEquals("食べる", duplicateKey(AnkiSettings().fields(hostile)))
    }

    @Test
    fun tagsSplitOnWhitespaceWithoutBlanksOrRepeats() {
        assertEquals(setOf("n5", "verb", "jlpt"), AnkiSettings(tags = "  n5\tverb\njlpt ").tagSet())
        assertEquals(setOf("a", "b"), AnkiSettings(tags = "a a b").tagSet())
        assertEquals(emptySet<String>(), AnkiSettings(tags = "   ").tagSet())
    }

    @Test
    fun tagsAcceptWhitespaceSeparatorsButNoOtherControlCharacters() {
        assertNull(AnkiSettings(tags = "n5\tverb\njlpt").validationError())
        assertEquals(AnkiSettingsError.TAGS, AnkiSettings(tags = "n5\u0000verb").validationError())
        assertEquals(AnkiSettingsError.TAGS, AnkiSettings(tags = "n5\u001fverb").validationError())
        assertEquals(AnkiSettingsError.TAGS, AnkiSettings(tags = "a".repeat(1001)).validationError())
        assertNull(AnkiSettings(tags = "a".repeat(1000)).validationError())
    }

    @Test
    fun deckNameMustBeOneAnkiDroidCanCreate() {
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "   ").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "Gaten::").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "::Gaten").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "Gaten::::N5").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "Ga\u0007ten").validationError())
        assertEquals(AnkiSettingsError.DECK, AnkiSettings(deckName = "a".repeat(201)).validationError())
        assertNull(AnkiSettings(deckName = "Gaten::JLPT N5").validationError())
        assertNull(AnkiSettings(deckName = "a".repeat(200)).validationError())
    }

    @Test
    fun frontMustUseSupportedFields() {
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "   ").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{Foo}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{expression}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{type:Expression}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{cloze:Text}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{Expression").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{Expression}} }}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{Expression}}\u0000").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{Expression}}\u001f").validationError())
        // The question side is already on screen: repeating it inside itself is not a layout.
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{FrontSide}}").validationError())
    }

    @Test
    fun backMayRepeatTheFront() {
        assertNull(AnkiSettings(back = "{{FrontSide}}<hr id=answer>{{Reading}}").validationError())
        assertEquals(AnkiSettingsError.BACK, AnkiSettings(back = "{{Nope}}").validationError())
        assertEquals(AnkiSettingsError.BACK, AnkiSettings(back = "{{FrontSide}}\u001f").validationError())
    }

    @Test
    fun sectionsMustNestAndClose() {
        // What the customise screen tells the user it supports: a section per field, both senses.
        assertNull(
            AnkiSettings(
                front = "{{Expression}}<br>{{#Reading}}{{Reading}}{{/Reading}}",
                back = "{{FrontSide}}{{^Reading}}no reading{{/Reading}}"
            ).validationError()
        )
        assertNull(
            AnkiSettings(
                front = "{{#Reading}}{{^Meaning}}{{Reading}} {{Meaning}}{{/Meaning}}{{/Reading}}"
            ).validationError()
        )
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{#Reading}}{{Expression}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{/Reading}}{{Expression}}").validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "{{#Reading}}{{/Meaning}}").validationError())
        assertEquals(
            AnkiSettingsError.BACK,
            AnkiSettings(back = "{{FrontSide}}{{#Reading}}{{/Reading}}{{/Reading}}").validationError()
        )
    }

    @Test
    fun templatesHaveLengthLimits() {
        val longest = "{{Expression}}" + "a".repeat(32000 - "{{Expression}}".length)
        assertNull(AnkiSettings(front = longest).validationError())
        assertEquals(AnkiSettingsError.FRONT, AnkiSettings(front = "$longest ").validationError())
        assertNull(AnkiSettings(back = "{{FrontSide}}".padEnd(32000, 'a')).validationError())
        assertEquals(AnkiSettingsError.BACK, AnkiSettings(back = "{{FrontSide}}".padEnd(32001, 'a')).validationError())
    }

    @Test
    fun cssMayBeBlankButNotLongerThanATemplate() {
        assertNull(AnkiSettings(css = "").validationError())
        assertNull(AnkiSettings(css = "a".repeat(32000)).validationError())
        assertEquals(AnkiSettingsError.CSS, AnkiSettings(css = "a".repeat(32001)).validationError())
        assertEquals(AnkiSettingsError.CSS, AnkiSettings(css = ".card\u0000{}").validationError())
        assertEquals(AnkiSettingsError.CSS, AnkiSettings(css = ".card\u001f{}").validationError())
    }

    @Test
    fun chosenNoteTypeNeedsOneSourcePerFieldWithTheFirstFilled() {
        assertNull(mapped.validationError())
        assertNull(mapped.copy(fieldSources = listOf(AnkiField.EXPRESSION, AnkiField.EMPTY, AnkiField.MEANING)).validationError())
        // No Expression at all, and the first field AnkiDroid checks duplicates on left empty.
        assertEquals(
            AnkiSettingsError.MAPPING,
            mapped.copy(fieldSources = listOf(AnkiField.READING, AnkiField.EMPTY, AnkiField.MEANING)).validationError()
        )
        assertEquals(
            AnkiSettingsError.MAPPING,
            mapped.copy(fieldSources = listOf(AnkiField.EMPTY, AnkiField.EXPRESSION, AnkiField.MEANING)).validationError()
        )
        assertEquals(
            AnkiSettingsError.MAPPING,
            mapped.copy(fieldSources = listOf(AnkiField.EXPRESSION, AnkiField.READING)).validationError()
        )
        assertEquals(AnkiSettingsError.MAPPING, mapped.copy(modelFields = emptyList(), fieldSources = emptyList()).validationError())
    }

    @Test
    fun chosenNoteTypeKeepsItsOwnTemplates() {
        // Its templates are AnkiDroid's, so Gaten's stored ones cannot make the settings unusable.
        assertNull(mapped.copy(front = "", back = "", css = "").validationError())
    }

    @Test
    fun aBrokenSavedMappingIsReportedInsteadOfFallingBackToGatenCards() {
        // What a hand-edited or half-written settings file looks like: a note type, no mapping.
        val broken = AnkiSettings(modelId = 5L, modelFields = listOf("Expression", "Reading"))
        assertEquals(5L, broken.modelId)
        assertEquals(AnkiSettingsError.MAPPING, broken.validationError())
    }

    @Test
    fun templateKeyCoversTheTemplatesAlone() {
        val key = AnkiSettings().templateKey()
        assertEquals(key, AnkiSettings(deckName = "JLPT").templateKey())
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })

        assertNotEquals(key, AnkiSettings().copy(front = "{{Expression}}<br>").templateKey())
        assertNotEquals(key, AnkiSettings().copy(back = "{{FrontSide}}{{Reading}}").templateKey())
        assertNotEquals(key, AnkiSettings().copy(css = ".card { text-align: left; }").templateKey())

        // The deck, the tags and the mapping a note type is filled with never enter the key.
        assertEquals(key, AnkiSettings(deckName = "JLPT", tags = "n5").templateKey())
        assertEquals(key, mapped.templateKey())
    }

    @Test
    fun onlyTheUntouchedTemplatesAreTheDefaultLayout() {
        assertTrue(AnkiSettings().isDefaultTemplates())
        assertTrue(AnkiSettings(deckName = "JLPT", tags = "n5").isDefaultTemplates())
        assertFalse(AnkiSettings().copy(css = "").isDefaultTemplates())
        assertFalse(AnkiSettings().copy(front = "\n{{Expression}}").isDefaultTemplates())
    }
}
