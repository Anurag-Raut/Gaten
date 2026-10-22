/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The account's own WaniKani numbers, as the dashboard shows them: the level the reader is on, the
 * reviews available now, the next hour one is due, the forecast for the next 24 hours, how the items
 * they have started are spread across the SRS, and how well their answers have gone.
 *
 * What it is, and what it deliberately is not:
 *  - **Reading only, and only the account's numbers.** It sends the stored token to
 *    `api.wanikani.com` through [WaniKaniApi] (the same allow-listed, capped, redirect-refusing
 *    client the reference content uses) and never sends anything else anywhere: no third-party
 *    service, no analytics host, no token in a log or in a name on disk.
 *  - **Eight requests, one snapshot — and one optional pair.** `GET /user` (level and subscription),
 *    `GET /summary` (what is due now, what is due next, and the 25 hourly buckets of the next day),
 *    and six count reads of `GET /assignments` — one per SRS bucket, `hidden=false`, of which only
 *    `total_count` is used and the returned page is thrown away. No assignment is ever stored: there
 *    is no row table, no database and no history. Beside those, the recall section reads
 *    `GET /review_statistics` (paged, and capped: see [readRecall]) and one `GET /subjects` for the
 *    characters of the worst three subjects — that pair is optional, in that a failure there leaves
 *    the section null and takes nothing else with it.
 *  - **A partial run is never published.** All eight answers are collected first; if any one of them
 *    fails, the previous complete snapshot stays exactly as it was (and is marked stale on the way
 *    out). A snapshot is either the whole account at one moment or the last one that was. The recall
 *    section is the one exception, because it is the one part the dashboard can draw without: it
 *    degrades to null on its own rather than failing the run.
 *  - **The counts are six snapshots, not one.** Each bucket is its own request, so they are counted a
 *    moment apart; the chart's total is their sum and nothing claims otherwise. WaniKani's own docs
 *    say only the filtered scope's count comes back, not an atomic view of the account.
 *  - **No history and no predictions.** `GET /reviews` has returned an empty dataset since April
 *    2023 (a WaniKani staff decision announced on its community forum), and nothing here pretends
 *    otherwise: no per-day heatmap, no accuracy trend, no retention curve, no completion estimate.
 *    The recall section's two accuracies come from `GET /review_statistics`, which keeps a running
 *    total per subject rather than a log of answers: they are cumulative over every answer the account
 *    has ever given and are never date-scoped, and the three difficult subjects are ranked over those
 *    same totals.
 *  - **Cached per account.** The snapshot is one small JSON file under the reference cache, keyed by
 *    a digest of the token (a different token is a different account and reads a different file), and
 *    the file carries a digest of the token too, so a stale or copied file can never answer for an
 *    account it does not belong to. `disconnect` deletes the whole cache root, snapshot included.
 *
 * Reads never throw: every outcome is a value, and the only two answers a caller has to tell apart are
 * "here are numbers" (possibly saved, possibly stale, possibly both) and "there are none, because…".
 */
