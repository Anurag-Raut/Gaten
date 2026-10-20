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
import java.time.Instant

/**
 * The stats read's decisions, against WaniKani's documented payloads: one run of eight requests, the
 * counts taken from `total_count` of the filtered scope, a snapshot cached for five minutes, a run
 * that fails keeping the last complete snapshot, a rejected token dropping it, and a `304` leaving a
 * cached part in place.
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
    fun `one run asks for the account, the summary and one count per SRS bucket`() = runBlocking {
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
                "assignments?srs_stages=9&hidden=false"
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
    fun `a snapshot younger than five minutes is answered without a request`() = runBlocking {
        val api = StatsApi()
        val client = stats(api)
        val first = client.read() as WaniKaniStatsResult.Available

        now += WaniKaniStats.STATS_TTL_MS - 1
        val second = client.read() as WaniKaniStatsResult.Available

        assertEquals(8, api.requests.size)
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
        assertEquals(16, api.requests.size)
        assertFalse(refreshed.cached)
        assertEquals(now, refreshed.snapshot.fetchedAt)

        // A second tap straight after the first run answers from it instead of spending another.
        val again = client.refresh() as WaniKaniStatsResult.Available
        assertEquals(16, api.requests.size)
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
    fun `a different token reads a different snapshot`() = runBlocking {
        val first = stats(StatsApi(), StatsCredentials(TOKEN))
        first.read()

        val other = StatsApi()
        val second = stats(other, StatsCredentials("another-token"))
        val result = second.read() as WaniKaniStatsResult.Available

        assertFalse(result.cached)
        assertEquals(8, other.requests.size)
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
        assertEquals(9, afterLimit)

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
                else -> WaniKaniFetchWithMeta.Failed(WaniKaniFailure.SERVER)
            }
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
    }
}
