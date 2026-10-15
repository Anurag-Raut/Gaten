/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordScannerTest {

    @Test
    fun longestMatchIsPreferred() {
        val words = WordScanner.scan("日本語", dictionaryOf("日本", "日本語"))

        assertEquals(listOf("日本語"), words.map { it.surface })
        assertEquals(0, words[0].start)
        assertEquals(3, words[0].end)
        assertEquals("日本語", words[0].baseForm)
        assertEquals("日本語", words[0].entries.single().primary)
    }

    @Test
    fun matchesNeverOverlap() {
        // 日本 matches at 0, so the overlapping 本語 inside 日本語 must not be emitted.
        val words = WordScanner.scan("日本語", dictionaryOf("日本", "本語"))

        assertEquals(listOf("日本"), words.map { it.surface })
        assertEquals(2, words[0].end)
    }

    @Test
    fun kanaOnlyWord() {
        val text = "たべる"
        val words = WordScanner.scan(text, dictionaryOf("たべる"))

        assertEquals(listOf("たべる"), words.map { it.surface })
        assertEquals("たべる", words[0].baseForm)
        assertEquals(text, text.substring(words[0].start, words[0].end))
    }

    @Test
    fun katakanaSurfaceIsFoldedToHiragana() {
        val words = WordScanner.scan("スシをたべる", dictionaryOf("すし", "たべる"))

        assertEquals(listOf("スシ", "たべる"), words.map { it.surface })
        assertEquals("すし", words[0].baseForm)
        assertEquals(0, words[0].start)
        assertEquals(2, words[0].end)
    }

    @Test
    fun katakanaSurfaceIsDeinflectedThroughTheFold() {
        val words = WordScanner.scan("ヨンダ", dictionaryOf("よむ"))

        assertEquals(1, words.size)
        assertEquals("ヨンダ", words[0].surface)
        assertEquals("よむ", words[0].baseForm)
    }

    @Test
    fun katakanaDictionaryEntryKeepsItsOwnForm() {
        val words = WordScanner.scan("コーヒー", dictionaryOf("コーヒー"))

        assertEquals("コーヒー", words.single().surface)
        assertEquals("コーヒー", words.single().baseForm)
    }

    @Test
    fun inflectedSurfaceIsDeinflected() {
        val words = WordScanner.scan("食べた", dictionaryOf("食べる"))

        assertEquals(1, words.size)
        assertEquals("食べた", words[0].surface)
        assertEquals(0, words[0].start)
        assertEquals(3, words[0].end)
        assertEquals("食べる", words[0].baseForm)
    }

    @Test
    fun aCompoundVerbStaysOneSpan() {
        val text = "振り返った"
        val words = WordScanner.scan(text, dictionaryOf("振り", "返る", "振り返る"))

        // 振り is a real word, but inside 振り返った it is this verb's own stem: the longer
        // inflected reading wins over the shorter dictionary form.
        assertEquals(listOf("振り返った"), words.map { it.surface })
        assertEquals(listOf("振り返る"), words.map { it.baseForm })
        assertEquals(0, words[0].start)
        assertEquals(5, words[0].end)
        assertEquals(text.substring(words[0].start, words[0].end), words[0].surface)
    }

    @Test
    fun aCompoundVerbTeFormStaysOneSpan() {
        val words = WordScanner.scan("振り返って", dictionaryOf("振り", "返る", "振り返る"))

        assertEquals(listOf("振り返って"), words.map { it.surface })
        assertEquals(listOf("振り返る"), words.map { it.baseForm })
        assertEquals(5, words[0].end)
    }

    @Test
    fun aMasuStemDoesNotShadowTheWholeVerb() {
        val words = WordScanner.scan("振り返ります", dictionaryOf("振り", "返る", "振り返る"))

        assertEquals(listOf("振り返ります"), words.map { it.surface })
        assertEquals(listOf("振り返る"), words.map { it.baseForm })
        assertEquals(6, words[0].end)
    }

    @Test
    fun aDictionaryFormStillWinsDirectly() {
        val words = WordScanner.scan("振り返る", dictionaryOf("振り", "振り返る"))

        assertEquals(listOf("振り返る"), words.map { it.surface })
        assertEquals(listOf("振り返る"), words.map { it.baseForm })
    }

    @Test
    fun aNounStemDoesNotShadowTheVerb() {
        val words = WordScanner.scan("話した", dictionaryOf("話し", "話す"))

        assertEquals(listOf("話した"), words.map { it.surface })
        assertEquals("話す", words[0].baseForm)
        assertEquals(3, words[0].end)
    }

    @Test
    fun aKanjiStemNounDoesNotShadowTheVerb() {
        val words = WordScanner.scan("食べた", dictionaryOf("食", "食べる"))

        assertEquals(listOf("食べた"), words.map { it.surface })
        assertEquals("食べる", words[0].baseForm)
    }

    @Test
    fun aMasuStemNounDoesNotShadowTheVerb() {
        val words = WordScanner.scan("使います", dictionaryOf("使い", "使う"))

        assertEquals(listOf("使います"), words.map { it.surface })
        assertEquals("使う", words[0].baseForm)
        assertEquals(4, words[0].end)
    }

    @Test
    fun aCompoundSuruVerbNounStaysWhole() {
        val words = WordScanner.scan("思い出した", dictionaryOf("思い出", "思い出す"))

        assertEquals(listOf("思い出した"), words.map { it.surface })
        assertEquals("思い出す", words[0].baseForm)
    }

    @Test
    fun aDirectHitWinsAtEqualLength() {
        // した and きた are words in their own right; deinflecting them to する / くる on their own
        // would be wrong, so the dictionary form still wins at equal length.
        assertEquals(
            listOf("した"),
            WordScanner.scan("した", dictionaryOf("した", "する")).map { it.baseForm }
        )
        assertEquals(
            listOf("きた"),
            WordScanner.scan("きた", dictionaryOf("きた", "くる")).map { it.baseForm }
        )
    }

    @Test
    fun aCopulaIsNeverSwallowedIntoTheWord() {
        // です is stripped by a deinflection rule, so 学生です resolves to 学生 as a candidate — but
        // 学生 is a word of its own and must stay the span, with the copula beside it.
        val bare = WordScanner.scan("学生です", dictionaryOf("学生"))

        assertEquals(listOf("学生"), bare.map { it.surface })
        assertEquals(2, bare[0].end)

        val withCopula = WordScanner.scan("学生です", dictionaryOf("学生", "です"))

        assertEquals(listOf("学生", "です"), withCopula.map { it.surface })
        assertEquals(listOf(0, 2), withCopula.map { it.start })
        assertEquals(listOf(2, 4), withCopula.map { it.end })

        assertEquals(
            listOf("静か"),
            WordScanner.scan("静かだった", dictionaryOf("静か")).map { it.surface }
        )
    }

    @Test
    fun multipleWordsOfASentenceAreFound() {
        val text = "日本語を勉強します"
        val words = WordScanner.scan(text, dictionaryOf("日本語", "日本", "語", "勉強", "する"))

        assertEquals(listOf("日本語", "勉強", "します"), words.map { it.surface })
        assertEquals(4, words[1].start)
        assertEquals(6, words[1].end)
        assertEquals("する", words[2].baseForm)
    }

    @Test
    fun unmatchedCharactersAreSkipped() {
        val text = "hello 日本"
        val words = WordScanner.scan(text, dictionaryOf("日本"))

        assertEquals(listOf("日本"), words.map { it.surface })
        assertEquals(6, words[0].start)
        assertEquals(8, words[0].end)
    }

    @Test
    fun garbageOnlyTextYieldsNothing() {
        assertTrue(WordScanner.scan("@#$%^&*()", dictionaryOf("日本語", "日本")).isEmpty())
        assertTrue(WordScanner.scan("", dictionaryOf("日本語")).isEmpty())
        assertTrue(WordScanner.scan("あいうえお", dictionaryOf()).isEmpty())
    }

    @Test
    fun maxWordsCapsTheResult() {
        val words = WordScanner.scan("日本語日本語日本語", dictionaryOf("日本語"), maxWords = 2)

        assertEquals(listOf("日本語", "日本語"), words.map { it.surface })
        assertEquals(6, words.last().end)
        assertTrue(WordScanner.scan("日本語", dictionaryOf("日本語"), maxWords = 0).isEmpty())
    }

    @Test
    fun kanjiWordsRankFirstAndSpansStayDisjoint() {
        val text = "時々、日本語の字幕を読みながら勉強します"
        val lookup = dictionaryOf("日本語", "字幕", "読む", "勉強", "する")
        val words = WordScanner.scan(text, lookup)

        // Ranking puts kanji-bearing surfaces first; text order holds inside each rank group.
        assertEquals(
            listOf("日本語", "字幕", "勉強", "読みながら", "します"),
            words.map { it.surface }
        )
        assertEquals(
            listOf("日本語", "字幕", "勉強", "読む", "する"),
            words.map { it.baseForm }
        )
        words.forEach { word ->
            assertEquals(word.surface, text.substring(word.start, word.end))
            assertTrue(word.entries.isNotEmpty())
        }
        // Ranking reorders the list, but the underlying spans must never overlap.
        words
            .map { it.start to it.end }
            .sortedBy { it.first }
            .zipWithNext { previous, next -> assertTrue(previous.second <= next.first) }
    }

    @Test
    fun loneKanaIsDropped() {
        val text = "猫が水を飲む"
        val words = WordScanner.scan(text, dictionaryOf("猫", "が", "水", "を", "飲む", "む"))

        // が / を / む are single kana: particles and auxiliaries, noise for a reader.
        assertEquals(listOf("猫", "水", "飲む"), words.map { it.surface })
    }

    @Test
    fun kanjiWordsOutrankKanaWords() {
        val text = "コンピュータを使います"
        val words = WordScanner.scan(text, dictionaryOf("コンピュータ", "使う", "する"))

        assertEquals(listOf("使います", "コンピュータ"), words.map { it.surface })
        assertEquals("使う", words[0].baseForm)
    }

    @Test
    fun latinSurfacesAreDropped() {
        val lookup = dictionaryOf("C", "言語")

        // JMdict carries Latin headwords ("C" read シー); OCR reading a keyboard key must not find one.
        assertTrue(WordScanner.scan("C", lookup).isEmpty())

        // A Latin key next to real text must not shadow the Japanese word around it.
        val words = WordScanner.scan("C言語", lookup)

        assertEquals(listOf("言語"), words.map { it.surface })
    }
}
