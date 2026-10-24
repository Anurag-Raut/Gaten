/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The review screen's native half, against WaniKani's documented payloads: what an assignment and a
 * subject decode into (an image-only radical, a kana vocabulary with nothing to read, a whitelisted
 * auxiliary meaning, rejected meanings falling back to the slug), the order the queue comes back in,
 * the exact body a finished review sends, and what WaniKani's refusals become.
 *
 * The API is stood in for rather than aimed at a server, so these run on a plain JVM with no socket
 * (and no way to reach a real WaniKani). What a real `POST /reviews` maps a 422 or a 429 to is
 * [WaniKaniApi.post]'s own reading of the status; here that is handed to the fake as the answer it
 * would produce, so the client's mapping is what is checked.
 */
class WaniKaniReviewTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "wanikani") }

    private fun client(api: WaniKaniApi, credentials: WaniKaniCredentials = StubCredentials(TOKEN)): WaniKaniClient =
        WaniKaniClient(dir, credentials, api) { 0L }

    @Test
    fun `the queue decodes an image-only radical and a kana vocabulary with no reading`() = runBlocking {
        val api = queueApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 11, subjectId = 1, subjectType = "radical", availableAt = EARLY),
                WaniKaniJson.assignment(id = 12, subjectId = 917, subjectType = "kana_vocabulary", availableAt = LATE)
            ),
            subjects = WaniKaniJson.collection(WaniKaniJson.radical(id = 1, character = null), WaniKaniJson.kanaVocabulary())
        )

        val available = client(api).reviewQueue() as WaniKaniReviewQueue.Available
        assertEquals(listOf(1, 917), available.items.map { it.subjectId })

        val radical = available.items[0]
        assertEquals("radical", radical.type)
        assertNull(radical.characters)
        assertEquals(listOf("Ground"), radical.meanings)
        assertEquals("Ground", radical.primaryMeaning)
        assertEquals(emptyList<String>(), radical.readings)
        assertNull(radical.primaryReading)

        val kana = available.items[1]
        assertEquals("kana_vocabulary", kana.type)
        assertEquals("ねこ", kana.characters)
        assertEquals(listOf("Cat"), kana.meanings)
        assertEquals(emptyList<String>(), kana.readings)
        assertNull(kana.primaryReading)

        // The assignments are filtered by WaniKani itself, and the subjects are asked for in one
        // request, by the ids the queue is about.
        assertEquals("assignments?immediately_available_for_review=true&hidden=false", api.requests[0])
        assertEquals("subjects?ids=1,917", api.requests[1])
    }

    @Test
    fun `a subject's study material decodes its notes and synonyms, and none is not a failure`() = runBlocking {
        val api = FakeReviewApi().apply {
            studyMaterialHandler = {
                WaniKaniFetch.Body(
                    """{"object":"collection","data":[{"id":65231,"object":"study_material","data":{"subject_id":241,"subject_type":"radical","meaning_note":"I like turtles","reading_note":"I like durtles","meaning_synonyms":["burn","sizzle"]}}]}"""
                )
            }
        }
        val found = client(api).studyMaterial(241) as WaniKaniStudyMaterialRead.Found
        assertEquals(65231, found.material?.id)
        assertEquals(241, found.material?.subjectId)
        assertEquals("I like turtles", found.material?.meaningNote)
        assertEquals("I like durtles", found.material?.readingNote)
        assertEquals(listOf("burn", "sizzle"), found.material?.meaningSynonyms)
        // The read is filtered by subject id, which is what keeps it to the reader's own record.
        assertEquals("study_materials?subject_ids=241", api.requests.single())

        val empty = FakeReviewApi().apply {
            studyMaterialHandler = { WaniKaniFetch.Body("""{"object":"collection","data":[]}""") }
        }
        assertEquals(
            WaniKaniStudyMaterialRead.Found(null),
            client(empty).studyMaterial(9)
        )

        val broken = FakeReviewApi().apply {
            studyMaterialHandler = { WaniKaniFetch.Body("<html>captive portal</html>") }
        }
        assertEquals(
            WaniKaniStudyMaterialRead.Unreachable(WaniKaniFailure.MALFORMED),
            client(broken).studyMaterial(9)
        )
    }

    @Test
    fun `a new note is created with the subject id, and an existing note is updated without one`() = runBlocking {
        val api = FakeReviewApi().apply {
            postHandler = {
                WaniKaniFetch.Body(
                    """{"id":234,"object":"study_material","data":{"subject_id":2,"meaning_note":"a note","reading_note":"","meaning_synonyms":["double"]}}"""
                )
            }
            putHandler = {
                WaniKaniFetch.Body(
                    """{"id":234,"object":"study_material","data":{"subject_id":2,"meaning_note":"a note","reading_note":"","meaning_synonyms":["double"]}}"""
                )
            }
        }
        val created = client(api).saveStudyMaterial(2, null, "a note", "", listOf("double"))
        assertEquals(234, (created as WaniKaniStudyMaterialWrite.Saved).material.id)
        val createBody = JSONObject(api.posts.single())
        val createMaterial = createBody.getJSONObject("study_material")
        assertEquals(2, createMaterial.getInt("subject_id"))
        assertEquals("a note", createMaterial.getString("meaning_note"))
        assertEquals("", createMaterial.getString("reading_note"))
        assertEquals("double", createMaterial.getJSONArray("meaning_synonyms").getString(0))

        val updated = client(api).saveStudyMaterial(2, 234, "changed", "read", listOf("double"))
        assertEquals(234, (updated as WaniKaniStudyMaterialWrite.Saved).material.id)
        assertEquals("study_materials/234", api.putPaths.single())
        val updateMaterial = JSONObject(api.puts.single()).getJSONObject("study_material")
        assertEquals("changed", updateMaterial.getString("meaning_note"))
        // An update is a full write, so the reader's existing synonyms ride along unchanged.
        assertEquals("double", updateMaterial.getJSONArray("meaning_synonyms").getString(0))
        assertFalse(updateMaterial.has("subject_id"))
    }

    @Test
    fun `a study material write with nothing to send is refused without a request`() = runBlocking {
        val api = FakeReviewApi()
        assertEquals(
            WaniKaniStudyMaterialWrite.Unreachable(WaniKaniFailure.MALFORMED),
            client(api).saveStudyMaterial(0, null, "note", "", emptyList())
        )
        assertEquals(emptyList<String>(), api.posts)
        assertEquals(emptyList<String>(), api.puts)
    }

    @Test
    fun `a queued vocabulary carries the level, mnemonics and examples its details show`() = runBlocking {
        val api = queueApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 10, subjectId = 2467, subjectType = "vocabulary", availableAt = EARLY)
            ),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.vocabulary(
                    id = 2467,
                    level = 27,
                    contextSentences = listOf("靴屋" to "shoe store"),
                    meaningMnemonic = "A single <radical>ground</radical> stroke.",
                    readingMnemonic = "It is read <reading>いち</reading>."
                )
            )
        )

        val item = (client(api).reviewQueue() as WaniKaniReviewQueue.Available).items.single()
        assertEquals(27, item.level)
        // The mnemonics are the plain text the details render: WaniKani's markup is stripped, not
        // shown, exactly as the lesson decoder already does.
        assertEquals("A single ground stroke.", item.meaningMnemonic)
        assertEquals("It is read いち.", item.readingMnemonic)
        assertEquals(listOf(WaniKaniContextSentence("靴屋", "shoe store")), item.contextSentences)
    }

    @Test
    fun `accepted meanings keep the whitelist and the primary first, and readings fold to hiragana`() = runBlocking {
        val api = queueApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 10, subjectId = 440, subjectType = "kanji", availableAt = EARLY)
            ),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.kanji(auxiliary = listOf("one" to "blacklist", "1" to "whitelist"), reading = "イチ")
            )
        )

        val item = (client(api).reviewQueue() as WaniKaniReviewQueue.Available).items.single()
        // "one" is blacklisted and must not be accepted; "1" is whitelisted and must be.
        assertEquals(listOf("One", "Flat", "1"), item.meanings)
        assertEquals("One", item.primaryMeaning)
        // Only the accepted reading: the kun'yomi and nanori are not accepted answers, and the
        // katakana is folded so a converted or typed answer can be compared with it directly.
        assertEquals(listOf("いち"), item.readings)
        assertEquals("いち", item.primaryReading)
    }

    @Test
    fun `meanings fall back to the slug when nothing is accepted`() = runBlocking {
        val api = queueApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 10, subjectId = 42, subjectType = "radical", availableAt = EARLY)
            ),
            subjects = WaniKaniJson.collection(WaniKaniJson.rejectedMeaningsRadical())
        )

        val item = (client(api).reviewQueue() as WaniKaniReviewQueue.Available).items.single()
        assertEquals(listOf("ground"), item.meanings)
        assertEquals("ground", item.primaryMeaning)
        assertEquals(emptyList<String>(), item.readings)
        assertNull(item.primaryReading)
    }

    @Test
    fun `the queue is ordered by when each subject came up, then by its assignment`() = runBlocking {
        // Sent jumbled, and with two subjects that came up at the same instant: the order the screen
        // asks them in must be the one Gaten decides, not WaniKani's.
        val api = queueApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 30, subjectId = 3, subjectType = "radical", availableAt = LATE),
                WaniKaniJson.assignment(id = 11, subjectId = 2, subjectType = "radical", availableAt = EARLY),
                WaniKaniJson.assignment(id = 10, subjectId = 1, subjectType = "radical", availableAt = EARLY),
                WaniKaniJson.assignment(id = 40, subjectId = 4, subjectType = "radical", availableAt = EARLY, hidden = true)
            ),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.radical(id = 3, name = "Three"),
                WaniKaniJson.radical(id = 4, name = "Four"),
                WaniKaniJson.radical(id = 2, name = "Two"),
                WaniKaniJson.radical(id = 1, name = "One")
            )
        )

        val available = client(api).reviewQueue() as WaniKaniReviewQueue.Available
        assertEquals(listOf(1, 2, 3), available.items.map { it.subjectId })
        // The hidden assignment is not queued, and is not even asked about.
        assertEquals("subjects?ids=3,2,1", api.requests[1])
    }

    @Test
    fun `an empty queue is an answer, and a broken body is a failure`() = runBlocking {
        val empty = queueApi(WaniKaniJson.collection(), WaniKaniJson.collection())
        assertEquals(WaniKaniReviewQueue.Empty, client(empty).reviewQueue())
        assertEquals(1, empty.requests.size)

        val broken = queueApi("<html>captive portal</html>", WaniKaniJson.collection())
        assertEquals(
            WaniKaniReviewQueue.Unreachable(WaniKaniFailure.MALFORMED),
            client(broken).reviewQueue()
        )

        val brokenSubjects = queueApi(
            WaniKaniJson.collection(
                WaniKaniJson.assignment(id = 10, subjectId = 1, subjectType = "radical", availableAt = EARLY)
            ),
            "<html>captive portal</html>"
        )
        assertEquals(
            WaniKaniReviewQueue.Unreachable(WaniKaniFailure.MALFORMED),
            client(brokenSubjects).reviewQueue()
        )
    }

    @Test
    fun `a rejected token is an auth rejection, and a rate limit is reported with its wait`() = runBlocking {
        val rejected = queueApi(WaniKaniJson.collection(), WaniKaniJson.collection())
        rejected.assignmentHandler = { WaniKaniFetch.Unauthorized }
        assertEquals(WaniKaniReviewQueue.AuthRejected, client(rejected).reviewQueue())

        val limited = queueApi(WaniKaniJson.collection(), WaniKaniJson.collection())
        limited.assignmentHandler = { WaniKaniFetch.Failed(WaniKaniFailure.RATE_LIMITED, 42) }
        assertEquals(
            WaniKaniReviewQueue.Unreachable(WaniKaniFailure.RATE_LIMITED, 42),
            client(limited).reviewQueue()
        )
        // Never retried on our own: one request, and one answer.
        assertEquals(1, limited.requests.size)
    }

    @Test
    fun `without a token nothing is asked of WaniKani`() = runBlocking {
        val api = queueApi(WaniKaniJson.collection(), WaniKaniJson.collection())
        val client = client(api, StubCredentials())

        assertEquals(WaniKaniReviewQueue.NeedsToken, client.reviewQueue())
        assertEquals(WaniKaniReviewSubmission.NeedsToken, client.submitReview(440, 0, 0))
        assertEquals(emptyList<String>(), api.requests)
        assertEquals(emptyList<String>(), api.posts)
    }

    @Test
    fun `a finished review sends exactly the counts WaniKani's request takes`() = runBlocking {
        val api = FakeReviewApi()
        assertEquals(WaniKaniReviewSubmission.Submitted, client(api).submitReview(440, 0, 1))
        assertEquals(
            listOf("""{"review":{"subject_id":440,"incorrect_meaning_answers":0,"incorrect_reading_answers":1}}"""),
            api.posts
        )
    }

    @Test
    fun `a review WaniKani refuses is malformed, and a rate limit is reported with its wait`() = runBlocking {
        // 422: WaniKani will not accept this review. `post` reads it as MALFORMED, and the client
        // must not pretend the request went through.
        val refused = FakeReviewApi()
        refused.postHandler = { WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED) }
        assertEquals(
            WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.MALFORMED),
            client(refused).submitReview(440, 1, 0)
        )

        // 429: the same, with the seconds to wait that came with it.
        val limited = FakeReviewApi()
        limited.postHandler = { WaniKaniFetch.Failed(WaniKaniFailure.RATE_LIMITED, 30) }
        assertEquals(
            WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.RATE_LIMITED, 30),
            client(limited).submitReview(440, 1, 0)
        )
        assertEquals(1, limited.posts.size)
    }

    @Test
    fun `an auth failure is an auth rejection and is never retried`() = runBlocking {
        val api = FakeReviewApi()
        api.postHandler = { WaniKaniFetch.Unauthorized }
        assertEquals(WaniKaniReviewSubmission.AuthRejected, client(api).submitReview(440, 0, 0))
        assertEquals(1, api.posts.size)
    }

    @Test
    fun `a subject id or a count that cannot be sent is refused without a request`() = runBlocking {
        val api = FakeReviewApi()

        assertEquals(
            WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.MALFORMED),
            client(api).submitReview(0, 0, 0)
        )
        assertEquals(
            WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.MALFORMED),
            client(api).submitReview(440, -1, 0)
        )
        assertEquals(
            WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.MALFORMED),
            client(api).submitReview(440, 0, -1)
        )
        assertEquals(emptyList<String>(), api.posts)
    }

    @Test
    fun `a romaji answer is transliterated, so it can be compared with an item's readings`() {
        assertEquals("いち", readingAnswerHiragana("ichi"))
        assertEquals("ねこ", readingAnswerHiragana("neko"))
        assertEquals("しんぶん", readingAnswerHiragana("shinbun"))
    }

    @Test
    fun `katakana folds, whitespace goes, and kana is left as it is`() {
        assertEquals("いち", readingAnswerHiragana("イチ"))
        assertEquals("いち", readingAnswerHiragana("イチ "))
        assertEquals("いち", readingAnswerHiragana(" い ち "))
        assertEquals("いち", readingAnswerHiragana("いち"))
        // NFKC composes the half-width katakana first, and the full-width space is whitespace too.
        assertEquals("いち", readingAnswerHiragana("ｲﾁ"))
        assertEquals("いち", readingAnswerHiragana("いち\u3000"))
        // The prolonged sound mark is not katakana to fold: only ァ to ヶ are.
        assertEquals("らーめん", readingAnswerHiragana("ラーメン"))
        // Kanji is not a reading the converter may touch.
        assertEquals("一", readingAnswerHiragana("一"))
    }

    @Test
    fun `an answer that is not romaji the converter can read is left normalised but unconverted`() {
        // "cat" is read as far as its letters look like romaji (か) and then left alone: it is an
        // English word, not a reading, so it must not become かt's half-kana.
        assertEquals("cat", readingAnswerHiragana("cat"))
        assertEquals("english", readingAnswerHiragana("english"))
        assertEquals("", readingAnswerHiragana("   "))
    }

    @Test
    fun `an answer in mixed script is never transliterated`() {
        assertEquals("ねこcat", readingAnswerHiragana("ねこcat"))
        assertEquals("いちねこ", readingAnswerHiragana("イチねこ"))
    }

    private fun queueApi(assignments: String, subjects: String): FakeReviewApi = FakeReviewApi().apply {
        assignmentHandler = { WaniKaniFetch.Body(assignments) }
        subjectHandler = { WaniKaniFetch.Body(subjects) }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val EARLY = "2026-01-02T03:04:05.000000Z"
        const val LATE = "2026-01-02T09:04:05.000000Z"
    }
}

