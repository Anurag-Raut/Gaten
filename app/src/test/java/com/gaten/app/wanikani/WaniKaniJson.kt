/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

/**
 * The API reference's own example payloads for revision 20170710, kept verbatim in the fields Gaten
 * reads and with the parts a test varies left as parameters, so the decoding is checked against
 * WaniKani's real shapes rather than a simplification of them.
 */
internal object WaniKaniJson {

    /** A `GET /subjects` collection around [resources]. */
    fun collection(vararg resources: String): String = """
        {
          "object": "collection",
          "url": "https://api.wanikani.com/v2/subjects",
          "pages": {"per_page": 1000, "next_url": null, "previous_url": null},
          "total_count": ${resources.size},
          "data_updated_at": "2018-04-09T18:08:59.946969Z",
          "data": [${resources.joinToString(", ")}]
        }
    """.trimIndent()

    /** A kanji resource: the reference's example for 一, with the subject's identity parameterised. */
    fun kanji(
        id: Int = 440,
        character: String = "一",
        level: Int = 1,
        componentIds: List<Int> = listOf(1),
        primaryMeaning: String = "One",
        reading: String = "いち",
        auxiliary: List<Pair<String, String>> = listOf("one" to "blacklist"),
        documentUrl: String = "https://www.wanikani.com/kanji/%E4%B8%80"
    ): String = """
        {
          "id": $id,
          "object": "kanji",
          "url": "https://api.wanikani.com/v2/subjects/$id",
          "data_updated_at": "2018-03-29T23:14:30.805034Z",
          "data": {
            "amalgamation_subject_ids": [56, 88, 91],
            "auxiliary_meanings": [${auxiliary.joinToString(", ") { """{"meaning": "${it.first}", "type": "${it.second}"}""" }}],
            "characters": "$character",
            "component_subject_ids": [${componentIds.joinToString(", ")}],
            "created_at": "2012-02-27T19:55:19.000000Z",
            "document_url": "$documentUrl",
            "hidden_at": null,
            "lesson_position": 2,
            "level": $level,
            "meanings": [
              {"meaning": "$primaryMeaning", "primary": true, "accepted_answer": true},
              {"meaning": "Flat", "primary": false, "accepted_answer": true}
            ],
            "meaning_hint": null,
            "meaning_mnemonic": "The <radical>ground</radical> holds a single <kanji>$primaryMeaning</kanji> stroke.",
            "readings": [
              {"type": "onyomi", "primary": true, "accepted_answer": true, "reading": "$reading"},
              {"type": "kunyomi", "primary": false, "accepted_answer": false, "reading": "ひと"},
              {"type": "nanori", "primary": false, "accepted_answer": false, "reading": "かず"}
            ],
            "reading_mnemonic": "Say <reading>specimen</reading> ($reading) next to the <kanji>$primaryMeaning</kanji>.",
            "reading_hint": null,
            "slug": "$character",
            "visually_similar_subject_ids": [],
            "spaced_repetition_system_id": 1
          }
        }
    """.trimIndent()

    /**
     * A radical resource: the reference's example for 一 (ground), which is the awkward case - a
     * radical may have no character and only an SVG image, which is what `character = null` builds.
     */
    fun radical(
        id: Int = 1,
        character: String? = "一",
        level: Int = 1,
        name: String = "Ground",
        slug: String = "ground",
        documentUrl: String = "https://www.wanikani.com/radicals/ground",
        imageUrl: String? = "https://cdn.wanikani.com/images/legacy/576-subject-1.svg?1520987227"
    ): String {
        val characters = character?.let { "\"$it\"" } ?: "null"
        val images = imageUrl?.let {
            """{"url": "$it", "metadata": {"inline_styles": true}, "content_type": "image/svg+xml"}"""
        } ?: ""
        return """
            {
              "id": $id,
              "object": "radical",
              "url": "https://api.wanikani.com/v2/subjects/$id",
              "data_updated_at": "2018-03-29T23:13:14.064836Z",
              "data": {
                "amalgamation_subject_ids": [5, 4, 98],
                "auxiliary_meanings": [{"meaning": "ground", "type": "blacklist"}],
                "characters": $characters,
                "character_images": [$images],
                "created_at": "2012-02-27T18:08:16.000000Z",
                "document_url": "$documentUrl",
                "hidden_at": null,
                "lesson_position": 1,
                "level": $level,
                "meanings": [{"meaning": "$name", "primary": true, "accepted_answer": true}],
                "meaning_mnemonic": "A stroke drawn along the <radical>ground</radical> marks this radical.",
                "slug": "$slug",
                "spaced_repetition_system_id": 2
              }
            }
        """.trimIndent()
    }

