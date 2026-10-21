/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun `a subject above the granted level is dropped`() = runBlocking {
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
        assertEquals(WaniKaniLessonQueue.Empty, client.lessonsQueue())
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

    private fun lessonApi(
        assignments: String? = null,
        subjects: String? = null,
        user: String? = null,
        unauthorized: Boolean = false
    ) = FakeLessonApi().apply {
        putHandler = { if (unauthorized) WaniKaniFetch.Unauthorized else WaniKaniFetch.Body("{}") }
        getHandler = { path, query ->
            when {
                unauthorized -> WaniKaniFetch.Unauthorized
                path == "user" && user != null -> WaniKaniFetch.Body(user)
                path == "assignments" && assignments != null -> WaniKaniFetch.Body(assignments)
                path == "subjects" && subjects != null -> WaniKaniFetch.Body(subjects)
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
    val writes = mutableListOf<String>()

    override suspend fun get(path: String, query: List<Pair<String, String>>, token: String): WaniKaniFetch {
        requests += path
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
