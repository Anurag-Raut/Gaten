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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * The stats read's decisions, against WaniKani's documented payloads: one run of the account's
 * requests, the counts taken from `total_count` of the filtered scope, the recall section summed over
 * the pages of `GET /review_statistics` it is allowed to follow, a snapshot cached for five minutes, a
 * run that fails keeping the last complete snapshot, a rejected token dropping it, and a `304` leaving
 * a cached part in place.
 *
 * The API is stood in for rather than aimed at a server, so these run on a plain JVM with no socket
 * and no token anywhere near a real account.
 */
class WaniKaniStatsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "wanikani") }

    /** What the client is told the time is; a test moves it past the cache's five minutes. */
    private var now = 1_000_000L

    private fun stats(
        api: WaniKaniApi,
        credentials: WaniKaniCredentials = StatsCredentials(TOKEN)
    ) = WaniKaniStats(dir, credentials, api, { now })

    @Test
    fun `one run asks for the account, the summary, the SRS counts, the history and the recall pages`() =
        runBlocking {
            val api = StatsApi()
            val result = stats(api).read()

            assertEquals(
                listOf(
                    "user",
                    "summary",
                    "assignments?srs_stages=0&hidden=false",
                    "assignments?srs_stages=1,2,3,4&hidden=false",
                    "assignments?srs_stages=5,6&hidden=false",
                    "assignments?srs_stages=7&hidden=false",
                    "assignments?srs_stages=8&hidden=false",
                    "assignments?srs_stages=9&hidden=false",
                    // The level history, then the recall pages, then the worst three's characters in
                    // the order the ranking put them — one request each, not one per subject.
                    "level_progressions",
                    "review_statistics?hidden=false",
                    "subjects?ids=11,12,10"
                ),
                api.requests
            )

            val available = result as WaniKaniStatsResult.Available
            assertFalse(available.cached)
            assertFalse(available.stale)
            val snapshot = available.snapshot
            assertEquals("reader", snapshot.username)
            assertEquals(12, snapshot.level)
            assertEquals(60, snapshot.maxLevelGranted)
            assertEquals("recurring", snapshot.subscriptionType)

            // The summary's first bucket is "available now"; its buckets are the next 24 hours.
            assertEquals(3, snapshot.dueNow)
            assertEquals(25, snapshot.forecast.size)
            assertEquals(3, snapshot.forecast.first().count)
            assertEquals(Instant.parse(NEXT_REVIEWS_AT).toEpochMilli(), snapshot.nextReviewAt)

            // One entry per bucket, in WaniKani's own names, counted from `total_count`.
            assertEquals(STAGE_BUCKETS.map { it.key }, snapshot.stages.map { it.key })
            assertEquals(
                listOf("Not started", "Apprentice", "Guru", "Master", "Enlightened", "Burned"),
                snapshot.stages.map { it.label }
            )
            assertEquals(listOf(0, 40, 20, 7, 5, 200), snapshot.stages.map { it.count })
            assertEquals(now, snapshot.fetchedAt)
        }

    @Test
    fun `the level history carries every attempt with its own id and timestamps`() = runBlocking {
        val api = StatsApi()
        val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

        // Three rows: level 1 passed, level 2 abandoned by a reset, and the level the reset put the
        // reader back on — two attempts at one level, told apart by their ids, with the abandoned one
        // kept rather than dropped.
        assertEquals(
            listOf(
                WaniKaniLevelProgression(
                    id = 100,
                    level = 1,
                    startedAt = Instant.parse("2017-09-05T23:41:28.980679Z").toEpochMilli(),
                    passedAt = Instant.parse("2017-09-07T17:14:14.491889Z").toEpochMilli(),
                    abandonedAt = null
                ),
                WaniKaniLevelProgression(
                    id = 101,
                    level = 2,
                    startedAt = Instant.parse("2017-09-07T18:00:00.000000Z").toEpochMilli(),
                    passedAt = null,
                    abandonedAt = Instant.parse("2017-09-20T08:00:00.000000Z").toEpochMilli()
                ),
                // A row whose timestamps are all null, which is the level the reader is on now.
                WaniKaniLevelProgression(id = 102, level = 2, startedAt = null, passedAt = null, abandonedAt = null)
            ),
            snapshot.levelHistory
        )
    }

    @Test
    fun `an account with no level progressions has an empty history rather than a missing one`() =
        runBlocking {
            val api = StatsApi()
            api.levelRecords = emptyList()
            val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

            assertEquals(emptyList<WaniKaniLevelProgression>(), snapshot.levelHistory)
            assertEquals(12, snapshot.level)
        }

    @Test
    fun `a level history read that fails leaves the section null and the rest of the snapshot alone`() =
        runBlocking {
            val api = StatsApi()
            api.failOn = "level_progressions"
            val available = stats(api).read() as WaniKaniStatsResult.Available

            // The sections the dashboard cannot do without are there, and so is the recall section:
            // the history is the only part missing.
            assertNull(available.snapshot.levelHistory)
            assertNotNull(available.snapshot.recall)
            assertEquals(12, available.snapshot.level)
            assertEquals(listOf(0, 40, 20, 7, 5, 200), available.snapshot.stages.map { it.count })

            // A failed section is not remembered as a failure: the next fresh read asks again.
            api.failOn = null
            now += WaniKaniStats.STATS_TTL_MS + 1
            val next = stats(api).read() as WaniKaniStatsResult.Available
            assertNotNull(next.snapshot.levelHistory)
        }

    @Test
    fun `a history page cap reached with another page offered is null rather than a short history`() =
        runBlocking {
            val api = StatsApi()
            api.levelPages = WaniKaniStats.LEVEL_MAX_PAGES + 1
            val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

            val pages = api.requests.filter { it.startsWith("level_progressions") }
            assertEquals(WaniKaniStats.LEVEL_MAX_PAGES, pages.size)
            // Five pages of rows arrived and a sixth was offered: a history over five of them would
            // read as the account's whole history, so there is no history instead.
            assertNull(snapshot.levelHistory)
            assertNotNull(snapshot.recall)
        }

    @Test
    fun `a level history that is not the collection is null rather than empty`() = runBlocking {
        val api = StatsApi()
        api.levelBody = """{"object":"collection","url":"https://api.wanikani.com/v2/level_progressions"}"""
        val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

        // An empty list is an account with no progressions; a body with no rows at all is an answer
        // this client cannot read, and the two must not look the same to a dashboard.
        assertNull(snapshot.levelHistory)
    }

    @Test
    fun `a body that does not say how it is paged is null, not a collection that ended`() = runBlocking {
        // A collection with no `pages` object at all. Reading that as "the last page" would publish
        // the pages that happened to arrive as the account's overall accuracy.
        val paged = StatsApi()
        paged.reviewBody = """{"object":"collection","url":"https://api.wanikani.com/v2/review_statistics",
            "total_count":4,"data":[${defaultReviewRecords().joinToString(",")}]}"""
        assertNull(freshSnapshot(paged).recall)

        // A `pages` object with no `next_url`: the collection does not say whether it ended either.
        val unfinished = StatsApi()
        unfinished.reviewBody = """{"object":"collection","url":"https://api.wanikani.com/v2/review_statistics",
            "pages":{"per_page":500,"previous_url":null},"total_count":4,
            "data":[${defaultReviewRecords().joinToString(",")}]}"""
        assertNull(freshSnapshot(unfinished).recall)

        // A `next_url` that is not this endpoint's next page: refused rather than followed, which is
        // the same answer — a read this client cannot finish.
        val elsewhere = StatsApi()
        elsewhere.reviewBody = """{"object":"collection","url":"https://api.wanikani.com/v2/review_statistics",
            "pages":{"per_page":500,"next_url":"https://api.wanikani.com/v2/subjects?page_after_id=2",
            "previous_url":null},"total_count":4,"data":[${defaultReviewRecords().joinToString(",")}]}"""
        assertNull(freshSnapshot(elsewhere).recall)

        // And the level history read is held to the same rule, with the rest of the snapshot intact.
        val history = StatsApi()
        history.levelBody = """{"object":"collection","url":"https://api.wanikani.com/v2/level_progressions",
            "pages":{"per_page":500,"previous_url":null},"total_count":3,
            "data":[${defaultLevelRecords().joinToString(",")}]}"""
        val snapshot = freshSnapshot(history)
        assertNull(snapshot.levelHistory)
        assertNotNull(snapshot.recall)
        assertEquals(12, snapshot.level)
    }

    @Test
    fun `a row this client cannot read makes the history null rather than a guess`() = runBlocking {
        // A non-null `abandoned_at` that is not a timestamp: read as "not yet", it would draw an
        // abandoned attempt as the live run of its level.
        val abandoned = StatsApi()
        abandoned.levelRecords = listOf(
            levelProgression(
                id = 101,
                level = 2,
                startedAt = "2017-09-07T18:00:00.000000Z",
                passedAt = null,
                abandonedAt = "yesterday"
            )
        )
        assertNull(freshSnapshot(abandoned).levelHistory)

        // A non-null `passed_at` that is not a timestamp, which would make a finished level look
        // unfinished — the same mistake from the other side.
        val passed = StatsApi()
        passed.levelRecords = listOf(
            levelProgression(
                id = 100,
                level = 1,
                startedAt = "2017-09-05T23:41:28.980679Z",
                passedAt = "2017-13-45T99:00:00Z",
                abandonedAt = null
            )
        )
        assertNull(freshSnapshot(passed).levelHistory)

        // An id WaniKani does not give a progression, and a level outside its own 1-to-60 range.
        val unidentified = StatsApi()
        unidentified.levelRecords = listOf(
            levelProgression(id = 0, level = 1, startedAt = null, passedAt = null, abandonedAt = null)
        )
        assertNull(freshSnapshot(unidentified).levelHistory)

        val outOfRange = StatsApi()
        outOfRange.levelRecords = listOf(
            levelProgression(id = 100, level = 61, startedAt = null, passedAt = null, abandonedAt = null)
        )
        assertNull(freshSnapshot(outOfRange).levelHistory)

        // A timestamp field the API always sends, left out altogether: a record Gaten cannot read.
        val missing = StatsApi()
        missing.levelRecords = listOf(
            levelProgression(id = 100, level = 1, startedAt = null, passedAt = null, abandonedAt = null)
                .replace("\"passed_at\": null,", "")
        )
        assertNull(freshSnapshot(missing).levelHistory)

        // None of it touches the sections the dashboard cannot do without.
        val api = StatsApi()
        api.levelRecords = listOf(
            levelProgression(id = 100, level = 1, startedAt = null, passedAt = "yesterday", abandonedAt = null)
        )
        val snapshot = freshSnapshot(api)
        assertNull(snapshot.levelHistory)
        assertNotNull(snapshot.recall)
        assertEquals(listOf(0, 40, 20, 7, 5, 200), snapshot.stages.map { it.count })
    }

    /**
     * A read the cache cannot answer: the clock is moved past its five minutes first, so a test that
     * reads several fixtures in a row really asks for each of them.
     */
    private suspend fun freshSnapshot(api: WaniKaniApi): WaniKaniStatsSnapshot {
        now += WaniKaniStats.STATS_TTL_MS + 1
        return (stats(api).read() as WaniKaniStatsResult.Available).snapshot
    }

    @Test
    fun `a snapshot younger than five minutes is answered without a request`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        val first = client.read() as WaniKaniStatsResult.Available

        now += WaniKaniStats.STATS_TTL_MS - 1
        val second = client.read() as WaniKaniStatsResult.Available

        // Eight account reads, one level history and one recall page, plus the worst three's
        // characters: one run, and no second one for the cached read.
        assertEquals(11, api.requests.size)
        assertTrue(second.cached)
        assertFalse(second.stale)
        assertEquals(first.snapshot.fetchedAt, second.snapshot.fetchedAt)
    }

    @Test
    fun `an aged snapshot is read again, and a repeated read after a refresh coalesces`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        client.read()

        now += WaniKaniStats.STATS_TTL_MS + 1
        val refreshed = client.refresh() as WaniKaniStatsResult.Available
        assertEquals(22, api.requests.size)
        assertFalse(refreshed.cached)
        assertEquals(now, refreshed.snapshot.fetchedAt)

        // A second tap straight after the first run answers from it instead of spending another.
        val again = client.refresh() as WaniKaniStatsResult.Available
        assertEquals(22, api.requests.size)
        assertTrue(again.cached)
        assertEquals(now, again.snapshot.fetchedAt)
    }

    @Test
    fun `a run that fails keeps the last complete snapshot and says why`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        val first = client.read() as WaniKaniStatsResult.Available

        // The summary fails on the next run: the account's numbers are not half-updated.
        now += WaniKaniStats.STATS_TTL_MS + 1
        api.failOn = "summary"
        val second = client.read() as WaniKaniStatsResult.Available

        assertEquals(first.snapshot, second.snapshot)
        assertTrue(second.cached)
        assertTrue(second.stale)
        assertEquals(WaniKaniFailure.OFFLINE, second.failure)

        // A second failing run changes nothing either: the file still holds the complete snapshot.
        val third = client.read() as WaniKaniStatsResult.Available
        assertEquals(first.snapshot, third.snapshot)
        assertTrue(third.cached)
        assertTrue(third.stale)

        // And once WaniKani answers again, the read is a fresh one - the earlier failure left no
        // half-written snapshot behind for it to serve.
        api.failOn = null
        val fourth = client.read() as WaniKaniStatsResult.Available
        assertFalse(fourth.cached)
        assertEquals(now, fourth.snapshot.fetchedAt)
    }

    @Test
    fun `a first run that fails with nothing cached is an unreachable answer`() = runBlocking {
        val api = StatsApi()
        api.failOn = "assignments?srs_stages=5,6&hidden=false"
        val result = stats(api).read()

        assertEquals(WaniKaniStatsResult.Unreachable(WaniKaniFailure.OFFLINE), result)
    }

    @Test
    fun `a rejected token drops the snapshot with it`() = runBlocking {
        val api = StatsApi()
        val credentials = StatsCredentials(TOKEN)
        val client = stats(api, credentials)
        client.read()
        assertTrue(File(dir, "stats").listFiles()!!.isNotEmpty())

        now += WaniKaniStats.STATS_TTL_MS + 1
        api.reject = true
        assertEquals(WaniKaniStatsResult.AuthRejected, client.read())
        assertTrue(File(dir, "stats").listFiles().isNullOrEmpty())
    }

    @Test
    fun `no token is its own answer, with no request`() = runBlocking {
        val api = StatsApi()
        val result = stats(api, StatsCredentials(null)).read()

        assertEquals(WaniKaniStatsResult.NeedsToken, result)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a 304 leaves the cached part of the snapshot standing`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        val first = client.read() as WaniKaniStatsResult.Available

        // The summary has not changed since the last read: 304, so its buckets are reused, and the
        // conditional request is what the cached ETag made possible.
        now += WaniKaniStats.STATS_TTL_MS + 1
        api.unchangedOn = "summary"
        val second = client.read() as WaniKaniStatsResult.Available

        assertEquals(first.snapshot.forecast, second.snapshot.forecast)
        assertEquals(first.snapshot.dueNow, second.snapshot.dueNow)
        assertEquals(now, second.snapshot.fetchedAt)
        assertEquals(listOf(first.snapshot.nextReviewAt), listOf(second.snapshot.nextReviewAt))
        assertEquals("summary|etag-1", api.conditional["summary"])
    }

    @Test
    fun `a cached snapshot from before the new sections still answers, with them null`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        client.read()

        // Rewrite the file the way a client that had neither section would have left it: no level
        // history, and a recall section with no completeness marker — the shape of a file whose totals
        // were summed over the pages a shorter read allowed, which are not the account's overall ones.
        val file = statsFile(TOKEN)
        val root = JSONObject(file.readText())
        root.remove("levelHistory")
        root.getJSONObject("recall").remove("complete")
        root.getJSONObject("recall").remove("bySubject")
        file.writeText(root.toString())

        // The snapshot still answers, and the parts it does have are untouched: an old file is not a
        // reason to show the reader nothing.
        val cached = client.read() as WaniKaniStatsResult.Available
        assertTrue(cached.cached)
        assertFalse(cached.stale)
        assertEquals("reader", cached.snapshot.username)
        assertEquals(12, cached.snapshot.level)
        assertEquals(25, cached.snapshot.forecast.size)
        assertEquals(listOf(0, 40, 20, 7, 5, 200), cached.snapshot.stages.map { it.count })
        assertEquals(now, cached.snapshot.fetchedAt)

        // The two sections the file cannot vouch for are null rather than guessed — a truncated sum
        // must never be shown as the account's overall accuracy.
        assertNull(cached.snapshot.recall)
        assertNull(cached.snapshot.levelHistory)

        // And the next fresh read fills both in, marker and all.
        now += WaniKaniStats.STATS_TTL_MS + 1
        val fresh = client.read() as WaniKaniStatsResult.Available
        assertFalse(fresh.cached)
        assertEquals(WaniKaniRecallSide(correct = 12, total = 23), fresh.snapshot.recall!!.reading)
        assertEquals(RECALL_TYPES.map { it.key }, fresh.snapshot.recall!!.bySubject!!.map { it.key })
        assertNotNull(fresh.snapshot.levelHistory)

        // Which means the rewritten file's sections survive a round trip through the cache too: the
        // whole snapshot comes back off disk as it went in, timestamps and per-kind rows included.
        val again = client.read() as WaniKaniStatsResult.Available
        assertTrue(again.cached)
        assertEquals(fresh.snapshot.recall, again.snapshot.recall)
        assertEquals(fresh.snapshot.levelHistory, again.snapshot.levelHistory)
        assertEquals(
            listOf(
                WaniKaniRecallType(key = "radical", label = "Radicals", correct = 0, total = 0),
                WaniKaniRecallType(key = "kanji", label = "Kanji", correct = 31, total = 50),
                WaniKaniRecallType(key = "vocabulary", label = "Vocabulary", correct = 0, total = 0)
            ),
            again.snapshot.recall!!.bySubject
        )
    }

    /** The cache file one account's snapshot is written to, named the way [WaniKaniStats] names it. */
    private fun statsFile(token: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(WaniKaniStats.ACCOUNT_KEY_CHARS)
        return File(dir, "stats/$digest.json")
    }

    @Test
    fun `the recall section sums the answers and names the three worst with a sample`() = runBlocking {
        val api = StatsApi()
        val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

        // Summed over the subjects, never averaged over their percentages: 12 right out of 23
        // readings, 19 out of 27 meanings.
        val recall = snapshot.recall!!
        assertEquals(WaniKaniRecallSide(correct = 12, total = 23), recall.reading)
        assertEquals(WaniKaniRecallSide(correct = 19, total = 27), recall.meaning)

        // The worst three of the subjects with five answers or more, weakest first: the subject
        // with two answers is left out of the ranking, though its answers are in the totals above.
        // The radical among them has no characters, so its row keeps the id and nothing else.
        assertEquals(
            listOf(
                WaniKaniDifficultSubject(subjectId = 11, characters = "一", percentage = 50, attempts = 20),
                WaniKaniDifficultSubject(subjectId = 12, characters = null, percentage = 83, attempts = 8),
                WaniKaniDifficultSubject(subjectId = 10, characters = "二", percentage = 90, attempts = 20)
            ),
            recall.difficult
        )
    }

    @Test
    fun `a side the account has never been asked is null rather than zero`() = runBlocking {
        val api = StatsApi()
        // A kana vocabulary is quizzed on its meaning only, so its reading counts are all zeroes.
        api.reviewRecords = listOf(
            reviewStatistic(
                subjectId = 20,
                readingCorrect = 0,
                readingIncorrect = 0,
                meaningCorrect = 1,
                meaningIncorrect = 4,
                percentage = 20
            )
        )
        val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

        val recall = snapshot.recall!!
        assertNull(recall.reading)
        assertEquals(WaniKaniRecallSide(correct = 1, total = 5), recall.meaning)
        // Five answers at 20% is a sample, so the subject is the ranking's only entry — and the
        // subjects answer carries no characters for it, which is a null rather than a blank.
        assertEquals(
            listOf(WaniKaniDifficultSubject(subjectId = 20, characters = null, percentage = 20, attempts = 5)),
            recall.difficult
        )
    }

    @Test
    fun `a recall read that fails leaves the section null and the rest of the snapshot alone`() = runBlocking {
        val api = StatsApi()
        api.failOn = "review_statistics?hidden=false"
        val available = stats(api).read() as WaniKaniStatsResult.Available

        // Everything the dashboard cannot do without is there: only the optional section is not.
        assertNull(available.snapshot.recall)
        assertEquals(12, available.snapshot.level)
        assertEquals(25, available.snapshot.forecast.size)
        assertEquals(listOf(0, 40, 20, 7, 5, 200), available.snapshot.stages.map { it.count })
        // And no characters were asked for, because there was nothing to name.
        assertFalse(api.requests.any { it.startsWith("subjects") })

        // A failed section is not remembered as a failure: the next fresh read asks again.
        api.failOn = null
        now += WaniKaniStats.STATS_TTL_MS + 1
        val next = stats(api).read() as WaniKaniStatsResult.Available
        assertNotNull(next.snapshot.recall)
    }

    @Test
    fun `a recall read that fails part-way through its pages is null rather than a partial sum`() = runBlocking {
        val api = StatsApi()
        api.reviewPages = 3
        api.failOn = "review_statistics?page_after_id=2"
        val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

        // The first page arrived and the second did not: a total over one page would understate the
        // account's accuracy, so the section is missing instead.
        assertNull(snapshot.recall)
        assertEquals(11, api.requests.size)
    }

    @Test
    fun `the recall read sums each kind of subject, folding kana vocabulary into vocabulary`() =
        runBlocking {
            val api = StatsApi()
            // One of each kind WaniKani names, with counts that would give different answers if they
            // were averaged over the percentages instead of summed.
            api.reviewRecords = listOf(
                // A radical and a kana vocabulary are quizzed on their meanings only, so their
                // reading counts are zeroes — which is what the API itself answers for them.
                reviewStatistic(1, 0, 0, 8, 2, percentage = 70, subjectType = "radical"),
                reviewStatistic(2, 9, 1, 7, 3, percentage = 80, subjectType = "kanji"),
                reviewStatistic(3, 4, 6, 10, 0, percentage = 70, subjectType = "vocabulary"),
                reviewStatistic(4, 0, 0, 1, 9, percentage = 10, subjectType = "kana_vocabulary")
            )
            val recall = (stats(api).read() as WaniKaniStatsResult.Available).snapshot.recall!!

            // The radical's row is its meaning answers, 8 of 10. The kanji's is its readings and
            // meanings, 16 of 20. The vocabulary's is its own 14 of 20 with the kana vocabulary's 1 of
            // 10 folded in — 15 of 30 — because the two are one kind to a reader.
            assertEquals(
                listOf(
                    WaniKaniRecallType(key = "radical", label = "Radicals", correct = 8, total = 10),
                    WaniKaniRecallType(key = "kanji", label = "Kanji", correct = 16, total = 20),
                    WaniKaniRecallType(key = "vocabulary", label = "Vocabulary", correct = 15, total = 30)
                ),
                recall.bySubject
            )

            // And the overall sides are still the sums over every record: 13 of 20 readings, 26 of 40
            // meanings — 65% over the account's answers, not the 57.5% an average of the four
            // per-subject percentages would give.
            assertEquals(WaniKaniRecallSide(correct = 13, total = 20), recall.reading)
            assertEquals(WaniKaniRecallSide(correct = 26, total = 40), recall.meaning)
        }

    @Test
    fun `a kind the account has never answered is a zero row rather than a missing one`() = runBlocking {
        val api = StatsApi()
        // The default records are all kanji, so two of the three rows are zeroes.
        val recall = (stats(api).read() as WaniKaniStatsResult.Available).snapshot.recall!!

        assertEquals(
            listOf(
                WaniKaniRecallType(key = "radical", label = "Radicals", correct = 0, total = 0),
                WaniKaniRecallType(key = "kanji", label = "Kanji", correct = 31, total = 50),
                WaniKaniRecallType(key = "vocabulary", label = "Vocabulary", correct = 0, total = 0)
            ),
            recall.bySubject
        )
    }

    @Test
    fun `a record of a kind WaniKani does not name is in the totals but in no kind's row`() =
        runBlocking {
            val api = StatsApi()
            api.reviewRecords = listOf(
                reviewStatistic(1, 1, 1, 1, 1, percentage = 50, subjectType = "kanji"),
                reviewStatistic(2, 1, 1, 1, 1, percentage = 50, subjectType = "something_else")
            )
            val recall = (stats(api).read() as WaniKaniStatsResult.Available).snapshot.recall!!

            // Guessing which kind the unknown record was would put a number in a column that does not
            // mean it, so it counts towards the overall accuracy and towards no kind.
            assertEquals(WaniKaniRecallSide(correct = 2, total = 4), recall.reading)
            assertEquals(
                listOf(
                    WaniKaniRecallType(key = "radical", label = "Radicals", correct = 0, total = 0),
                    WaniKaniRecallType(key = "kanji", label = "Kanji", correct = 2, total = 4),
                    WaniKaniRecallType(key = "vocabulary", label = "Vocabulary", correct = 0, total = 0)
                ),
                recall.bySubject
            )
        }

    @Test
    fun `a record with an unreadable counter makes the recall section null, not a smaller total`() =
        runBlocking {
            // A record whose `meaning_incorrect` is missing altogether: defaulting it to zero would
            // report the account as more accurate than it is.
            val missing = StatsApi()
            missing.reviewRecords = listOf(
                reviewStatistic(1, 1, 1, 1, 1, percentage = 50)
                    .replace("\"meaning_incorrect\": 1,", "")
            )
            assertNull(freshSnapshot(missing).recall)

            // A counter that is a negative number, and one that is not a number at all.
            val negative = StatsApi()
            negative.reviewRecords = listOf(
                reviewStatistic(1, 1, 1, 1, 1, percentage = 50)
                    .replace("\"reading_correct\": 1,", "\"reading_correct\": -3,")
            )
            assertNull(freshSnapshot(negative).recall)

            val nonsense = StatsApi()
            nonsense.reviewRecords = listOf(
                reviewStatistic(1, 1, 1, 1, 1, percentage = 50)
                    .replace("\"meaning_correct\": 1,", "\"meaning_correct\": \"many\",")
            )
            assertNull(freshSnapshot(nonsense).recall)

            // A record with no subject kind at all, which would vanish from the per-kind rows.
            val kindless = StatsApi()
            kindless.reviewRecords = listOf(
                reviewStatistic(1, 1, 1, 1, 1, percentage = 50)
                    .replace("\"subject_type\": \"kanji\",", "")
            )
            assertNull(freshSnapshot(kindless).recall)

            // None of it touches the sections the dashboard cannot do without, or the other optional
            // one: only the section whose numbers cannot be totalled goes.
            val snapshot = freshSnapshot(missing)
            assertNull(snapshot.recall)
            assertNotNull(snapshot.levelHistory)
            assertEquals(12, snapshot.level)
            assertEquals(listOf(0, 40, 20, 7, 5, 200), snapshot.stages.map { it.count })
        }

    @Test
    fun `a record without a percentage is in the totals but out of the ranking`() = runBlocking {
        val api = StatsApi()
        api.reviewRecords = listOf(
            reviewStatistic(10, 2, 8, 5, 5, percentage = 50),
            reviewStatistic(11, 1, 1, 1, 1, percentage = 50)
                .replace("\"percentage_correct\": 50,", "")
        )
        val recall = freshSnapshot(api).recall!!

        // The percentage is only what the ranking needs, so both records are still counted: 3 of 12
        // readings, 6 of 12 meanings.
        assertEquals(WaniKaniRecallSide(correct = 3, total = 12), recall.reading)
        assertEquals(WaniKaniRecallSide(correct = 6, total = 12), recall.meaning)
        // And only the record that carried one can be named as weak; the other is not guessed at.
        assertEquals(listOf(10), recall.difficult.map { it.subjectId })
    }

    @Test
    fun `the recall read stops at its page cap and calls the section unavailable, not partial`() =
        runBlocking {
            val api = StatsApi()
            api.reviewPages = WaniKaniStats.RECALL_MAX_PAGES + 2
            val snapshot = (stats(api).read() as WaniKaniStatsResult.Available).snapshot

            // Twenty pages, the last cursor inside the cap: the next page the API offers is not
            // followed. What arrived would sum to a part of the account's answers, and a dashboard
            // shows this section as the account's overall accuracy, so there is no section instead.
            val pages = api.requests.filter { it.startsWith("review_statistics") }
            assertEquals(WaniKaniStats.RECALL_MAX_PAGES, pages.size)
            assertFalse(pages.any { it.endsWith("page_after_id=${WaniKaniStats.RECALL_MAX_PAGES + 1}") })
            assertNull(snapshot.recall)

            // The rest of the snapshot is untouched by it.
            assertEquals(12, snapshot.level)
            assertEquals(listOf(0, 40, 20, 7, 5, 200), snapshot.stages.map { it.count })
            assertNotNull(snapshot.levelHistory)
        }

    @Test
    fun `a different token reads a different snapshot`() = runBlocking {
        val first = stats(StatsApi(), StatsCredentials(TOKEN))
        first.read()

        val other = StatsApi()
        val second = stats(other, StatsCredentials("another-token"))
        val result = second.read() as WaniKaniStatsResult.Available

        assertFalse(result.cached)
        assertEquals(11, other.requests.size)
    }

    @Test
    fun `a rate limit holds every caller to the wait, in seconds, per account`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        val first = client.read() as WaniKaniStatsResult.Available

        now += WaniKaniStats.STATS_TTL_MS + 1
        api.limited = true
        val limited = client.read() as WaniKaniStatsResult.Available

        // The wait comes back in seconds, the snapshot stays, and the run stopped at the first 429.
        assertEquals(WaniKaniFailure.RATE_LIMITED, limited.failure)
        assertEquals(120L, limited.retryAfterSeconds)
        assertTrue(limited.stale)
        assertTrue(limited.cached)
        assertEquals(first.snapshot, limited.snapshot)
        val afterLimit = api.requests.size
        assertEquals(12, afterLimit)

        // Nothing is asked while the wait runs: not the automatic read, not the reader's own refresh.
        val blocked = client.refresh() as WaniKaniStatsResult.Available
        assertEquals(WaniKaniFailure.RATE_LIMITED, blocked.failure)
        assertEquals(120L, blocked.retryAfterSeconds)
        assertEquals(afterLimit, api.requests.size)

        // Another account's credentials are not held by this account's wait.
        val other = StatsApi()
        other.limited = true
        val otherResult = stats(other, StatsCredentials("second-token")).read()
        assertTrue(otherResult is WaniKaniStatsResult.Unreachable)
        assertEquals(1, other.requests.size)

        // When the wait is up, a read asks again.
        now += 121_000
        api.limited = false
        val resumed = client.read() as WaniKaniStatsResult.Available
        assertFalse(resumed.stale)
        assertNull(resumed.failure)
        assertTrue(api.requests.size > afterLimit)
    }

    /** The account's numbers, as the API would answer them. */
    private class StatsApi : WaniKaniApi() {

        val requests = mutableListOf<String>()
        var failOn: String? = null
        var reject = false

        /** WaniKani's rate limit, with the wait it asks for, in seconds. */
        var limited = false
        var unchangedOn: String? = null
        val conditional = mutableMapOf<String, String?>()

        /** The review statistics rows every page answers with, for a test that wants its own. */
        var reviewRecords: List<String> = defaultReviewRecords()

        /** A body to answer `GET /review_statistics` with instead of the collection of [reviewRecords]. */
        var reviewBody: String? = null

        /**
         * How many pages `GET /review_statistics` says it has. The next page is offered until the
         * last one, so a test can ask for more than the read is allowed to follow — and its cursor is
         * the page number itself, with no subject ids to keep straight.
         */
        var reviewPages = 1

        /** The level progression rows every page answers with, for a test that wants its own. */
        var levelRecords: List<String> = defaultLevelRecords()

        /** How many pages `GET /level_progressions` says it has, as [reviewPages] does for the recall. */
        var levelPages = 1

        /** A body to answer `GET /level_progressions` with instead of the collection of [levelRecords]. */
        var levelBody: String? = null

        override suspend fun getWithMeta(
            path: String,
            query: List<Pair<String, String>>,
            token: String,
            ifNoneMatch: String?
        ): WaniKaniFetchWithMeta {
            val key = if (query.isEmpty()) {
                path
            } else {
                path + query.joinToString(separator = "&", prefix = "?") { "${it.first}=${it.second}" }
            }
            requests += key
            conditional[path] = if (ifNoneMatch == null) null else "$path|$ifNoneMatch"

            if (reject) return WaniKaniFetchWithMeta.Unauthorized
            if (limited) return WaniKaniFetchWithMeta.Failed(WaniKaniFailure.RATE_LIMITED, 120)
            if (failOn == key) return WaniKaniFetchWithMeta.Failed(WaniKaniFailure.OFFLINE)
            if (unchangedOn == path) {
                return WaniKaniFetchWithMeta.NotModified(WaniKaniMeta("etag-1", null))
            }
            val meta = WaniKaniMeta("etag-1", null)
            return when (path) {
                "user" -> WaniKaniFetchWithMeta.Body(USER_JSON, meta)
                "summary" -> WaniKaniFetchWithMeta.Body(summaryJson(), meta)
                "assignments" -> WaniKaniFetchWithMeta.Body(countJson(query), meta)
                "review_statistics" -> WaniKaniFetchWithMeta.Body(reviewStatisticsJson(query), meta)
                "level_progressions" -> WaniKaniFetchWithMeta.Body(levelProgressionsJson(query), meta)
                "subjects" -> WaniKaniFetchWithMeta.Body(difficultSubjectsJson(), meta)
                else -> WaniKaniFetchWithMeta.Failed(WaniKaniFailure.SERVER)
            }
        }

        private fun reviewStatisticsJson(query: List<Pair<String, String>>): String {
            reviewBody?.let { return it }
            val page = query.firstOrNull { it.first == "page_after_id" }?.second?.toIntOrNull() ?: 1
            return reviewStatistics(reviewRecords, if (page < reviewPages) page + 1 else null)
        }

        private fun levelProgressionsJson(query: List<Pair<String, String>>): String {
            levelBody?.let { return it }
            val page = query.firstOrNull { it.first == "page_after_id" }?.second?.toIntOrNull() ?: 1
            return levelProgressions(levelRecords, if (page < levelPages) page + 1 else null)
        }

        private fun countJson(query: List<Pair<String, String>>): String {
            val stages = query.first { it.first == "srs_stages" }.second
            val count = when (stages) {
                "0" -> 0
                "1,2,3,4" -> 40
                "5,6" -> 20
                "7" -> 7
                "8" -> 5
                else -> 200
            }
            // The body a real answer carries: the count, and a page of rows this read throws away.
            return """{"object":"collection","url":"https://api.wanikani.com/v2/assignments",
                "pages":{"per_page":500,"next_url":null,"previous_url":null},
                "total_count":$count,"data_updated_at":null,
                "data":[{"id":1,"object":"assignment","data":{"srs_stage":0,"subject_type":"radical"}}]}"""
        }
    }

    /** The token store without a keystore: what the stats read takes the credential from. */
    private class StatsCredentials(private var token: String?) : WaniKaniCredentials {
        override fun hasToken(): Boolean = token != null
        override fun read(): String? = token
        override fun save(token: String): Boolean {
            this.token = token
            return true
        }

        override fun clear(): Boolean {
            token = null
            return true
        }
    }

    private companion object {
        const val TOKEN = "a-token"
        const val NEXT_REVIEWS_AT = "2018-04-11T22:00:00.000000Z"

        /** `GET /user`, with the fields the dashboard reads. */
        const val USER_JSON = """{"object":"user","url":"https://api.wanikani.com/v2/user",
            "data":{"id":"5a6a5234","username":"reader","level":12,
            "started_at":"2012-05-11T00:52:18.958466Z","current_vacation_started_at":null,
            "subscription":{"active":true,"type":"recurring","max_level_granted":60}}}"""

        /** `GET /summary`: the current hour plus the next 24, and the next review's time. */
        fun summaryJson(): String {
            val buckets = (0 until 25).joinToString(separator = ",") { hour ->
                val subjects = if (hour == 0) "1,2,3" else ""
                val at = Instant.parse("2018-04-11T21:00:00.000000Z").plusSeconds(hour * 3600L)
                """{"available_at":"$at","subject_ids":[$subjects]}"""
            }
            return """{"object":"report","url":"https://api.wanikani.com/v2/summary",
                "data_updated_at":"2018-04-11T21:00:00.000000Z",
                "data":{"lessons":[],"next_reviews_at":"$NEXT_REVIEWS_AT","reviews":[$buckets]}}"""
        }

        /**
         * The four reviewed subjects the recall read is answered with by default: three with a sample
         * worth ranking and one with only two answers, which is counted in the account's totals but
         * left out of the worst three.
         */
        fun defaultReviewRecords(): List<String> = listOf(
            reviewStatistic(10, readingCorrect = 8, readingIncorrect = 2, meaningCorrect = 9, meaningIncorrect = 1, percentage = 90),
            reviewStatistic(11, readingCorrect = 2, readingIncorrect = 8, meaningCorrect = 5, meaningIncorrect = 5, percentage = 50),
            reviewStatistic(12, readingCorrect = 1, readingIncorrect = 1, meaningCorrect = 4, meaningIncorrect = 2, percentage = 83),
            reviewStatistic(13, readingCorrect = 1, readingIncorrect = 0, meaningCorrect = 1, meaningIncorrect = 0, percentage = 100)
        )

        /**
         * One `GET /review_statistics` resource: the four counts and the rate WaniKani reports for the
         * subject, in the reference's own shape. The streaks and the timestamps Gaten does not read
         * are the reference's example values.
         */
        fun reviewStatistic(
            subjectId: Int,
            readingCorrect: Int,
            readingIncorrect: Int,
            meaningCorrect: Int,
            meaningIncorrect: Int,
            percentage: Int,
            subjectType: String = "kanji"
        ): String = """
            {
              "id": $subjectId,
              "object": "review_statistic",
              "url": "https://api.wanikani.com/v2/review_statistics/$subjectId",
              "data_updated_at": "2018-04-03T11:50:31.558505Z",
              "data": {
                "created_at": "2017-09-05T23:38:10.964821Z",
                "subject_id": $subjectId,
                "subject_type": "$subjectType",
                "meaning_correct": $meaningCorrect,
                "meaning_incorrect": $meaningIncorrect,
                "meaning_max_streak": 8,
                "meaning_current_streak": 8,
                "reading_correct": $readingCorrect,
                "reading_incorrect": $readingIncorrect,
                "reading_max_streak": 12,
                "reading_current_streak": 4,
                "percentage_correct": $percentage,
                "hidden": false
              }
            }
        """.trimIndent()

        /** A `GET /review_statistics` collection around [records], offering page [nextPage] next. */
        fun reviewStatistics(records: List<String>, nextPage: Int? = null): String {
            val nextUrl = nextPage?.let {
                "\"https://api.wanikani.com/v2/review_statistics?page_after_id=$it\""
            } ?: "null"
            return """{"object":"collection","url":"https://api.wanikani.com/v2/review_statistics",
                "pages":{"per_page":500,"next_url":$nextUrl,"previous_url":null},
                "total_count":${records.size},"data_updated_at":"2018-04-03T11:50:31.558505Z",
                "data":[${records.joinToString(",")}]}"""
        }

        /**
         * The level progressions the history read is answered with by default: a level passed, a level
         * abandoned by a reset, and the attempt the reset started — which is why two rows share a level
         * number and are told apart by their ids. The last row's timestamps are all null, as the level
         * a reader is on now answers.
         */
        fun defaultLevelRecords(): List<String> = listOf(
            levelProgression(
                id = 100,
                level = 1,
                startedAt = "2017-09-05T23:41:28.980679Z",
                passedAt = "2017-09-07T17:14:14.491889Z",
                abandonedAt = null
            ),
            levelProgression(
                id = 101,
                level = 2,
                startedAt = "2017-09-07T18:00:00.000000Z",
                passedAt = null,
                abandonedAt = "2017-09-20T08:00:00.000000Z"
            ),
            levelProgression(id = 102, level = 2, startedAt = null, passedAt = null, abandonedAt = null)
        )

        /**
         * One `GET /level_progressions` resource, in the reference's own shape: the timestamps Gaten
         * reads as parameters, the ones it does not (`created_at`, `unlocked_at`, `completed_at`) as
         * the reference's example values, and a null written as JSON null rather than a string.
         */
        fun levelProgression(
            id: Int,
            level: Int,
            startedAt: String?,
            passedAt: String?,
            abandonedAt: String?
        ): String = """
            {
              "id": $id,
              "object": "level_progression",
              "url": "https://api.wanikani.com/v2/level_progressions/$id",
              "data_updated_at": "2017-09-07T17:14:14.491889Z",
              "data": {
                "created_at": "2017-09-05T23:38:10.695133Z",
                "level": $level,
                "unlocked_at": "2017-09-05T23:38:10.695133Z",
                "started_at": ${startedAt?.let { "\"$it\"" } ?: "null"},
                "passed_at": ${passedAt?.let { "\"$it\"" } ?: "null"},
                "completed_at": null,
                "abandoned_at": ${abandonedAt?.let { "\"$it\"" } ?: "null"}
              }
            }
        """.trimIndent()

        /** A `GET /level_progressions` collection around [records], offering page [nextPage] next. */
        fun levelProgressions(records: List<String>, nextPage: Int? = null): String {
            val nextUrl = nextPage?.let {
                "\"https://api.wanikani.com/v2/level_progressions?page_after_id=$it\""
            } ?: "null"
            return """{"object":"collection","url":"https://api.wanikani.com/v2/level_progressions",
                "pages":{"per_page":500,"next_url":$nextUrl,"previous_url":null},
                "total_count":${records.size},"data_updated_at":"2017-09-07T17:14:14.491889Z",
                "data":[${records.joinToString(",")}]}"""
        }

        /**
         * `GET /subjects?ids=…`: the characters of the three difficult subjects, one of which is a
         * radical that has none at all — the case a row has to survive with its id and nothing else.
         */
        fun difficultSubjectsJson(): String = WaniKaniJson.collection(
            WaniKaniJson.kanji(id = 11, character = "一"),
            WaniKaniJson.radical(id = 12, character = null),
            WaniKaniJson.kanji(id = 10, character = "二")
        )
    }
}
