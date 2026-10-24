/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The lookup itself: which level the subscription grants, what the cache already knows, and what
 * still has to be asked of WaniKani.
 *
 * The order every read follows:
 *  1. No token, no answer - [WaniKaniLookup.NeedsToken], without touching the network.
 *  2. The entitlement of the last check sets the highest level that may be shown, and it is applied
 *     *before* the cache is read, so content above it is never handed out - not even from a copy
 *     that was fetched while the subscription was still active.
 *  3. Unless a refresh was asked for, the cache answers immediately: a cached subject, forever - the
 *     content of a subject does not change - or a recent "WaniKani has no subject for this" answer,
 *     which is stamped with the day it was observed and trusted for [MISSING_TTL_DAYS]. Either way no
 *     request is made here: a tap on a kanji the reader has already seen, or already seen to be
 *     absent, costs nothing.
 *  4. Otherwise one request is made: the character's kanji by slug, or a subject by id. A kanji
 *     brings its components in a second request - bounded by its own component list - so the
 *     composition can be drawn and tapped without a call per radical. A lookup that finds nothing
 *     records that not-found answer for the same TTL, so the next tap on it is answered locally,
 *     while a found subject is cached with no expiry at all.
 *  5. A failed request falls back to the cached copy, marked stale; with nothing cached, the failure
 *     is the answer. Auth is never retried: 401 and 403 become [WaniKaniLookup.AuthRejected] so the
 *     UI can ask for the token again, and 429 is reported rather than retried, since retrying is
 *     what makes a rate limit worse.
 *
 * Every operation runs on [Dispatchers.IO] and is serialized against the others by one lock, so the
 * cache a `disconnect` has just cleared cannot be written back by a request that was already in
 * flight. Nothing here throws: the outcomes are values.
 *
 * The queues and their writes live here too, because they are the same kind of operation: a request
 * made on the app's own credential, serialized with everything else, answered with a value. They are
 * the only ones that are not the reference lookup - [reviewQueue] and [lessonsQueue] read what is
 * due now and keep none of it, [submitReview] finishes a review, and [startLesson] starts a lesson.
 * Those last two are the only things Gaten ever sends back to WaniKani.
 */