internal class WaniKaniStats(
    dir: File,
    private val credentials: WaniKaniCredentials,
    private val api: WaniKaniApi = WaniKaniApi(),
    private val now: () -> Long = System::currentTimeMillis
) {

    private val store = WaniKaniStore(dir)
    private val lock = Mutex()

    /**
     * When each account may ask WaniKani again, in epoch milliseconds, after the server answered
     * `429` with a wait (`Retry-After`, or `RateLimit-Reset`). Per account, so one account's limit
     * never silences another's, and read by every caller of [read] and [refresh]: a request inside
     * the advertised wait is refused here, before a socket is touched, rather than being displayed
     * and then made anyway.
     *
     * Session-scoped on purpose: the wait is minutes, and after a restart the first read is a single
     * attempt that a limited server answers with `429` again — one request, not a loop.
     */
    private val blockedUntil = HashMap<String, Long>()

    /**
     * The snapshot the dashboard shows: the cached one while it is younger than [STATS_TTL_MS], and a
     * fresh read otherwise. A cached snapshot that cannot be refreshed is still the answer, marked
     * [WaniKaniStatsResult.Available.stale], with the failure beside it.
     */
    suspend fun read(): WaniKaniStatsResult = ask(force = false)

    /**
     * Asks WaniKani again whatever the cache says — the dashboard's own refresh action. A refresh
     * that lands moments after another run answers from that run instead of starting a second one, so
     * a double tap cannot spend two runs of eight requests.
     */
    suspend fun refresh(): WaniKaniStatsResult = ask(force = true)

    private suspend fun ask(force: Boolean): WaniKaniStatsResult = lock.withLock {
        val token = credentials.read() ?: return@withLock WaniKaniStatsResult.NeedsToken
        val key = accountKey(token)
        val cached = cached(key, token)
        // WaniKani asked for a wait and has not been asked again since: answer from what is here,
        // with the wait that is left, and spend no request.
        val waiting = blockRemaining(key)
        if (waiting != null) {
            return@withLock cached?.let {
                available(it.snapshot, cached = true, stale = true).copy(
                    failure = WaniKaniFailure.RATE_LIMITED,
                    retryAfterSeconds = waiting
                )
            } ?: WaniKaniStatsResult.Unreachable(WaniKaniFailure.RATE_LIMITED, waiting)
        }
        val age = cached?.let { now() - it.snapshot.fetchedAt }
        if (age != null && age >= 0) {
            if (!force && age < STATS_TTL_MS) return@withLock available(cached.snapshot, cached = true)
            if (force && age < REFRESH_COALESCE_MS) return@withLock available(cached.snapshot, cached = true)
        }
        when (val run = withContext(Dispatchers.IO) { readAll(token, cached) }) {
            is Run.Completed -> {
                // WaniKani answered, so any earlier wait is over.
                blockedUntil.remove(key)
                val saved = store.saveStats(key, encode(run.snapshot, run.etags, digest(token)))
                available(run.snapshot, cached = false).copy(
                    cacheFailure = if (saved) null else WaniKaniFailure.STORAGE
                )
            }

            // A token WaniKani has rejected cannot refresh this snapshot again, so it goes: leaving it
            // would show numbers beside a connect prompt, as if they were still being kept up to date.
            Run.AuthRejected -> {
                store.clearStats(key)
                WaniKaniStatsResult.AuthRejected
            }

            is Run.Unreachable -> {
                // A rate limit comes with the wait WaniKani wants, and that wait is what every
                // caller is held to from here on — seconds from the server, milliseconds here.
                if (run.failure == WaniKaniFailure.RATE_LIMITED && run.retryAfterSeconds != null) {
                    blockedUntil[key] = now() + run.retryAfterSeconds * 1000L
                }
                cached?.let {
                    available(it.snapshot, cached = true, stale = true).copy(
                        failure = run.failure,
                        retryAfterSeconds = run.retryAfterSeconds
                    )
                } ?: WaniKaniStatsResult.Unreachable(run.failure, run.retryAfterSeconds)
            }
        }
    }

    /** Seconds left before this account may ask again, or null when it may ask now. */
    private fun blockRemaining(key: String): Long? {
        val until = blockedUntil[key] ?: return null
        val left = until - now()
        if (left <= 0) {
            blockedUntil.remove(key)
            return null
        }
        return (left + 999) / 1000
    }

    /**
     * One run of the eight reads. Each part either arrives (and replaces the cached one) or comes back
     * `304` (and the cached one stands); the first failure ends the run, so the caller never sees a
     * half-updated account.
     */
    private suspend fun readAll(token: String, previous: Cached?): Run {
        val etags = HashMap<String, WaniKaniMeta>()

        // GET /user - the level, and what the subscription covers (which is the reader's own account
        // state; the entitlement gate for *content* stays where it always was, in the reference client).
        var username = previous?.snapshot?.username
        var level = previous?.snapshot?.level
        var maxLevelGranted = previous?.snapshot?.maxLevelGranted
        var subscriptionType = previous?.snapshot?.subscriptionType
        when (val section = section(token, USER, emptyList(), previous?.etag(USER))) {
            is Section.Body -> {
                val data = section.json.optJSONObject("data")
                username = data?.optString("username")?.takeIf { it.isNotEmpty() }
                level = data?.optInt("level", 0)?.takeIf { it > 0 }
                val subscription = data?.optJSONObject("subscription")
                maxLevelGranted = subscription?.optInt("max_level_granted", 0)?.takeIf { it > 0 }
                subscriptionType = subscription?.optString("type")?.takeIf { it.isNotEmpty() }
                etags[USER] = section.meta
            }

            is Section.Unchanged -> etags[USER] = section.meta
            Section.Unauthorized -> return Run.AuthRejected
            is Section.Failed -> return Run.Unreachable(section.failure, section.retryAfterSeconds)
        }
        if (username == null || level == null || maxLevelGranted == null || subscriptionType == null) {
            return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
        }

        // GET /summary - what is available now, what is next, and the next 24 hours by the hour. The
        // first bucket is the current hour's, which is "available now"; every timestamp is the top of
        // an hour, so nothing here invents a finer time than WaniKani reports.
        var dueNow = previous?.snapshot?.dueNow ?: 0
        var lessonsNow = previous?.snapshot?.lessonsNow ?: 0
        var nextReviewAt = previous?.snapshot?.nextReviewAt
        var forecast = previous?.snapshot?.forecast ?: emptyList()
        when (val section = section(token, SUMMARY, emptyList(), previous?.etag(SUMMARY))) {
            is Section.Body -> {
                val data = section.json.optJSONObject("data")
                    ?: return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
                // The lessons bucket is the same shape as the reviews one, and just as optional: an
                // account up to date with its lessons answers with an empty array, which is a count
                // of zero rather than a missing answer.
                data.optJSONArray("lessons")?.let { lessons ->
                    lessonsNow = lessons.optJSONObject(0)?.optJSONArray("subject_ids")?.length() ?: 0
                }
                val reviews = data.optJSONArray("reviews")
                    ?: return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
                val buckets = ArrayList<WaniKaniForecastBucket>(reviews.length())
                for (index in 0 until reviews.length()) {
                    val bucket = reviews.optJSONObject(index) ?: continue
                    val at = instant(bucket.optString("available_at")) ?: continue
                    buckets.add(
                        WaniKaniForecastBucket(
                            availableAt = at,
                            count = bucket.optJSONArray("subject_ids")?.length() ?: 0
                        )
                    )
                }
                if (buckets.isEmpty()) return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
                forecast = buckets
                dueNow = buckets.first().count
                nextReviewAt = if (data.isNull("next_reviews_at")) {
                    null
                } else {
                    instant(data.optString("next_reviews_at"))
                }
                etags[SUMMARY] = section.meta
            }

            is Section.Unchanged -> etags[SUMMARY] = section.meta
            Section.Unauthorized -> return Run.AuthRejected
            is Section.Failed -> return Run.Unreachable(section.failure, section.retryAfterSeconds)
        }

        // The six counts. `total_count` is the count of the whole filtered scope, not of the page, so
        // the collection's 500-row first page is read past and discarded; nothing here keeps a row.
        val stages = ArrayList<WaniKaniStageBucket>(STAGE_BUCKETS.size)
        for (bucket in STAGE_BUCKETS) {
            val query = listOf("srs_stages" to bucket.stages, "hidden" to "false")
            when (val section = section(token, ASSIGNMENTS, query, previous?.etag(bucket.key))) {
                is Section.Body -> {
                    val count = section.json.optInt("total_count", -1)
                    if (count < 0) return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
                    stages.add(WaniKaniStageBucket(bucket.key, bucket.label, count))
                    etags[bucket.key] = section.meta
                }

                is Section.Unchanged -> {
                    val cached = previous?.bucket(bucket.key)
                        ?: return Run.Unreachable(WaniKaniFailure.MALFORMED, null)
                    stages.add(WaniKaniStageBucket(bucket.key, bucket.label, cached))
                    etags[bucket.key] = section.meta
                }

                Section.Unauthorized -> return Run.AuthRejected
                is Section.Failed -> return Run.Unreachable(section.failure, section.retryAfterSeconds)
            }
        }

        // The recall section, read last and on purpose: it is the one part of the snapshot the
        // dashboard can draw without, so it must not be able to end a run early and waste the eight
        // answers above - and a run that is going to fail on one of those eight has already failed by
        // now, before this read spends up to eleven more requests on it.
        val recall = readRecall(token)

        return Run.Completed(
            snapshot = WaniKaniStatsSnapshot(
                username = username,
                level = level,
                maxLevelGranted = maxLevelGranted,
                subscriptionType = subscriptionType,
                dueNow = dueNow,
                lessonsNow = lessonsNow,
                nextReviewAt = nextReviewAt,
                forecast = forecast,
                stages = stages,
                recall = recall,
                fetchedAt = now()
            ),
            etags = etags
        )
    }

    /** One endpoint's answer: its body, a 304 that leaves the cached one standing, or a failure. */
    private suspend fun section(
        token: String,
        path: String,
        query: List<Pair<String, String>>,
        ifNoneMatch: String?
    ): Section = when (val fetch = api.getWithMeta(path, query, token, ifNoneMatch)) {
        is WaniKaniFetchWithMeta.Body -> {
            val json = try {
                JSONObject(fetch.text)
            } catch (e: JSONException) {
                null
            }
            if (json == null) Section.Failed(WaniKaniFailure.MALFORMED, null)
            else Section.Body(json, fetch.meta)
        }

        is WaniKaniFetchWithMeta.NotModified -> Section.Unchanged(fetch.meta)
        WaniKaniFetchWithMeta.Unauthorized -> Section.Unauthorized

        // These endpoints exist for every account, so a 404 is not "nothing there" but an answer Gaten
        // cannot use — the same reading the reference client gives it.
        WaniKaniFetchWithMeta.NotFound -> Section.Failed(WaniKaniFailure.SERVER, null)
        is WaniKaniFetchWithMeta.Failed -> Section.Failed(fetch.failure, fetch.retryAfterSeconds)
    }

    /**
     * The account's cumulative review accuracy, and the subjects it gets wrong most often, from
     * `GET /review_statistics?hidden=false` — one record per reviewed subject, held as running totals
     * rather than a log of answers.
     *
     * The read pages by following `pages.next_url`, and stops at [RECALL_MAX_PAGES] pages even when
     * WaniKani offers another one: the API hands a client no other bound, and an account with tens of
     * thousands of reviewed subjects must not be able to hold a dashboard refresh open for a minute.
     * At that cap the aggregate is the ratio over the pages read — the account's own answers, taken in
     * WaniKani's own order — not over every subject it has ever reviewed, and nothing here claims the
     * second thing.
     *
     * The two sides are summed, never averaged: adding up `percentage_correct` would weigh a subject
     * answered once the same as one answered a hundred times. A side the account has never been asked
     * is null rather than 0, so "nothing to divide by" and "everything wrong" stay apart.
     *
     * Null when the read could not be made at all: a failed request, a body that is not the
     * collection, or a page that failed part-way through. A sum over only the pages that did arrive
     * would quietly understate the account's accuracy, and this section is the one the dashboard can
     * draw without — the level, the due counts and the SRS spread are true either way.
     */
    private suspend fun readRecall(token: String): WaniKaniRecall? {
        var readingCorrect = 0
        var readingIncorrect = 0
        var meaningCorrect = 0
        var meaningIncorrect = 0
        val reviewed = ArrayList<RecallCandidate>()
        var query = listOf("hidden" to "false")
        var page = 0
        while (true) {
            // No `If-None-Match` on this read: the totals span every page, so a `304` on the first one
            // could not stand for the rest, and each page would need a validator of its own.
            val json = when (val section = section(token, REVIEW_STATISTICS, query, null)) {
                is Section.Body -> section.json
                else -> return null
            }
            val items = json.optJSONArray("data") ?: return null
            for (index in 0 until items.length()) {
                val data = items.optJSONObject(index)?.optJSONObject("data") ?: continue
                val subjectId = data.optInt("subject_id", 0)
                // A record without the percentage is one this ranking cannot place; its answers still
                // count towards the account's accuracy, so it is skipped, not dropped from the totals.
                val percentage = data.optInt("percentage_correct", -1)
                val reading = data.optInt("reading_correct", 0) + data.optInt("reading_incorrect", 0)
                val meaning = data.optInt("meaning_correct", 0) + data.optInt("meaning_incorrect", 0)
                readingCorrect += data.optInt("reading_correct", 0)
                readingIncorrect += data.optInt("reading_incorrect", 0)
                meaningCorrect += data.optInt("meaning_correct", 0)
                meaningIncorrect += data.optInt("meaning_incorrect", 0)
                if (subjectId > 0 && percentage >= 0) {
                    reviewed.add(RecallCandidate(subjectId, percentage, reading + meaning))
                }
            }
            page++
            val next = json.optJSONObject("pages")?.let { pages ->
                if (pages.isNull("next_url")) {
                    null
                } else {
                    pages.optString("next_url").takeIf { it.isNotEmpty() }
                }
            }
            if (next == null || page >= RECALL_MAX_PAGES) break
            query = nextPageQuery(next) ?: return null
        }

        // The worst three out of the subjects with a sample worth ranking: one bad day is not a weak
        // subject, so fewer than [RECALL_DIFFICULT_ATTEMPTS] answers is left out. Ties go to the
        // subject with more answers behind the same percentage — the more confidently bad one — and
        // then to the lower id, so the same numbers always name the same three.
        val worst = reviewed
            .filter { it.attempts >= RECALL_DIFFICULT_ATTEMPTS }
            .sortedWith(compareBy({ it.percentage }, { -it.attempts }, { it.subjectId }))
            .take(RECALL_DIFFICULT_COUNT)

        // One request for the three characters, not one each. A failed fetch leaves all three null on
        // purpose: the accuracy is what this section is for, and the id beside it still names the row.
        val characters = subjectCharacters(token, worst.map { it.subjectId })

        return WaniKaniRecall(
            reading = recallSide(readingCorrect, readingIncorrect),
            meaning = recallSide(meaningCorrect, meaningIncorrect),
            difficult = worst.map { candidate ->
                WaniKaniDifficultSubject(
                    subjectId = candidate.subjectId,
                    characters = characters[candidate.subjectId],
                    percentage = candidate.percentage,
                    attempts = candidate.attempts
                )
            }
        )
    }

    /**
     * The characters of the subjects behind [ids], by subject id, from one `GET /subjects?ids=…`
     * request — the endpoint's own `ids` filter, so three subjects cost one answer rather than three.
     *
     * Empty when there are no ids, when the request fails, or when the body is not a collection: a row
     * without a character still carries its id, its percentage and its sample size, so a missing
     * character costs this section nothing it needs. A subject that has no characters — a radical that
     * is only an image, which the API reports as `null` — is left out of the map for the same reason,
     * so a row without one reads as unknown rather than as an empty character.
     */
    private suspend fun subjectCharacters(token: String, ids: List<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val query = listOf("ids" to ids.joinToString(","))
        val json = when (val section = section(token, SUBJECTS, query, null)) {
            is Section.Body -> section.json
            else -> return emptyMap()
        }
        val items = json.optJSONArray("data") ?: return emptyMap()
        val characters = HashMap<Int, String>(items.length())
        for (index in 0 until items.length()) {
            val resource = items.optJSONObject(index) ?: continue
            val id = resource.optInt("id", 0)
            if (id <= 0) continue
            val data = resource.optJSONObject("data") ?: continue
            // `isNull` first: AOSP's org.json answers a JSON null with the literal string "null", so
            // a radical's absent `characters` would otherwise read as text that is not there.
            if (data.isNull("characters")) continue
            val text = data.optString("characters").takeIf { it.isNotEmpty() } ?: continue
            characters[id] = text
        }
        return characters
    }

    /** One side's cumulative accuracy, or null when the account has never been asked that side. */
    private fun recallSide(correct: Int, incorrect: Int): WaniKaniRecallSide? {
        val total = correct + incorrect
        return if (total <= 0) null else WaniKaniRecallSide(correct, total)
    }

    /**
     * The query of a `pages.next_url`, in the pairs [WaniKaniApi] takes, or null when the url is not
     * this endpoint's own next page.
     *
     * Only the query is taken: the request is still built from the API's own base by the client, so
     * the allow-list that governs every other read governs this one too, and a `next_url` pointing
     * anywhere unexpected is refused instead of followed. The values arrive percent-encoded and are
     * decoded here because the client encodes every value it is handed.
     */
    private fun nextPageQuery(nextUrl: String): List<Pair<String, String>>? {
        val uri = try {
            URI(nextUrl)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.path.orEmpty().endsWith("/$REVIEW_STATISTICS")) return null
        val raw = uri.rawQuery ?: return emptyList()
        return raw.split('&').mapNotNull { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) return@mapNotNull null
            URLDecoder.decode(pair.substring(0, at), "UTF-8") to
                URLDecoder.decode(pair.substring(at + 1), "UTF-8")
        }
    }

    private fun cached(key: String, token: String): Cached? {
        val text = store.stats(key) ?: return null
        return decodeCached(text, digest(token))
    }

    private fun available(
        snapshot: WaniKaniStatsSnapshot,
        cached: Boolean,
        stale: Boolean = false
    ): WaniKaniStatsResult.Available =
        WaniKaniStatsResult.Available(snapshot = snapshot, cached = cached, stale = stale)

    private fun encode(
        snapshot: WaniKaniStatsSnapshot,
        etags: Map<String, WaniKaniMeta>,
        tokenDigest: String
    ): String = JSONObject().apply {
        put(FIELD_TOKEN, tokenDigest)
        put(FIELD_FETCHED_AT, snapshot.fetchedAt)
        put("username", snapshot.username)
        put("level", snapshot.level)
        put("maxLevelGranted", snapshot.maxLevelGranted)
        put("subscriptionType", snapshot.subscriptionType)
        put("dueNow", snapshot.dueNow)
        put("lessonsNow", snapshot.lessonsNow)
        put("nextReviewAt", snapshot.nextReviewAt?.let { it } ?: JSONObject.NULL)
        put("forecast", JSONArray().apply {
            snapshot.forecast.forEach { bucket ->
                put(JSONObject().apply {
                    put("availableAt", bucket.availableAt)
                    put("count", bucket.count)
                })
            }
        })
        put("stages", JSONArray().apply {
            snapshot.stages.forEach { stage ->
                put(JSONObject().apply {
                    put("key", stage.key)
                    put("label", stage.label)
                    put("count", stage.count)
                })
            }
        })
        put("recall", snapshot.recall?.let { recallJson(it) } ?: JSONObject.NULL)
        put("etags", JSONObject().apply {
            etags.forEach { (key, meta) ->
                put(key, JSONObject().apply {
                    put("etag", meta.etag ?: JSONObject.NULL)
                    put("lastModified", meta.lastModified ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    /** The recall section as JSON, for the cached snapshot. */
    private fun recallJson(recall: WaniKaniRecall): JSONObject = JSONObject().apply {
        put("reading", recallSideJson(recall.reading))
        put("meaning", recallSideJson(recall.meaning))
        put("difficult", JSONArray().apply {
            recall.difficult.forEach { subject ->
                put(JSONObject().apply {
                    put("subjectId", subject.subjectId)
                    put("characters", subject.characters ?: JSONObject.NULL)
                    put("percentage", subject.percentage)
                    put("attempts", subject.attempts)
                })
            }
        })
    }

    /** One side of the recall section as JSON: `null` when the account has never been asked it. */
    private fun recallSideJson(side: WaniKaniRecallSide?): Any = if (side == null) {
        JSONObject.NULL
    } else {
        JSONObject().apply {
            put("correct", side.correct)
            put("total", side.total)
        }
    }

    /** The cached snapshot, or null when the file is missing, corrupt, or another account's. */
    private fun decodeCached(text: String, tokenDigest: String): Cached? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (root.optString(FIELD_TOKEN) != tokenDigest) return null
        val username = root.optString("username").takeIf { it.isNotEmpty() } ?: return null
        val forecastJson = root.optJSONArray("forecast") ?: return null
        val forecast = ArrayList<WaniKaniForecastBucket>(forecastJson.length())
        for (index in 0 until forecastJson.length()) {
            val bucket = forecastJson.optJSONObject(index) ?: continue
            val at = bucket.optLong("availableAt", 0L).takeIf { it > 0 } ?: continue
            forecast.add(WaniKaniForecastBucket(at, bucket.optInt("count", 0)))
        }
        if (forecast.isEmpty()) return null
        val stagesJson = root.optJSONArray("stages") ?: return null
        val stages = ArrayList<WaniKaniStageBucket>(stagesJson.length())
        for (index in 0 until stagesJson.length()) {
            val stage = stagesJson.optJSONObject(index) ?: continue
            val key = stage.optString("key").takeIf { it.isNotEmpty() } ?: continue
            stages.add(
                WaniKaniStageBucket(
                    key = key,
                    label = stage.optString("label").takeIf { it.isNotEmpty() } ?: key,
                    count = stage.optInt("count", 0)
                )
            )
        }
        if (stages.size != STAGE_BUCKETS.size) return null
        val snapshot = WaniKaniStatsSnapshot(
            username = username,
            level = root.optInt("level", 1),
            maxLevelGranted = root.optInt("maxLevelGranted", 3),
            subscriptionType = root.optString("subscriptionType").takeIf { it.isNotEmpty() } ?: "unknown",
            dueNow = root.optInt("dueNow", 0),
            lessonsNow = root.optInt("lessonsNow", 0),
            nextReviewAt = if (root.isNull("nextReviewAt")) null else root.optLong("nextReviewAt").takeIf { it > 0 },
            forecast = forecast,
            stages = stages,
            recall = decodeRecall(root.optJSONObject("recall")),
            fetchedAt = root.optLong(FIELD_FETCHED_AT, 0L).takeIf { it > 0 } ?: return null
        )
        val etags = HashMap<String, WaniKaniMeta>()
        root.optJSONObject("etags")?.let { object_ ->
            val keys = object_.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val meta = object_.optJSONObject(key) ?: continue
                etags[key] = WaniKaniMeta(
                    etag = if (meta.isNull("etag")) null else meta.optString("etag"),
                    lastModified = if (meta.isNull("lastModified")) null else meta.optString("lastModified")
                )
            }
        }
        return Cached(snapshot, etags)
    }

    /**
     * The recall section of a cached snapshot, or null when the file has none — either because the
     * read that wrote it could not make one, or because the file was written before this section
     * existed. The two are the same thing to a reader: nothing to show until the next fresh read,
     * which is better than refusing a snapshot whose other nine parts are all still good.
     */
    private fun decodeRecall(json: JSONObject?): WaniKaniRecall? {
        if (json == null) return null
        val difficultJson = json.optJSONArray("difficult") ?: JSONArray()
        val difficult = ArrayList<WaniKaniDifficultSubject>(difficultJson.length())
        for (index in 0 until difficultJson.length()) {
            val subject = difficultJson.optJSONObject(index) ?: continue
            val id = subject.optInt("subjectId", 0)
            if (id <= 0) continue
            val characters = if (subject.isNull("characters")) {
                null
            } else {
                subject.optString("characters").takeIf { it.isNotEmpty() }
            }
            difficult.add(
                WaniKaniDifficultSubject(
                    subjectId = id,
                    characters = characters,
                    percentage = subject.optInt("percentage", 0),
                    attempts = subject.optInt("attempts", 0)
                )
            )
        }
        return WaniKaniRecall(
            reading = decodeRecallSide(json.optJSONObject("reading")),
            meaning = decodeRecallSide(json.optJSONObject("meaning")),
            difficult = difficult
        )
    }

    /** One side of a cached recall section: null when it was stored as `null`, or cannot be trusted. */
    private fun decodeRecallSide(json: JSONObject?): WaniKaniRecallSide? {
        if (json == null) return null
        val correct = json.optInt("correct", -1)
        val total = json.optInt("total", -1)
        return if (correct < 0 || total <= 0) null else WaniKaniRecallSide(correct, total)
    }

    /** The cache file's name for the account behind [token]: a digest, never the token itself. */
    private fun accountKey(token: String): String = digest(token).take(ACCOUNT_KEY_CHARS)

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The epoch milliseconds of an ISO-8601 timestamp, or null when it is not one. */
    private fun instant(text: String): Long? = try {
        if (text.isEmpty()) null else Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /** The snapshot written under one account's key, with the per-endpoint validators beside it. */
    private class Cached(
        val snapshot: WaniKaniStatsSnapshot,
        private val etags: Map<String, WaniKaniMeta>
    ) {
        fun etag(key: String): String? = etags[key]?.etag
        fun bucket(key: String): Int? = snapshot.stages.firstOrNull { it.key == key }?.count
    }

    /** One reviewed subject's totals, before the worst three are picked out of them. */
    private class RecallCandidate(val subjectId: Int, val percentage: Int, val attempts: Int)

    /** One endpoint's answer. */
    private sealed interface Section {
        class Body(val json: JSONObject, val meta: WaniKaniMeta) : Section
        class Unchanged(val meta: WaniKaniMeta) : Section
        data object Unauthorized : Section
        class Failed(val failure: WaniKaniFailure, val retryAfterSeconds: Long?) : Section
    }

    /** One whole run of reads: every endpoint answered, or the run stopped at the first failure. */
    private sealed interface Run {
        class Completed(
            val snapshot: WaniKaniStatsSnapshot,
            val etags: Map<String, WaniKaniMeta>
        ) : Run

        data object AuthRejected : Run

        class Unreachable(
            val failure: WaniKaniFailure,
            val retryAfterSeconds: Long?
        ) : Run
    }

    internal companion object {
        /** How long a snapshot is shown without asking WaniKani again. */
        const val STATS_TTL_MS = 5 * 60 * 1000L

        /** How long a fresh run answers a manual refresh instead of starting another one. */
        const val REFRESH_COALESCE_MS = 5 * 1000L

        /** How much of the token's digest names the cache file. */
        const val ACCOUNT_KEY_CHARS = 16

        /**
         * How many pages of `GET /review_statistics` one run follows before it stops where it is.
         * Ten pages of 500 rows is the most the API will hand over in that many requests, so an
         * account with tens of thousands of reviewed subjects cannot turn one dashboard refresh into
         * an unbounded run of them.
         */
        const val RECALL_MAX_PAGES = 10

        /** How many answers a subject needs before it can be named as one of the difficult ones. */
        const val RECALL_DIFFICULT_ATTEMPTS = 5

        /** How many subjects the recall section names as the account's worst. */
        const val RECALL_DIFFICULT_COUNT = 3

        const val USER = "user"
        const val SUMMARY = "summary"
        const val ASSIGNMENTS = "assignments"
        const val REVIEW_STATISTICS = "review_statistics"
        const val SUBJECTS = "subjects"

        const val FIELD_TOKEN = "tokenDigest"
        const val FIELD_FETCHED_AT = "fetchedAt"
    }
}

/**
 * The SRS buckets the chart draws, in WaniKani's own terms.
 *
 * WaniKani's knowledge guide ("WaniKani's SRS Stages", knowledge.wanikani.com/wanikani/srs-stages/
 * — the page the API reference points at) names nine stages in five groups: Apprentice, the first
 * four stages; Guru, two; then Master, Enlightened and Burned, one each. It also says the lowest
 * stage is 1 — an item becomes Apprentice 1 when its lesson is finished — so an assignment at stage 0
 * has not had its lesson finished at all. That is why stage 0 is its own slice here, labelled
 * "Not started" rather than with an SRS name the API does not give it.
 *
 * One request per bucket: `GET /assignments` with `srs_stages` and `hidden=false`, of which only
 * `total_count` is used. The sum of the six is the chart's total; they are six counts a moment apart,
 * and no code here claims that is an atomic view.
 */
internal val STAGE_BUCKETS = listOf(
    StageBucket("stage0", "Not started", "0"),
    StageBucket("apprentice", "Apprentice", "1,2,3,4"),
    StageBucket("guru", "Guru", "5,6"),
    StageBucket("master", "Master", "7"),
    StageBucket("enlightened", "Enlightened", "8"),
    StageBucket("burned", "Burned", "9")
)

/** One bucket: the chart's key, the label WaniKani's own guide gives it, and its `srs_stages` values. */
internal data class StageBucket(val key: String, val label: String, val stages: String)

/** One hour of the review forecast, from `GET /summary`. */
internal data class WaniKaniForecastBucket(val availableAt: Long, val count: Int)

/** One SRS bucket of the account's assignments, counted with `hidden=false`. */
internal data class WaniKaniStageBucket(val key: String, val label: String, val count: Int)

/** One side of the account's reviews — its readings or its meanings — as a running total. */
internal data class WaniKaniRecallSide(val correct: Int, val total: Int)

/**
 * One subject the account gets wrong most often, out of `GET /review_statistics` and named by the
 * characters `GET /subjects` reports for it.
 */
internal data class WaniKaniDifficultSubject(
    val subjectId: Int,
    /** The character WaniKani shows for it, or null when it has none — a radical that is only an image. */
    val characters: String?,
    /** WaniKani's own cumulative accuracy for that subject, 0 through 100. */
    val percentage: Int,
    /** Every answer to that subject so far, reading and meaning together: the sample it was taken over. */
    val attempts: Int
)

/**
 * The account's review accuracy over everything it has ever answered, plus the subjects it does worst
 * on, from the paged `GET /review_statistics` read.
 *
 * Cumulative is the whole point: WaniKani keeps a running total per subject rather than a log of
 * answers, so these numbers are never date-scoped and no trend can be drawn from them. The two sides
 * are sums of the counted answers — never an average of the per-subject percentages, which would weigh
 * a subject answered once the same as one answered a hundred times.
 */
internal data class WaniKaniRecall(
    /** The readings: how many were right, out of how many times a reading was asked at all. */
    val reading: WaniKaniRecallSide?,
    /** The meanings, counted the same way and over the same reviews. */
    val meaning: WaniKaniRecallSide?,
    /** The worst three of the subjects with a sample worth ranking, weakest first. */
    val difficult: List<WaniKaniDifficultSubject>
)

/** The account's numbers at one moment, as the dashboard reads them. */
internal data class WaniKaniStatsSnapshot(
    val username: String,
    val level: Int,
    /** What the subscription covers: 3 for a free account, 60 for a paid one (the API's own values). */
    val maxLevelGranted: Int,
    /** `free`, `recurring`, `lifetime` or `unknown`, as `GET /user` reports it. */
    val subscriptionType: String,
    /** Reviews available in the current hour — the first bucket of the summary. */
    val dueNow: Int,
    /** Subjects available for lessons now — the first lessons bucket of the summary. */
    val lessonsNow: Int,
    /** When the next reviews come up, or null when the account has none scheduled. */
    val nextReviewAt: Long?,
    /** The next 24 hours by the hour: 25 buckets, the first being the current one. */
    val forecast: List<WaniKaniForecastBucket>,
    /** The SRS spread, one entry per [STAGE_BUCKETS]. */
    val stages: List<WaniKaniStageBucket>,
    /**
     * The cumulative accuracy and the subjects the account does worst on; null when that read could
     * not be made at all. The sections above are true without it, so its absence invalidates nothing.
     */
    val recall: WaniKaniRecall?,
    /** When this snapshot was read from WaniKani (epoch milliseconds). */
    val fetchedAt: Long
)

/** What the dashboard's stats read answered. */
internal sealed interface WaniKaniStatsResult {

    /**
     * Numbers to show. [cached] says they came from the on-device snapshot; [stale] says a refresh was
     * attempted and failed, so they are the last complete ones (with [failure] saying why); a failure
     * can also sit beside fresh numbers as a note — a refresh error never hides the content.
     */
    data class Available(
        val snapshot: WaniKaniStatsSnapshot,
        val cached: Boolean,
        val stale: Boolean,
        val failure: WaniKaniFailure? = null,
        val retryAfterSeconds: Long? = null,
        val cacheFailure: WaniKaniFailure? = null
    ) : WaniKaniStatsResult

    /** No token is stored: the dashboard offers the WaniKani settings page. */
    data object NeedsToken : WaniKaniStatsResult

    /** WaniKani rejected the stored token: the snapshot was dropped and the token needs reconnecting. */
    data object AuthRejected : WaniKaniStatsResult

    /** Nothing could be read and there is nothing cached to fall back on. */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniStatsResult
}

/**
 * The stats client over the app's own directories: the same cache root the reference client uses
 * (`noBackupFilesDir/wanikani/cache`, which `disconnect` deletes whole) and the same Keystore
 * credential, so the token is read and used on the Kotlin side only and never reaches JS.
 */
internal fun waniKaniStats(context: Context): WaniKaniStats {
    val app = context.applicationContext
    return WaniKaniStats(File(app.noBackupFilesDir, CACHE_ROOT), KeystoreCredentials(app))
}

/** The cache root `WaniKaniRepository` builds; the stats snapshot lives inside it. */
private const val CACHE_ROOT = "wanikani/cache"