/** Answers the requests the review queue and a submission make, from whatever a test hands it. */
private class FakeReviewApi : WaniKaniApi() {

    val requests = mutableListOf<String>()
    val posts = mutableListOf<String>()

    val puts = mutableListOf<String>()
    val putPaths = mutableListOf<String>()

    var assignmentHandler: () -> WaniKaniFetch = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
    var subjectHandler: () -> WaniKaniFetch = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
    var studyMaterialHandler: () -> WaniKaniFetch = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
    var postHandler: (String) -> WaniKaniFetch = { WaniKaniFetch.Body("""{"id":1}""") }
    var putHandler: (String) -> WaniKaniFetch = { WaniKaniFetch.Body("""{"id":1}""") }

    override suspend fun get(path: String, query: List<Pair<String, String>>, token: String): WaniKaniFetch {
        requests += if (query.isEmpty()) {
            path
        } else {
            path + query.joinToString(separator = "&", prefix = "?") { "${it.first}=${it.second}" }
        }
        return when (path) {
            "assignments" -> assignmentHandler()
            "subjects" -> subjectHandler()
            "study_materials" -> studyMaterialHandler()
            else -> WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        }
    }

    override suspend fun post(path: String, body: String, token: String): WaniKaniFetch {
        posts += body
        return postHandler(body)
    }

    override suspend fun put(path: String, body: String, token: String): WaniKaniFetch {
        putPaths += path
        puts += body
        return putHandler(body)
    }
}

/** The token store, without a keystore. */
private class StubCredentials(private var stored: String? = null) : WaniKaniCredentials {

    override fun hasToken(): Boolean = stored != null

    override fun read(): String? = stored

    override fun save(token: String): Boolean {
        stored = token
        return true
    }

    override fun clear(): Boolean {
        stored = null
        return true
    }
}