internal class WaniKaniClient(
    dir: File,
    private val credentials: WaniKaniCredentials,
    private val api: WaniKaniApi = WaniKaniApi(),
    private val now: () -> Long = System::currentTimeMillis
) {

    private val store = WaniKaniStore(dir)

    /** Whether a token is stored. Cheap enough for a screen's first composition. */
    fun isConfigured(): Boolean = credentials.hasToken()

    /**
     * Checks [token] against `GET /user` and stores it. Only the subscription's content entitlement
     * is read from the answer, and nothing is left behind when any part of it fails.
     */
    suspend fun connect(token: String): WaniKaniConnection = serialized {
        val clean = token.trim()
        if (!looksLikeToken(clean)) {
            WaniKaniConnection.InvalidToken
        } else {
            when (val check = verify(clean)) {
                is Check.Ok -> {
                    // The token goes in first, then the entitlement it was just checked for: a
                    // half-saved configuration - a stored token beside an entitlement it was not
                    // checked against - would decide what content is allowed from something never
                    // checked, so a failed write drops the token and says so. The cached subjects are
                    // left alone: they are unreachable without a token.
                    if (credentials.save(clean) && store.saveEntitlement(check.entitlement)) {
                        WaniKaniConnection.Connected(check.entitlement.maxLevelAt(now()))
                    } else {
                        credentials.clear()
                        WaniKaniConnection.Unavailable(WaniKaniFailure.STORAGE)
                    }
                }
                Check.InvalidToken -> WaniKaniConnection.InvalidToken
                is Check.Unavailable -> WaniKaniConnection.Unavailable(check.failure)
            }
        }
    }

    /** Checks the stored token again, which is also how a subscription's expiry is picked up. */
    suspend fun reconnect(): WaniKaniConnection = serialized {
        val token = credentials.read()
        if (token == null) {
            WaniKaniConnection.InvalidToken
        } else {
            when (val check = verify(token)) {
                is Check.Ok -> if (store.saveEntitlement(check.entitlement)) {
                    WaniKaniConnection.Connected(check.entitlement.maxLevelAt(now()))
                } else {
                    // The token is still good, but an entitlement that cannot be recorded would gate
                    // content from a stale one offline, so the caller is told instead.
                    WaniKaniConnection.Unavailable(WaniKaniFailure.STORAGE)
                }
                Check.InvalidToken -> WaniKaniConnection.InvalidToken
                is Check.Unavailable -> WaniKaniConnection.Unavailable(check.failure)
            }
        }
    }

    /**
     * Forgets the token and everything cached. True only when neither is left behind: the token goes
     * first, and both steps are reported rather than assumed.
     */
    suspend fun disconnect(): Boolean = serialized {
        val tokenCleared = credentials.clear()
        val cacheCleared = store.clear()
        tokenCleared && cacheCleared
    }

    /**
     * The subjects due for review right now, asked of WaniKani every time.
     *
     * Two requests: the assignments immediately available, then the subjects behind their ids. Nothing
     * is cached - a queue is a fact about this minute, and an item answered a minute ago is a stale
     * question - and the items come back in the order they came up, the earliest first, with the
     * assignment id breaking a tie so two subjects that became available at the same instant have a
     * fixed order rather than whatever order WaniKani happened to send.
     *
     * A body that is not the collection expected is [WaniKaniFailure.MALFORMED], never an empty
     * queue: an empty queue is an answer about the account, and a broken body is not one. An
     * assignment whose subject is not in the answer is left out rather than guessed at.
     */
    suspend fun reviewQueue(): WaniKaniReviewQueue = serialized {
        val token = credentials.read() ?: return@serialized WaniKaniReviewQueue.NeedsToken
        val assignments = when (
            val fetch = api.get(
                "assignments",
                listOf("immediately_available_for_review" to "true", "hidden" to "false"),
                token
            )
        ) {
            is WaniKaniFetch.Body -> decodeAssignmentCollection(fetch.text)
                ?: return@serialized WaniKaniReviewQueue.Unreachable(WaniKaniFailure.MALFORMED)

            WaniKaniFetch.Unauthorized -> return@serialized WaniKaniReviewQueue.AuthRejected
            WaniKaniFetch.NotFound -> return@serialized WaniKaniReviewQueue.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> return@serialized WaniKaniReviewQueue.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
        if (assignments.isEmpty()) return@serialized WaniKaniReviewQueue.Empty

        val ids = assignments.map { it.subjectId }.distinct()
        val subjects = when (val fetch = api.get("subjects", listOf("ids" to ids.joinToString(",")), token)) {
            is WaniKaniFetch.Body -> decodeReviewSubjectCollection(fetch.text)
                ?: return@serialized WaniKaniReviewQueue.Unreachable(WaniKaniFailure.MALFORMED)

            WaniKaniFetch.Unauthorized -> return@serialized WaniKaniReviewQueue.AuthRejected
            WaniKaniFetch.NotFound -> return@serialized WaniKaniReviewQueue.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> return@serialized WaniKaniReviewQueue.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }

        val byId = subjects.associateBy { it.subjectId }
        val items = assignments.sortedWith(compareBy({ it.availableAt }, { it.id }))
            .mapNotNull { byId[it.subjectId] }
        if (items.isEmpty()) WaniKaniReviewQueue.Empty else WaniKaniReviewQueue.Available(items)
    }

    /**
     * Sends one finished review back: how many times the meaning and the reading were answered
     * incorrectly - zero and zero for an item answered right first time - which is all WaniKani's
     * `POST /reviews` takes.
     *
     * A subject id or a count that cannot be sent is refused as [WaniKaniFailure.MALFORMED] without a
     * request: a review for subject 0, or a negative number of wrong answers, is the caller's own bug
     * and there is nothing for WaniKani to answer about it.
     */
    suspend fun submitReview(
        subjectId: Int,
        incorrectMeaningAnswers: Int,
        incorrectReadingAnswers: Int
    ): WaniKaniReviewSubmission = serialized {
        if (subjectId <= 0 || incorrectMeaningAnswers < 0 || incorrectReadingAnswers < 0) {
            return@serialized WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.MALFORMED)
        }
        val token = credentials.read() ?: return@serialized WaniKaniReviewSubmission.NeedsToken
        val body = "{\"review\":{" +
            "\"subject_id\":$subjectId," +
            "\"incorrect_meaning_answers\":$incorrectMeaningAnswers," +
            "\"incorrect_reading_answers\":$incorrectReadingAnswers}}"
        when (val fetch = api.post("reviews", body, token)) {
            is WaniKaniFetch.Body -> WaniKaniReviewSubmission.Submitted
            WaniKaniFetch.Unauthorized -> WaniKaniReviewSubmission.AuthRejected
            WaniKaniFetch.NotFound -> WaniKaniReviewSubmission.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> WaniKaniReviewSubmission.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
    }

    /**
     * The subjects waiting to be learned, asked of WaniKani every time and kept nowhere: the same two
     * requests the review queue makes, against the lessons filter, then joined and ordered the way
     * WaniKani presents them - by level, then by the subject's own lesson position.
     *
     * Subjects above the subscription's granted level are dropped rather than shown, because the API
     * rejects a lesson above it; when every waiting lesson is above it, the queue is
     * [WaniKaniLessonQueue.Locked] rather than empty. A malformed body is [WaniKaniFailure.MALFORMED],
     * never an empty queue: an empty queue is an answer about the account.
     */
    suspend fun lessonsQueue(): WaniKaniLessonQueue = serialized {
        val token = credentials.read() ?: return@serialized WaniKaniLessonQueue.NeedsToken
        val assignments = when (
            val fetch = api.get(
                "assignments",
                listOf("immediately_available_for_lessons" to "true", "hidden" to "false"),
                token
            )
        ) {
            is WaniKaniFetch.Body -> decodeAssignmentCollection(fetch.text)
                ?: return@serialized WaniKaniLessonQueue.Unreachable(WaniKaniFailure.MALFORMED)

            WaniKaniFetch.Unauthorized -> return@serialized WaniKaniLessonQueue.AuthRejected
            WaniKaniFetch.NotFound -> return@serialized WaniKaniLessonQueue.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> return@serialized WaniKaniLessonQueue.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
        if (assignments.isEmpty()) return@serialized WaniKaniLessonQueue.Empty

        val assignmentIds = assignments.associate { it.subjectId to it.id }
        val subjects = when (
            val fetch = api.get("subjects", listOf("ids" to assignmentIds.keys.joinToString(",")), token)
        ) {
            is WaniKaniFetch.Body -> decodeLessonSubjectCollection(fetch.text, assignmentIds)
                ?: return@serialized WaniKaniLessonQueue.Unreachable(WaniKaniFailure.MALFORMED)

            WaniKaniFetch.Unauthorized -> return@serialized WaniKaniLessonQueue.AuthRejected
            WaniKaniFetch.NotFound -> return@serialized WaniKaniLessonQueue.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> return@serialized WaniKaniLessonQueue.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }

        val maxLevel = entitlementLevel()
        val granted = subjects.filter { it.level <= maxLevel }
        val items = granted
            .sortedWith(compareBy({ it.level }, { it.lessonPosition }, { it.assignmentId }))
        when {
            items.isNotEmpty() -> WaniKaniLessonQueue.Available(items)
            subjects.isNotEmpty() -> WaniKaniLessonQueue.Locked
            else -> WaniKaniLessonQueue.Empty
        }
    }

    /**
     * Starts one lesson: `PUT /assignments/<id>/start`, the only documented way to finish one, which
     * moves the assignment out of the lessons queue and into the review queue. A lesson quiz is never
     * sent as a review, so this is a different write from [submitReview] and takes the assignment id
     * rather than the subject id.
     *
     * An id that cannot be sent is refused as [WaniKaniFailure.MALFORMED] without a request: a lesson
     * for assignment 0 is the caller's own bug.
     */
    suspend fun startLesson(assignmentId: Int): WaniKaniLessonStart = serialized {
        if (assignmentId <= 0) return@serialized WaniKaniLessonStart.Unreachable(WaniKaniFailure.MALFORMED)
        val token = credentials.read() ?: return@serialized WaniKaniLessonStart.NeedsToken
        when (val fetch = api.put("assignments/$assignmentId/start", "{}", token)) {
            is WaniKaniFetch.Body -> WaniKaniLessonStart.Started
            WaniKaniFetch.Unauthorized -> WaniKaniLessonStart.AuthRejected
            WaniKaniFetch.NotFound -> WaniKaniLessonStart.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> WaniKaniLessonStart.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
    }

    /**
     * One subject's study material, asked of WaniKani with `GET /study_materials?subject_ids=<id>`.
     * The endpoint is a collection even for one subject, and WaniKani creates at most one record per
     * subject, so the first entry is the reader's own; an empty collection is
     * [WaniKaniStudyMaterialRead.Found] with a null material, which is the reader having no note yet
     * rather than a failure. A body that is not a collection is [WaniKaniFailure.MALFORMED].
     */
    suspend fun studyMaterial(subjectId: Int): WaniKaniStudyMaterialRead = serialized {
        if (subjectId <= 0) return@serialized WaniKaniStudyMaterialRead.Unreachable(WaniKaniFailure.MALFORMED)
        val token = credentials.read() ?: return@serialized WaniKaniStudyMaterialRead.NeedsToken
        when (
            val fetch = api.get(
                "study_materials",
                listOf("subject_ids" to subjectId.toString()),
                token
            )
        ) {
            is WaniKaniFetch.Body -> {
                val materials = decodeStudyMaterialCollection(fetch.text)
                    ?: return@serialized WaniKaniStudyMaterialRead.Unreachable(WaniKaniFailure.MALFORMED)
                WaniKaniStudyMaterialRead.Found(materials.firstOrNull())
            }

            WaniKaniFetch.Unauthorized -> WaniKaniStudyMaterialRead.AuthRejected
            WaniKaniFetch.NotFound -> WaniKaniStudyMaterialRead.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> WaniKaniStudyMaterialRead.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
    }

    /**
     * Writes a subject's notes. A subject with no material yet is created with
     * `POST /study_materials` and carries its `subject_id`; a subject that already has one is updated
     * with `PUT /study_materials/<id>`, which does not carry the subject again.
     *
     * The reader's existing [meaningSynonyms] are sent back unchanged on both paths: the update is a
     * full write of the record, so omitting them would erase personal synonyms the note editor does
     * not touch. Notes are sent as written - empty ones included, which is how a reader clears one.
     */
    suspend fun saveStudyMaterial(
        subjectId: Int,
        materialId: Int?,
        meaningNote: String,
        readingNote: String,
        meaningSynonyms: List<String>
    ): WaniKaniStudyMaterialWrite = serialized {
        if (subjectId <= 0 || (materialId != null && materialId <= 0)) {
            return@serialized WaniKaniStudyMaterialWrite.Unreachable(WaniKaniFailure.MALFORMED)
        }
        val token = credentials.read() ?: return@serialized WaniKaniStudyMaterialWrite.NeedsToken
        val material = JSONObject().apply {
            if (materialId == null) put("subject_id", subjectId)
            put("meaning_note", meaningNote)
            put("reading_note", readingNote)
            put("meaning_synonyms", JSONArray(meaningSynonyms))
        }
        val body = JSONObject().put("study_material", material).toString()
        val fetch = if (materialId == null) {
            api.post("study_materials", body, token)
        } else {
            api.put("study_materials/$materialId", body, token)
        }
        when (fetch) {
            is WaniKaniFetch.Body -> decodeStudyMaterialResource(fetch.text)
                ?.let { WaniKaniStudyMaterialWrite.Saved(it) }
                ?: WaniKaniStudyMaterialWrite.Unreachable(WaniKaniFailure.MALFORMED)

            WaniKaniFetch.Unauthorized -> WaniKaniStudyMaterialWrite.AuthRejected
            WaniKaniFetch.NotFound -> WaniKaniStudyMaterialWrite.Unreachable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> WaniKaniStudyMaterialWrite.Unreachable(
                fetch.failure,
                fetch.retryAfterSeconds
            )
        }
    }

    /** The content behind one tap, from the cache when it is there and from WaniKani when it is not. */
    suspend fun lookup(target: WaniKaniTarget, refresh: Boolean): WaniKaniLookup = serialized {
        val token = credentials.read() ?: return@serialized WaniKaniLookup.NeedsToken
        val maxLevel = entitlementLevel()
        when (target) {
            is WaniKaniTarget.Kanji ->
                if (isSingleIdeograph(target.character)) lookupKanji(target.character, token, maxLevel, refresh)
                else WaniKaniLookup.NoEntry
            is WaniKaniTarget.Radical -> lookupRadical(target.id, token, maxLevel, refresh)
        }
    }

    /**
     * The kanji of [character], by slug: `types=kanji` plus `slugs=<character>` returns at most the
     * one subject WaniKani knows that character as.
     */
    private suspend fun lookupKanji(
        character: String,
        token: String,
        maxLevel: Int,
        refresh: Boolean
    ): WaniKaniLookup {
        val cachedId = store.index()[character]
        if (!refresh) {
            if (cachedId != null) {
                val cached = store.subject(cachedId)?.let { decodeSubjectResource(it) }
                if (cached != null) return fromCache(cached, maxLevel, stale = false)
            } else if (isMissingFresh(store.missingCharacter(character))) {
                // WaniKani had no subject for this character when it was last asked, and that answer
                // is still young enough to trust: tapping it again costs no request.
                return WaniKaniLookup.NoEntry
            }
        }
        return when (val fetch = api.get("subjects", listOf("types" to "kanji", "slugs" to character), token)) {
            is WaniKaniFetch.Body -> {
                val subjects = decodeSubjectCollection(fetch.text)
                if (subjects == null) {
                    // A body that is not a collection is a failed answer, not "WaniKani has no such
                    // kanji": the reader has to be told to try again, not that nothing exists.
                    fromCacheOrFailure(cachedId, maxLevel, WaniKaniFailure.MALFORMED)
                } else {
                    val kanji = subjects.firstOrNull {
                        it.subject is WaniKaniSubject.Kanji && it.subject.character == character
                    }
                    when {
                        kanji == null -> {
                            store.putMissingCharacter(character, today())
                            WaniKaniLookup.NoEntry
                        }
                        kanji.level > maxLevel -> WaniKaniLookup.Restricted
                        else -> {
                            val writes = CacheWrites()
                            cache(kanji, writes)
                            refreshComponents(kanji, token, writes, refresh)
                            WaniKaniLookup.Available(
                                render(kanji, refetchImages = true, maxLevel, writes),
                                cached = false,
                                stale = false,
                                cacheFailure = writes.failure
                            )
                        }
                    }
                }
            }
            WaniKaniFetch.Unauthorized -> WaniKaniLookup.AuthRejected
            WaniKaniFetch.NotFound -> WaniKaniLookup.NoEntry
            is WaniKaniFetch.Failed -> fromCacheOrFailure(cachedId, maxLevel, fetch.failure)
        }
    }

    /** A subject by id: the radical behind a component of a kanji that is already on screen. */
    private suspend fun lookupRadical(id: Int, token: String, maxLevel: Int, refresh: Boolean): WaniKaniLookup {
        if (!refresh) {
            val cached = store.subject(id)?.let { decodeSubjectResource(it) }
            if (cached != null) return fromCache(cached, maxLevel, stale = false)
            if (isMissingFresh(store.missingSubject(id))) return WaniKaniLookup.NoEntry
        }
        return when (val fetch = api.get("subjects/$id", emptyList(), token)) {
            is WaniKaniFetch.Body -> when (val body = decodeSubjectBody(fetch.text)) {
                SubjectBody.Malformed -> fromCacheOrFailure(id, maxLevel, WaniKaniFailure.MALFORMED)
                SubjectBody.NotASubject -> {
                    store.putMissingSubject(id, today())
                    WaniKaniLookup.NoEntry
                }
                is SubjectBody.Subject -> {
                    val subject = body.decoded
                    when {
                        subject.level > maxLevel -> WaniKaniLookup.Restricted
                        else -> {
                            val writes = CacheWrites()
                            cache(subject, writes)
                            refreshComponents(subject, token, writes, refresh)
                            WaniKaniLookup.Available(
                                render(subject, refetchImages = true, maxLevel, writes),
                                cached = false,
                                stale = false,
                                cacheFailure = writes.failure
                            )
                        }
                    }
                }
            }
            WaniKaniFetch.Unauthorized -> WaniKaniLookup.AuthRejected
            WaniKaniFetch.NotFound -> {
                store.putMissingSubject(id, today())
                WaniKaniLookup.NoEntry
            }
            is WaniKaniFetch.Failed -> fromCacheOrFailure(id, maxLevel, fetch.failure)
        }
    }

    /**
     * The cached copy, when the subscription still allows it. A subject above the granted level is
     * [WaniKaniLookup.Restricted] even when it is sitting in the cache.
     */
    private suspend fun fromCache(cached: DecodedSubject, maxLevel: Int, stale: Boolean): WaniKaniLookup =
        if (cached.level > maxLevel) {
            WaniKaniLookup.Restricted
        } else {
            WaniKaniLookup.Available(
                render(cached, refetchImages = false, maxLevel, CacheWrites()),
                cached = true,
                stale = stale
            )
        }

    /**
     * The cached copy when a request failed - marked stale, and again only if the subscription still
     * allows it - or the failure itself when there is nothing cached to fall back on.
     */
    private suspend fun fromCacheOrFailure(cachedId: Int?, maxLevel: Int, failure: WaniKaniFailure): WaniKaniLookup {
        val cached = cachedId?.let { store.subject(it) }?.let { decodeSubjectResource(it) }
            ?: return WaniKaniLookup.Unreachable(failure)
        return fromCache(cached, maxLevel, stale = true)
    }

    /**
     * The highest level the account may be shown, from the entitlement of the last successful check.
     * No entitlement means no grant: an app whose cache has been cleared shows what a free account
     * may see until the user connects, never more.
     */
    private fun entitlementLevel(): Int =
        store.entitlement()?.maxLevelAt(now()) ?: WaniKaniEntitlement.FREE_LEVEL

    /** The day a not-found answer is stamped with. */
    private fun today(): Long = now() / MILLIS_PER_DAY

    /**
     * Whether a recorded not-found answer is still young enough to answer for. A stamp before the
     * epoch or in the future is not trustworthy - a hand-edited file, a clock that moved - and is
     * treated as no record at all, so the next tap asks WaniKani rather than hiding a subject that
     * may exist by then. WaniKani adds subjects over time, which is the whole reason for the TTL.
     */
    private fun isMissingFresh(epochDay: Long?): Boolean {
        if (epochDay == null) return false
        val today = today()
        return epochDay in 0..today && today - epochDay < MISSING_TTL_DAYS
    }

    /**
     * What `GET /user` says about the token and the subscription it belongs to. Nothing is written
     * here: the two callers decide what to persist, and what a failed write means.
     */
    private suspend fun verify(token: String): Check =
        when (val fetch = api.get("user", emptyList(), token)) {
            is WaniKaniFetch.Body -> {
                val entitlement = decodeEntitlement(fetch.text)
                if (entitlement == null) Check.Unavailable(WaniKaniFailure.MALFORMED) else Check.Ok(entitlement)
            }
            WaniKaniFetch.Unauthorized -> Check.InvalidToken
            WaniKaniFetch.NotFound -> Check.Unavailable(WaniKaniFailure.SERVER)
            is WaniKaniFetch.Failed -> Check.Unavailable(fetch.failure)
        }

    /** The result of checking a token, before anything has been stored. */
    private sealed interface Check {
        data class Ok(val entitlement: WaniKaniEntitlement) : Check
        data object InvalidToken : Check
        data class Unavailable(val failure: WaniKaniFailure) : Check
    }

    /**
     * Stores [decoded] and the character a kanji will be found by next time, reporting whether each
     * write reached the disk.
     *
     * What that means is the caller's decision: a subject that could not be saved is still shown, with
     * [WaniKaniLookup.Available.cacheFailure] set so the UI can say it will not be there offline,
     * while the writes that *are* the answer - the entitlement, on connect and reconnect - are
     * reported as [WaniKaniFailure.STORAGE] rather than served.
     */
    private fun cache(decoded: DecodedSubject, writes: CacheWrites) {
        writes.note(store.saveSubject(decoded.id, decoded.json))
        val subject = decoded.subject
        if (subject is WaniKaniSubject.Kanji) writes.note(store.putIndex(subject.character, decoded.id))
    }

    /**
     * Fetches the component subjects of a freshly fetched kanji in one request, so its composition can
     * be drawn and tapped without a call per radical. Bounded by the kanji's own component ids.
     *
     * On an explicit refresh every component is fetched again - not only the missing ones - since a
     * radical's name or character may have changed since it was cached. Either way the kanji is shown
     * whatever this answers.
     */
    private suspend fun refreshComponents(
        decoded: DecodedSubject,
        token: String,
        writes: CacheWrites,
        refresh: Boolean
    ) {
        val subject = decoded.subject
        if (subject !is WaniKaniSubject.Kanji) return
        val ids = subject.components.map { it.id }
        val wanted = if (refresh) ids else ids.filter { store.subject(it) == null }
        if (wanted.isEmpty()) return
        val fetch = api.get("subjects", listOf("ids" to wanted.joinToString(",")), token)
        if (fetch !is WaniKaniFetch.Body) return
        for (component in decodeSubjectCollection(fetch.text) ?: return) {
            writes.note(store.saveSubject(component.id, component.json))
        }
    }

    /**
     * The public subject for [decoded]: the image when it has no character to be drawn as, and, for a
     * kanji, its components resolved from the cache into chips.
     *
     * [refetchImages] is true only on the paths that have just talked to WaniKani - a first fetch, or
     * the refresh the user asked for - where an image is fetched again instead of being reused from
     * the cache forever; a failed fetch then keeps the cached copy. A cache read fetches nothing.
     *
     * Every write it makes is noted in [writes], so the answer can say when it could not be saved.
     */
    private suspend fun render(
        decoded: DecodedSubject,
        refetchImages: Boolean,
        maxLevel: Int,
        writes: CacheWrites
    ): WaniKaniSubject =
        when (val subject = decoded.subject) {
            is WaniKaniSubject.Radical ->
                subject.copy(svg = imageOf(subject.character, decoded.imageUrl, refetchImages, writes))
            is WaniKaniSubject.Kanji -> subject.copy(
                components = subject.components.map { component ->
                    val cached = store.subject(component.id)?.let { decodeSubjectResource(it) }
                    // A component shows its own level's name, so the subscription is checked against
                    // that subject rather than assumed from the kanji that named it: the cache could
                    // hold a component the account no longer grants, or a payload whose level differs
                    // from the one that referenced it. The bare id is still a chip to tap.
                    if (cached == null || cached.level > maxLevel) {
                        component
                    } else {
                        if (refetchImages) prefetchImage(cached, writes)
                        WaniKaniComponent(
                            id = component.id,
                            character = cached.subject.characterOrNull,
                            name = cached.subject.primaryMeaning
                        )
                    }
                }
            )
        }

    /**
     * Caches the image of a component that has no character to be drawn as, while WaniKani is being
     * talked to anyway. Nothing can use the result now - a chip is a glyph and a name - but the
     * drill-down into that radical reads the cache and nothing else, so this is what gives it a
     * picture to draw on the first tap instead of on a refresh.
     */
    private suspend fun prefetchImage(decoded: DecodedSubject, writes: CacheWrites) {
        imageOf(decoded.subject.characterOrNull, decoded.imageUrl, refetch = true, writes)
    }

    /**
     * The SVG of a subject that has no character to be drawn as, or null when it has none, has a
     * character, or is not available.
     *
     * A cache read never touches the network, whatever it finds there: only a path that has just
     * talked to WaniKani may fetch an image, and it fetches to replace the copy it already holds -
     * falling back to that copy when the fetch fails, so a picture is never lost to a bad request.
     * A fetched image is returned whether or not it could be cached.
     */
    private suspend fun imageOf(
        character: String?,
        imageUrl: String?,
        refetch: Boolean,
        writes: CacheWrites
    ): String? {
        if (character != null || imageUrl == null) return null
        val cached = store.image(imageUrl)
        if (!refetch) return cached
        val fetch = api.getImage(imageUrl)
        if (fetch !is WaniKaniFetch.Body) return cached
        writes.note(store.saveImage(imageUrl, fetch.text))
        return fetch.text
    }

    /**
     * Runs [block] on [Dispatchers.IO], holding the one lock every lookup, connect and disconnect
     * passes through. A disconnect therefore waits for the request that is in flight and then clears
     * a cache nothing can write to afterwards; the price is that two taps in a row are answered one
     * after the other, which is what they already look like to a reader.
     */
    private suspend fun <T> serialized(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        /**
         * One lock for the whole process: the repository builds a client per call, and the cache and
         * the credential they all share are what has to be protected.
         */
        val lock = Mutex()

        /**
         * How long a "WaniKani has no subject for this" answer is trusted. WaniKani keeps adding
         * subjects, so a not-found answer is a fact about the day it was observed rather than forever;
         * a cached subject never expires, since its content does not change.
         */
        const val MISSING_TTL_DAYS = 7L

        /** Milliseconds in a day: the granularity the not-found answers are stamped with. */
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * Whether [text] is a single ideographic character: exactly one code point - which may be outside the
 * basic plane, since 𠮷 is U+20BB7 and two `Char`s - and an ideograph rather than kana, a letter or
 * punctuation. Pure, so what a lookup accepts can be checked without a device.
 */
internal fun isSingleIdeograph(text: String): Boolean {
    if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return false
    return Character.isIdeographic(text.codePointAt(0))
}

/** The character a subject is drawn as, when it has one: only an image-only radical has none. */
private val WaniKaniSubject.characterOrNull: String?
    get() = when (this) {
        is WaniKaniSubject.Kanji -> character
        is WaniKaniSubject.Radical -> character
    }

/**
 * Whether every write on one lookup's path reached the disk. The answer carries the result, as
 * [WaniKaniLookup.Available.cacheFailure], so a subject that could not be saved is never promised as
 * available offline - which is the one promise the cache exists to keep.
 */
private class CacheWrites {

    private var failed = false

    fun note(saved: Boolean) {
        if (!saved) failed = true
    }

    val failure: WaniKaniFailure? get() = if (failed) WaniKaniFailure.STORAGE else null
}
