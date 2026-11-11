/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The lesson queue and the one write that finishes a lesson: the join of assignments to subjects, the
 * order WaniKani presents them in, the entitlement filter, and `PUT /assignments/<id>/start`.
 *
 * The API is stood in for rather than aimed at a server, so these run on a plain JVM and never touch
 * a real account.
 */
class WaniKaniLessonTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "wanikani") }

    private fun client(api: WaniKaniApi, credentials: WaniKaniCredentials = LessonCredentials(TOKEN)) =
        WaniKaniClient(dir, credentials, api) { 0L }

    @Test
    fun `a lesson subject decodes with its teach fields`() {
        val items = decodeLessonSubjectCollection(
            WaniKaniJson.collection(WaniKaniJson.vocabulary(id = 2467, character = "一")),
            mapOf(2467 to 99)
        )!!
        val item = items.single()
        assertEquals(99, item.assignmentId)
        assertEquals(2467, item.subjectId)
        assertEquals("vocabulary", item.type)
        assertEquals("一", item.characters)
        assertEquals(1, item.level)
        assertEquals(3, item.lessonPosition)
        assertEquals(listOf("One"), item.meanings)
        assertEquals("One", item.primaryMeaning)
        assertEquals(listOf("いち"), item.readings)
        assertEquals("いち", item.primaryReading)
        // The mnemonics are plain text: WaniKani's own markup is stripped.
        assertEquals("A single ground stroke.", item.meaningMnemonic)
        assertEquals("It is read いち.", item.readingMnemonic)
        assertEquals(listOf(WaniKaniComponent(440, null, null)), item.components)
        assertEquals(listOf(WaniKaniContextSentence("一", "one")), item.contextSentences)
        assertEquals("https://www.wanikani.com/vocabulary/%E4%B8%80", item.documentUrl)
    }

    @Test
    fun `a subject the assignments do not name is left out of the queue`() {
        val items = decodeLessonSubjectCollection(
            WaniKaniJson.collection(WaniKaniJson.vocabulary(id = 1), WaniKaniJson.vocabulary(id = 2)),
            mapOf(1 to 10)
        )!!
        assertEquals(listOf(1), items.map { it.subjectId })
        assertEquals(listOf(10), items.map { it.assignmentId })
    }

    @Test
    fun `a malformed collection is a failure, not an empty queue`() {
        assertNull(decodeLessonSubjectCollection("<html>not a collection</html>", mapOf(1 to 1)))
    }

    @Test
    fun `the queue joins assignments to subjects and orders them the way WaniKani presents`() =
        runBlocking {
            val api = lessonApi(
                assignments = WaniKaniJson.collection(
                    WaniKaniJson.assignment(1, 2467, "vocabulary", AVAILABLE),
                    WaniKaniJson.assignment(2, 440, "kanji", AVAILABLE),
                    WaniKaniJson.assignment(3, 1, "radical", AVAILABLE)
                ),
                subjects = WaniKaniJson.collection(
                    WaniKaniJson.vocabulary(id = 2467, character = "一", level = 2, lessonPosition = 1),
                    WaniKaniJson.kanji(id = 440, character = "一", level = 1, componentIds = emptyList()),
                    WaniKaniJson.radical(id = 1, character = null)
                )
            )
            val queue = client(api).lessonsQueue()
            assertTrue(queue is WaniKaniLessonQueue.Available)
            val items = (queue as WaniKaniLessonQueue.Available).items
            // Level first, then WaniKani's own lesson position: the radical (1, 1), the kanji (1, 2),
            // then the vocabulary (2, 1).
            assertEquals(listOf(1, 440, 2467), items.map { it.subjectId })
            assertEquals(listOf(3, 2, 1), items.map { it.assignmentId })
        }

    @Test
    fun `a subject above the granted level is dropped, and a wholly restricted queue is locked`() =
        runBlocking {
            val api = lessonApi(
                assignments = WaniKaniJson.collection(
                    WaniKaniJson.assignment(1, 500, "kanji", AVAILABLE)
                ),
                subjects = WaniKaniJson.collection(
                    WaniKaniJson.kanji(id = 500, character = "語", level = 5, componentIds = emptyList())
                ),
                user = WaniKaniJson.user(type = "free", maxLevelGranted = 3, periodEndsAt = null)
            )
            val client = client(api)
            client.connect(TOKEN)
            // Not Empty: WaniKani has a lesson, the account's grant just does not reach it.
            assertEquals(WaniKaniLessonQueue.Locked, client.lessonsQueue())
        }

    @Test
    fun `a restricted subject is dropped while a granted one is still taught`() = runBlocking {
        val api = lessonApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(1, 1, "radical", AVAILABLE),
                WaniKaniJson.assignment(2, 500, "kanji", AVAILABLE)
            ),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.radical(id = 1, character = null, level = 1),
                WaniKaniJson.kanji(id = 500, character = "語", level = 5, componentIds = emptyList())
            ),
            user = WaniKaniJson.user(type = "free", maxLevelGranted = 3, periodEndsAt = null)
        )
        val client = client(api)
        client.connect(TOKEN)
        val queue = client.lessonsQueue() as WaniKaniLessonQueue.Available
        assertEquals(listOf(1), queue.items.map { it.subjectId })
    }

    @Test
    fun `an empty assignment collection is an empty queue and a broken body is a failure`() =
        runBlocking {
            assertEquals(
                WaniKaniLessonQueue.Empty,
                client(lessonApi(assignments = WaniKaniJson.collection())).lessonsQueue()
            )
            assertEquals(
                WaniKaniLessonQueue.Unreachable(WaniKaniFailure.MALFORMED),
                client(lessonApi(assignments = "<html>nope</html>")).lessonsQueue()
            )
        }

    @Test
    fun `without a token the queue asks for one and makes no request`() = runBlocking {
        val api = lessonApi(assignments = WaniKaniJson.collection())
        assertEquals(
            WaniKaniLessonQueue.NeedsToken,
            client(api, LessonCredentials()).lessonsQueue()
        )
        assertEquals(emptyList<String>(), api.requests)
    }

    @Test
    fun `a rejected token is reported as such`() = runBlocking {
        val api = lessonApi(unauthorized = true)
        assertEquals(WaniKaniLessonQueue.AuthRejected, client(api).lessonsQueue())
    }

    @Test
    fun `starting a lesson sends a PUT to the assignment's start`() = runBlocking {
        val api = lessonApi()
        assertEquals(WaniKaniLessonStart.Started, client(api).startLesson(7))
        assertEquals(listOf("assignments/7/start {}"), api.writes)
    }

    @Test
    fun `an assignment id that cannot be sent is refused without a request`() = runBlocking {
        val api = lessonApi()
        assertEquals(
            WaniKaniLessonStart.Unreachable(WaniKaniFailure.MALFORMED),
            client(api).startLesson(0)
        )
        assertEquals(emptyList<String>(), api.writes)
    }

    @Test
    fun `a start without a token asks for one, and a refused one is reported`() = runBlocking {
        assertEquals(
            WaniKaniLessonStart.NeedsToken,
            client(lessonApi(), LessonCredentials()).startLesson(7)
        )
        assertEquals(
            WaniKaniLessonStart.AuthRejected,
            client(lessonApi(unauthorized = true)).startLesson(7)
        )
    }

    @Test
    fun `a start that could not go through reports the failure`() = runBlocking {
        val api = lessonApi()
        api.putHandler = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        assertEquals(
            WaniKaniLessonStart.Unreachable(WaniKaniFailure.OFFLINE),
            client(api).startLesson(7)
        )
        assertNull(client(lessonApi()).startLesson(7) as? WaniKaniLessonStart.Unreachable)
    }

    @Test
    fun `a kanji lesson keeps its amalgamation ids and a vocabulary keeps none`() {
        val kanji = decodeLessonSubjectCollection(
            WaniKaniJson.collection(WaniKaniJson.kanji(amalgamationIds = (1..40).toList())),
            mapOf(440 to 10)
        )!!.single()
        // Bounded: only the first thirty are kept, since they are asked for in one url per batch.
        assertEquals((1..30).toList(), kanji.amalgamationIds)
        // Nothing is resolved at decode time: the words are filled in from the queue's extra request.
        assertEquals(emptyList<WaniKaniRelatedWord>(), kanji.relatedWords)

        val vocabulary = decodeLessonSubjectCollection(
            WaniKaniJson.collection(WaniKaniJson.vocabulary(id = 2467)),
            mapOf(2467 to 11)
        )!!.single()
        // A vocabulary names no amalgamations, and its own subjects are kanji, not other words.
        assertEquals(emptyList<Int>(), vocabulary.amalgamationIds)
        assertEquals(emptyList<WaniKaniRelatedWord>(), vocabulary.relatedWords)
    }

    @Test
    fun `the first batch's components and the words a kanji is used in are filled in`() = runBlocking {
        // Seven words the kanji is used in, deliberately not in level order, so the expected list
        // below is the order the queue has to impose rather than the order WaniKani sent them in.
        val words = listOf(
            Triple(101, 3, "上"),
            Triple(102, 1, "下"),
            Triple(103, 2, "中"),
            Triple(104, 1, "大"),
            Triple(105, 3, "小"),
            Triple(106, 2, "川"),
            Triple(107, 1, "山")
        )
        val api = lessonApi(
            assignments = WaniKaniJson.collection(
                WaniKaniJson.assignment(1, 440, "kanji", AVAILABLE),
                WaniKaniJson.assignment(2, 1, "radical", AVAILABLE),
                *words.mapIndexed { index, word ->
                    WaniKaniJson.assignment(3 + index, word.first, "vocabulary", AVAILABLE)
                }.toTypedArray()
            ),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.kanji(
                    id = 440,
                    lessonPosition = 1,
                    componentIds = listOf(1),
                    amalgamationIds = words.map { it.first }
                ),
                WaniKaniJson.radical(id = 1, character = "一", lessonPosition = 2, name = "Ground"),
                *words.mapIndexed { index, word ->
                    WaniKaniJson.vocabulary(
                        id = word.first,
                        character = word.third,
                        level = word.second,
                        lessonPosition = 10 + index,
                        meaning = "Word ${word.first}"
                    )
                }.toTypedArray()
            )
        )

        val items = (client(api).lessonsQueue() as WaniKaniLessonQueue.Available).items
        assertEquals(9, items.size)
        val kanji = items.single { it.subjectId == 440 }
        // The bare component id is resolved into a chip that can be drawn.
        assertEquals(listOf(WaniKaniComponent(1, "一", "Ground")), kanji.components)
        // Five of the seven, the lowest level first, the id breaking a tie.
        assertEquals(
            listOf(
                WaniKaniRelatedWord("下", "いち", "Word 102"),
                WaniKaniRelatedWord("大", "いち", "Word 104"),
                WaniKaniRelatedWord("山", "いち", "Word 107"),
                WaniKaniRelatedWord("中", "いち", "Word 103"),
                WaniKaniRelatedWord("川", "いち", "Word 106")
            ),
            kanji.relatedWords
        )
    }

    @Test
    fun `a word the subscription does not reach is not shown as used in`() = runBlocking {
        val api = lessonApi(
            assignments = WaniKaniJson.collection(WaniKaniJson.assignment(1, 440, "kanji", AVAILABLE)),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.kanji(id = 440, componentIds = emptyList(), amalgamationIds = listOf(101, 102)),
                WaniKaniJson.vocabulary(id = 101, character = "上", level = 3, meaning = "Above"),
                WaniKaniJson.vocabulary(id = 102, character = "下", level = 5, meaning = "Below")
            ),
            user = WaniKaniJson.user(type = "free", maxLevelGranted = 3, periodEndsAt = null)
        )
        val client = client(api)
        client.connect(TOKEN)

        val kanji = (client.lessonsQueue() as WaniKaniLessonQueue.Available).items.single()
        // Level 5 is beyond a free account's grant, exactly as the lesson itself would be.
        assertEquals(listOf(WaniKaniRelatedWord("上", "いち", "Above")), kanji.relatedWords)
    }

    @Test
    fun `a failed detail request leaves the queue teachable with what it has`() = runBlocking {
        val api = lessonApi(
            assignments = WaniKaniJson.collection(WaniKaniJson.assignment(1, 440, "kanji", AVAILABLE)),
            subjects = WaniKaniJson.collection(
                WaniKaniJson.kanji(id = 440, componentIds = listOf(1), amalgamationIds = listOf(101))
            ),
            // The one request that resolves the components and the used-in words is an extra: losing
            // it must not lose the lesson, which is still taught from its meaning and reading.
            detail = WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        )

        val queue = client(api).lessonsQueue()
        val item = (queue as WaniKaniLessonQueue.Available).items.single()
        assertEquals(listOf(WaniKaniComponent(1, null, null)), item.components)
        assertEquals(emptyList<WaniKaniRelatedWord>(), item.relatedWords)
    }

    @Test
    fun `only the first five subjects of a long queue are enriched`() = runBlocking {
        val ids = (1..6).toList()
        val api = lessonApi(
            assignments = WaniKaniJson.collection(
                *ids.map { id ->
                    WaniKaniJson.assignment(id, 500 + id, "vocabulary", AVAILABLE)
                }.toTypedArray()
            ),
            subjects = WaniKaniJson.collection(
                *ids.map { id ->
                    WaniKaniJson.vocabulary(
                        id = 500 + id,
                        character = "語",
                        level = 1,
                        lessonPosition = id,
                        componentIds = listOf(900 + id)
                    )
                }.toTypedArray()
            ),
            // The extra request answers every component, the sixth included: that one is only left
            // unresolved because it is never asked for.
            detail = WaniKaniFetch.Body(
                WaniKaniJson.collection(
                    *ids.map { id ->
                        WaniKaniJson.radical(id = 900 + id, character = "一", name = "Ground")
                    }.toTypedArray()
                )
            )
        )

        val items = (client(api).lessonsQueue() as WaniKaniLessonQueue.Available).items
        assertEquals(6, items.size)
        assertEquals(listOf(WaniKaniComponent(901, "一", "Ground")), items.first().components)
        assertEquals(listOf(WaniKaniComponent(905, "一", "Ground")), items[4].components)
        // Past the batch WaniKani teaches in one sitting: the id is a chip to tap, not a lesson.
        assertEquals(listOf(WaniKaniComponent(906, null, null)), items[5].components)
        val requested = api.queries.last { query ->
            query.any { it.first == "ids" }
        }.first { it.first == "ids" }.second.split(",")
        assertFalse(requested.contains("906"))
    }

    private fun lessonApi(
        assignments: String? = null,
        subjects: String? = null,
        user: String? = null,
        unauthorized: Boolean = false,
        detail: WaniKaniFetch? = null
    ) = FakeLessonApi().apply {
        putHandler = { if (unauthorized) WaniKaniFetch.Unauthorized else WaniKaniFetch.Body("{}") }
        var subjectReads = 0
        getHandler = { path, query ->
            when {
                unauthorized -> WaniKaniFetch.Unauthorized
                path == "user" && user != null -> WaniKaniFetch.Body(user)
                path == "assignments" && assignments != null -> WaniKaniFetch.Body(assignments)
                path == "subjects" && subjects != null -> {
                    // The queue resolves its own subjects first, then asks for the batch's teaching
                    // detail in a second request, which [detail] stands in for.
                    subjectReads += 1
                    if (subjectReads == 1 || detail == null) WaniKaniFetch.Body(subjects) else detail
                }
                else -> WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
            }
        }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val AVAILABLE = "2018-02-27T00:00:00.000000Z"
    }
}

/** Answers `get` and records `put`, so the lesson queue's reads and its one write can be checked. */
private class FakeLessonApi : WaniKaniApi() {

    var getHandler: (String, List<Pair<String, String>>) -> WaniKaniFetch = { _, _ ->
        WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
    }

    var putHandler: (String) -> WaniKaniFetch = { WaniKaniFetch.Body("{}") }

    val requests = mutableListOf<String>()
    val queries = mutableListOf<List<Pair<String, String>>>()
    val writes = mutableListOf<String>()

    override suspend fun get(path: String, query: List<Pair<String, String>>, token: String): WaniKaniFetch {
        requests += path
        queries += query
        return getHandler(path, query)
    }

    override suspend fun put(path: String, body: String, token: String): WaniKaniFetch {
        writes += "$path $body"
        return putHandler(path)
    }
}

/** The token store, without a keystore. */
private class LessonCredentials(private var stored: String? = null) : WaniKaniCredentials {
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
