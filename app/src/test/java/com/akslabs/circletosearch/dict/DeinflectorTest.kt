/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * YomiLens: Japanese screen lookup for immersion learners.
 *
 * Freehand circle / drag-select a region -> on-device Japanese OCR (ML Kit, Tesseract fallback)
 * -> offline JMdict lookup with deinflection -> result panel with real Anki (AnkiDroid) export.
 */

package com.akslabs.circletosearch.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinflectorTest {

    private fun candidates(surface: String) = Deinflector.candidates(surface)

    private fun assertCandidate(surface: String, expected: String) {
        val actual = candidates(surface)
        assertTrue(
            "candidates(\"$surface\") = $actual should contain \"$expected\"",
            actual.contains(expected)
        )
    }

    private fun assertFirstCandidate(surface: String, expected: String) {
        val actual = candidates(surface)
        assertEquals(
            "candidates(\"$surface\") = $actual should start with \"$expected\"",
            expected,
            actual.first()
        )
    }

    @Test
    fun ichidanForms() {
        assertFirstCandidate("食べた", "食べる")
        assertFirstCandidate("食べて", "食べる")
        assertFirstCandidate("食べます", "食べる")
        assertFirstCandidate("食べました", "食べる")
        assertCandidate("食べません", "食べる")
        assertFirstCandidate("食べない", "食べる")
        assertFirstCandidate("食べなかった", "食べる")
        assertCandidate("食べている", "食べる")
        assertCandidate("食べちゃった", "食べる")
        assertCandidate("食べれば", "食べる")
        assertCandidate("食べよう", "食べる")
        assertCandidate("食べろ", "食べる")
    }

    @Test
    fun godanTeAndPastForms() {
        assertFirstCandidate("買った", "買う")
        assertFirstCandidate("書いて", "書く")
        assertFirstCandidate("泳いだ", "泳ぐ")
        assertCandidate("話して", "話す")
        assertCandidate("待って", "待つ")
        assertCandidate("死んだ", "死ぬ")
        assertCandidate("遊んだ", "遊ぶ")
        assertCandidate("読んだ", "読む")
    }

    @Test
    fun godanStemForms() {
        assertFirstCandidate("書きます", "書く")
        assertFirstCandidate("飲まない", "飲む")
        assertCandidate("飲まなかった", "飲む")
        assertCandidate("話します", "話す")
        assertCandidate("帰ります", "帰る")
        assertCandidate("遊びたい", "遊ぶ")
        assertCandidate("書けば", "書く")
        assertCandidate("読める", "読む")
        assertCandidate("行かない", "行く")
        assertCandidate("買いました", "買う")
    }

    @Test
    fun godanRuVerbs() {
        assertFirstCandidate("あります", "ある")
        assertFirstCandidate("帰ります", "帰る")
        assertCandidate("分かりません", "分かる")
        assertCandidate("帰らない", "帰る")
        assertCandidate("帰って", "帰る")
        assertCandidate("分かった", "分かる")
        assertCandidate("帰れる", "帰る")
        assertCandidate("帰ろう", "帰る")
    }

    @Test
    fun irregularTeFormsOfIku() {
        assertFirstCandidate("いった", "いく")
        assertFirstCandidate("いって", "いく")
        assertCandidate("いっちゃった", "いく")
    }

    @Test
    fun passivePotentialAndCausative() {
        assertCandidate("食べられる", "食べる")
        assertCandidate("食べられなかった", "食べる")
        assertCandidate("買われる", "買う")
        assertCandidate("買われた", "買う")
        assertCandidate("食べさせる", "食べる")
        assertCandidate("買わせる", "買う")
        assertCandidate("買わされる", "買う")
        assertCandidate("食べさせられた", "食べる")
        assertCandidate("話される", "話す")
        assertCandidate("書ける", "書く")
        assertCandidate("食べられます", "食べる")
    }

    @Test
    fun teShimauAndWhileForms() {
        assertCandidate("食べてしまった", "食べる")
        assertCandidate("飲んでしまった", "飲む")
        assertCandidate("書いてしまう", "書く")
        assertCandidate("読みながら", "読む")
        assertCandidate("歩きながら", "歩く")
    }

    @Test
    fun suruAndKuru() {
        assertFirstCandidate("した", "する")
        assertFirstCandidate("します", "する")
        assertCandidate("しなかった", "する")
        assertCandidate("しています", "する")
        assertCandidate("勉強しませんでした", "勉強する")
        assertCandidate("きた", "くる")
        assertCandidate("こなかった", "くる")
        assertCandidate("来ます", "来る")
        assertCandidate("来なかった", "来る")
        assertCandidate("来られる", "来る")
    }

    @Test
    fun iAdjectiveForms() {
        assertFirstCandidate("高かった", "高い")
        assertCandidate("高くなかった", "高い")
        assertCandidate("高くない", "高い")
        assertCandidate("高ければ", "高い")
        assertCandidate("高くて", "高い")
        assertCandidate("楽しかった", "楽しい")
        assertFirstCandidate("よかった", "いい")
    }

    @Test
    fun copulaEndingsAreStripped() {
        assertCandidate("学生です", "学生")
        assertCandidate("学生だった", "学生")
        assertCandidate("静かでしょう", "静か")
    }

    @Test
    fun candidatesAreDedupedAndExcludeTheSurface() {
        val actual = candidates("食べた")
        assertEquals(actual.distinct(), actual)
        assertFalse(actual.contains("食べた"))
    }

    @Test
    fun candidatesOfADictionaryFormDoNotContainIt() {
        assertFalse(candidates("食べる").contains("食べる"))
        assertFalse(candidates("くる").contains("くる"))
    }

    @Test
    fun emptyInputHasNoCandidates() {
        assertEquals(emptyList<String>(), candidates(""))
    }
}
