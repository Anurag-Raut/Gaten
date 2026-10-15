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
        meaning = "to eat"
    )

    private val mapped = AnkiSettings(
        modelId = 5L,
        modelFields = listOf("Expression", "Reading", "Meaning"),
        fieldSources = listOf(AnkiField.MEANING, AnkiField.EMPTY, AnkiField.EXPRESSION)
    )

    @Test
    fun defaultsAreTheNoteTypeGatenCreates() {
        // The layout a fresh install writes: the word alone on the front, its reading and meaning on
        // the back, and no sentence field to leave empty.
        assertEquals("{{Expression}}", AnkiSettings.DEFAULT_FRONT)
        assertEquals(
            "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}",
            AnkiSettings.DEFAULT_BACK
        )
        assertEquals(
            """
            .card { font-family: sans-serif; font-size: 24px; text-align: center; }
            small { font-size: 16px; }
            """.trimIndent(),
            AnkiSettings.DEFAULT_CSS
        )
        assertEquals(listOf("Expression", "Reading", "Meaning"), AnkiSettings.NOTE_FIELDS)
        assertEquals("Gaten", AnkiSettings().deckName)
        assertNull(AnkiSettings().validationError())
        assertTrue(AnkiSettings().isDefaultTemplates())
    }

    @Test
    fun builtInFieldsFollowTheCardsOwnOrder() {
        assertArrayEquals(
            arrayOf("食べる", "たべる", "to eat"),
            AnkiSettings().fields(card)
        )
    }

    @Test
    fun mappedFieldsFollowTheMappingAndLeaveEmptiesBlank() {
        assertArrayEquals(
            arrayOf("to eat", "", "食べる"),
            mapped.fields(card)
        )
    }

    @Test
    fun duplicateKeyIsTheFirstMappedField() {
        assertEquals(card.meaning, duplicateKey(mapped.fields(card)))
        assertEquals(card.expression, duplicateKey(AnkiSettings().fields(card)))

        val meaningLast = mapped.copy(fieldSources = listOf(AnkiField.EXPRESSION, AnkiField.EMPTY, AnkiField.MEANING))
        assertEquals(card.expression, duplicateKey(meaningLast.fields(card)))
    }

    @Test
    fun aFieldValueCannotCarryAnkiDroidsFieldSeparator() {
        val hostile = AnkiCard(
            expression = "食べる\u001f",
            reading = "たべる",
            meaning = "to eat\u0000and drink"
        )
        assertArrayEquals(
            arrayOf("食べる ", "たべる", "to eat and drink"),
            AnkiSettings().fields(hostile)
        )
        // The duplicate key is serialized the same way as the note it is checked against.
        assertEquals("食べる ", duplicateKey(AnkiSettings().fields(hostile)))
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
    fun aMappingSavedForTheRemovedSentenceFieldIsRefusedNotRewritten() {
        // What an install that mapped the (now removed) sentence source has in its settings file:
        // "SENTENCE" names nothing any more, so it drops out and the mapping no longer covers the
        // note type's fields. Validation refuses it, and nothing is ever written behind the user.
        val loaded = splitFieldSources("SENTENCE,EMPTY,EXPRESSION")

        assertEquals(listOf(AnkiField.EMPTY, AnkiField.EXPRESSION), loaded)
        assertEquals(
            AnkiSettingsError.MAPPING,
            AnkiSettings(
                modelId = 5L,
                modelFields = listOf("Expression", "Reading", "Meaning"),
                fieldSources = loaded
            ).validationError()
        )
        // Even a note type with as many fields as the shortened mapping has is refused, because the
        // first field — the one AnkiDroid checks duplicates on — would be empty.
        assertEquals(
            AnkiSettingsError.MAPPING,
            AnkiSettings(
                modelId = 5L,
                modelFields = listOf("Expression", "Reading"),
                fieldSources = loaded
            ).validationError()
        )
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