    /** A subject of a kind Gaten never looks up, to check it is refused rather than half-read. */
    fun vocabulary(id: Int = 2467, character: String = "一"): String = """
        {
          "id": $id,
          "object": "vocabulary",
          "url": "https://api.wanikani.com/v2/subjects/$id",
          "data_updated_at": "2018-12-12T23:09:52.234049Z",
          "data": {
            "characters": "$character",
            "level": 1,
            "meanings": [{"meaning": "One", "primary": true, "accepted_answer": true}],
            "readings": [{"type": "onyomi", "primary": true, "accepted_answer": true, "reading": "いち"}],
            "slug": "$character",
            "document_url": "https://www.wanikani.com/vocabulary/%E4%B8%80",
            "component_subject_ids": [440]
          }
        }
    """.trimIndent()

    /**
     * A kana vocabulary resource: the reference's example for ねこ, the awkward case for a review -
     * a subject quizzed on its meaning only, whose characters are its own reading, so it carries no
     * `readings` array at all.
     */
    fun kanaVocabulary(
        id: Int = 917,
        character: String = "ねこ",
        meaning: String = "Cat"
    ): String = """
        {
          "id": $id,
          "object": "kana_vocabulary",
          "url": "https://api.wanikani.com/v2/subjects/$id",
          "data_updated_at": "2020-07-22T21:12:17.303117Z",
          "data": {
            "characters": "$character",
            "level": 5,
            "meanings": [{"meaning": "$meaning", "primary": true, "accepted_answer": true}],
            "auxiliary_meanings": [],
            "slug": "$character",
            "document_url": "https://www.wanikani.com/vocabulary/$character",
            "component_subject_ids": [],
            "parts_of_speech": ["noun"]
          }
        }
    """.trimIndent()

    /**
     * One assignment of a `GET /assignments` collection: which subject is due, and when. The fields
     * Gaten reads are parameterised; the rest is the reference's example.
     */
    fun assignment(
        id: Int,
        subjectId: Int,
        subjectType: String,
        availableAt: String,
        hidden: Boolean = false
    ): String = """
        {
          "id": $id,
          "object": "assignment",
          "url": "https://api.wanikani.com/v2/assignments/$id",
          "data_updated_at": "2017-09-05T23:38:10.348180Z",
          "data": {
            "created_at": "2017-09-05T23:38:10.251517Z",
            "subject_id": $subjectId,
            "subject_type": "$subjectType",
            "srs_stage": 1,
            "unlocked_at": "2017-09-05T23:38:10.251517Z",
            "started_at": "2017-09-05T23:38:10.251517Z",
            "passed_at": null,
            "burned_at": null,
            "available_at": "$availableAt",
            "resurrected_at": null,
            "hidden": $hidden
          }
        }
    """.trimIndent()

    /**
     * A subject whose every meaning is rejected and which has no whitelisted auxiliary meaning - the
     * case a review has to fall back to the slug for, so the screen is never left without an answer
     * to judge against.
     */
    fun rejectedMeaningsRadical(id: Int = 42, slug: String = "ground"): String = """
        {
          "id": $id,
          "object": "radical",
          "url": "https://api.wanikani.com/v2/subjects/$id",
          "data_updated_at": "2018-03-29T23:13:14.064836Z",
          "data": {
            "auxiliary_meanings": [{"meaning": "ground", "type": "blacklist"}],
            "characters": null,
            "character_images": [],
            "level": 1,
            "meanings": [{"meaning": "Ground", "primary": true, "accepted_answer": false}],
            "meaning_mnemonic": "",
            "slug": "$slug",
            "spaced_repetition_system_id": 2
          }
        }
    """.trimIndent()

    /** A `GET /user` resource. Only its `subscription` is read; the rest is the reference's example. */
    fun user(
        type: String = "recurring",
        maxLevelGranted: Int = 60,
        periodEndsAt: String? = "2018-12-11T13:32:19.485748Z",
        active: Boolean = true
    ): String {
        val period = periodEndsAt?.let { "\"$it\"" } ?: "null"
        return """
            {
              "object": "user",
              "url": "https://api.wanikani.com/v2/user",
              "data_updated_at": "2018-04-06T14:26:53.022245Z",
              "data": {
                "id": "5a6a5234-a392-4a87-8f3f-33342afe8a42",
                "username": "example_user",
                "level": 5,
                "profile_url": "https://www.wanikani.com/users/example_user",
                "started_at": "2012-05-11T00:52:18.958466Z",
                "current_vacation_started_at": null,
                "subscription": {
                  "active": $active,
                  "type": "$type",
                  "max_level_granted": $maxLevelGranted,
                  "period_ends_at": $period
                },
                "preferences": {"default_voice_actor_id": 1, "lessons_batch_size": 10}
              }
            }
        """.trimIndent()
    }
}
