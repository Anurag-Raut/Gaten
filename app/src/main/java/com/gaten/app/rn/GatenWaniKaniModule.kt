/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.wanikani.WaniKaniConnection
import com.gaten.app.wanikani.WaniKaniFailure
import com.gaten.app.wanikani.WaniKaniLessonItem
import com.gaten.app.wanikani.WaniKaniLessonQueue
import com.gaten.app.wanikani.WaniKaniLessonStart
import com.gaten.app.wanikani.WaniKaniLearningPreferences
import com.gaten.app.wanikani.WaniKaniLookup
import com.gaten.app.wanikani.WaniKaniRepository
import com.gaten.app.wanikani.WaniKaniReviewItem
import com.gaten.app.wanikani.WaniKaniReviewQueue
import com.gaten.app.wanikani.WaniKaniReviewSubmission
import com.gaten.app.wanikani.WaniKaniStatsResult
import com.gaten.app.wanikani.WaniKaniSubject
import com.gaten.app.wanikani.WaniKaniTarget
import com.gaten.app.wanikani.readingAnswerHiragana
import com.gaten.app.wanikani.waniKaniStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The WaniKani reference, for the RN screens.
 *
 * Everything delegates to [WaniKaniRepository] — the token lives where it always has
 * (`noBackupFilesDir/wanikani/token`), the cache beside it, and the entitlement check stays inside
 * `connect`. The surface is exactly what the Compose screens consumed: the token's state and the
 * three ways to change it, and the lookup behind a tapped character — a kanji by its character, a
 * radical by its subject id, the same targets [com.gaten.app.ui.wanikani.WaniKaniDetail] offers.
 * Vocabulary is not a target the repository knows, so there is no vocabulary lookup here either.
 * Beside that are the dashboard's numbers (`stats`, `refreshStats`) and the review screen's pieces:
 * the two live queues (`reviewQueue`, `lessonsQueue`), the fold an answer is compared with
 * (`readingAnswer`), the finished review that goes back (`submitReview`) and the lesson that is
 * started (`startLesson`). Only the ids and the counts of wrong answers
 * ever cross to WaniKani; the reader's typed answers never leave the device, and the token never
 * reaches JS.
 *
 * Failures follow the wrapped shapes: a lookup's `Unreachable` rejects with `WK_<failure>`
 * (OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, TOO_LARGE, MALFORMED, STORAGE), and the states that are
 * answers rather than errors — no entry, restricted by the subscription, no token, a rejected
 * token — resolve as the lookup's `status`. The stats read and the two review calls instead resolve
 * *every* outcome as a `status`, a failure included (`{ status: "failed", failure }` with the
 * seconds to wait when WaniKani asked for one), because a rejected promise cannot carry that wait.
 */
class GatenWaniKaniModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = bridgeScope()
    private val learningPreferences = WaniKaniLearningPreferences(reactContext)
    private val lessonStartMutex = Mutex()

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        scope.cancel()
    }

    /** True when a token is stored; cheap enough to call while a screen renders. */
    @ReactMethod
    fun isConfigured(promise: Promise) {
        promise.resolve(WaniKaniRepository.isConfigured(reactApplicationContext))
    }

    /** Reads the optional daily lesson goal and this device's count of successful starts today. */
    @ReactMethod
    fun getLearningPreferences(promise: Promise) {
        try {
            promise.resolve(learningPreferences.read().toJsMap())
        } catch (e: Exception) {
            promise.reject(CODE_REPOSITORY, e.message ?: "Could not read WaniKani preferences", e)
        }
    }

    /** Saves a daily lesson goal; null means unlimited. */
    @ReactMethod
    fun setDailyLessonLimit(limit: Double?, promise: Promise) {
        try {
            val validatedLimit = limit?.let {
                require(it.isFinite() && it % 1.0 == 0.0 && it in 1.0..1000.0) {
                    "Daily lesson limit must be a whole number from 1 to 1000, or unlimited."
                }
                it.toInt()
            }
            promise.resolve(learningPreferences.setDailyLimit(validatedLimit).toJsMap())
        } catch (e: IllegalArgumentException) {
            promise.reject("WK_INVALID_DAILY_LIMIT", e.message, e)
        } catch (e: Exception) {
            promise.reject(CODE_REPOSITORY, e.message ?: "Could not save WaniKani preferences", e)
        }
    }

    /**
     * Checks [token] with WaniKani and stores it when it works — the check also refreshes the
     * subscription's entitlement.
     *
     * Resolves `{ status: "connected", maxLevelGranted }` or `{ status: "invalidToken" }`; a token
     * that could not be checked at all rejects with the connection's failure. A rejected or
     * unreachable token is not stored, exactly as the Compose settings screen left it.
     */
    @ReactMethod
    fun connect(token: String, promise: Promise) {
        scope.launch {
            try {
                when (val connection = WaniKaniRepository.connect(reactApplicationContext, token)) {
                    is WaniKaniConnection.Unavailable -> rejectFailure(promise, connection.failure)
                    else -> promise.resolve(connection.toJsMap())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /** Checks the stored token again — the "recheck subscription" action. As [connect]. */
    @ReactMethod
    fun reconnect(promise: Promise) {
        scope.launch {
            try {
                when (val connection = WaniKaniRepository.reconnect(reactApplicationContext)) {
                    is WaniKaniConnection.Unavailable -> rejectFailure(promise, connection.failure)
                    else -> promise.resolve(connection.toJsMap())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * Forgets the token and every cached subject, image and entitlement. False when something could
     * not be cleared, so the screen can say so instead of promising a clean device.
     */
    @ReactMethod
    fun disconnect(promise: Promise) {
        scope.launch {
            try {
                promise.resolve(WaniKaniRepository.disconnect(reactApplicationContext))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * The content behind the kanji [character] — one code point, possibly outside the basic plane.
     * [refresh] skips the cache, exactly as the detail screen's retry does.
     */
    @ReactMethod
    fun lookupKanji(character: String, refresh: Boolean, promise: Promise) {
        lookup(WaniKaniTarget.Kanji(character), refresh, promise)
    }

    /** The content behind the radical whose WaniKani subject id is [id]; a kanji component tapped. */
    @ReactMethod
    fun lookupRadical(id: Double, refresh: Boolean, promise: Promise) {
        lookup(WaniKaniTarget.Radical(id.toInt()), refresh, promise)
    }

    private fun lookup(target: WaniKaniTarget, refresh: Boolean, promise: Promise) {
        scope.launch {
            try {
                when (
                    val answer = WaniKaniRepository.lookup(reactApplicationContext, target, refresh)
                ) {
                    is WaniKaniLookup.Available -> promise.resolve(
                        Arguments.createMap().apply {
                            putString("status", "available")
                            putMap("subject", answer.subject.toJsMap())
                            putBoolean("cached", answer.cached)
                            putBoolean("stale", answer.stale)
                            answer.cacheFailure?.let { putString("cacheFailure", it.name) }
                        }
                    )

                    is WaniKaniLookup.NoEntry -> promise.resolve(status("noEntry"))
                    is WaniKaniLookup.Restricted -> promise.resolve(status("restricted"))
                    is WaniKaniLookup.NeedsToken -> promise.resolve(status("needsToken"))
                    is WaniKaniLookup.AuthRejected -> promise.resolve(status("authRejected"))
                    is WaniKaniLookup.Unreachable -> rejectFailure(promise, answer.failure)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * The review queue: the subjects due right now, for the review screen to ask one after another.
     *
     * Resolves `{ status: "available", items: [...] }` — one entry per due subject, the earliest
     * first, in the shape [submitReview] and [readingAnswer] work with: `characters` absent for an
     * image-only radical, `readings` empty (and `primaryReading` absent) for a subject that is not
     * quizzed on its reading. The states that are answers rather than errors resolve too:
     * `{ status: "empty" }`, `{ status: "needsToken" }`, `{ status: "authRejected" }`.
     *
     * A queue that could not be read *resolves* as `{ status: "failed", failure }`, with
     * `retryAfterSeconds` when WaniKani asked for a wait: a rejected promise cannot carry that wait,
     * and the screen shows the failure beside its own retry action.
     */
    @ReactMethod
    fun reviewQueue(promise: Promise) {
        scope.launch {
            try {
                when (val queue = WaniKaniRepository.reviewQueue(reactApplicationContext)) {
                    is WaniKaniReviewQueue.Available -> promise.resolve(
                        Arguments.createMap().apply {
                            putString("status", "available")
                            putArray(
                                "items",
                                Arguments.createArray().apply {
                                    queue.items.forEach { pushMap(it.toJsMap()) }
                                }
                            )
                        }
                    )

                    WaniKaniReviewQueue.Empty -> promise.resolve(status("empty"))
                    WaniKaniReviewQueue.NeedsToken -> promise.resolve(status("needsToken"))
                    WaniKaniReviewQueue.AuthRejected -> promise.resolve(status("authRejected"))
                    is WaniKaniReviewQueue.Unreachable -> promise.resolve(
                        unreachable(queue.failure, queue.retryAfterSeconds)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * [text] as an answer to compare with an item's `readings`: NFKC-composed, every whitespace
     * removed, katakana folded to hiragana, and a fully-ASCII romaji answer transliterated — the same
     * fold the queue's own readings were built with, so the screen can compare the two directly.
     * Pure and synchronous: no network, no dispatcher.
     */
    @ReactMethod
    fun readingAnswer(text: String, promise: Promise) {
        promise.resolve(readingAnswerHiragana(text))
    }

    /**
     * Sends one finished review back: the subject, and how many times its meaning and its reading
     * were answered incorrectly — 0 and 0 for an item answered right first time. The typed answers
     * themselves never leave the device.
     *
     * Resolves `{ status: "accepted" }`, `{ status: "needsToken" }` or `{ status: "authRejected" }`,
     * and a submission that did not go through resolves as `{ status: "failed", failure }` with
     * `retryAfterSeconds` when there is one — whether WaniKani refused the request or never answered
     * it — exactly as [reviewQueue] does.
     */
    @ReactMethod
    fun submitReview(
        subjectId: Double,
        incorrectMeaningAnswers: Double,
        incorrectReadingAnswers: Double,
        promise: Promise
    ) {
        scope.launch {
            try {
                when (
                    val submission = WaniKaniRepository.submitReview(
                        reactApplicationContext,
                        subjectId.toInt(),
                        incorrectMeaningAnswers.toInt(),
                        incorrectReadingAnswers.toInt()
                    )
                ) {
                    WaniKaniReviewSubmission.Submitted -> promise.resolve(status("accepted"))
                    WaniKaniReviewSubmission.NeedsToken -> promise.resolve(status("needsToken"))
                    WaniKaniReviewSubmission.AuthRejected -> promise.resolve(status("authRejected"))
                    is WaniKaniReviewSubmission.Unreachable -> promise.resolve(
                        unreachable(submission.failure, submission.retryAfterSeconds)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * The lesson queue: the subjects waiting to be learned, for the lesson screen to teach and quiz
     * one after another. Same shape and same states as [reviewQueue], against the lessons filter.
     */
    @ReactMethod
    fun lessonsQueue(promise: Promise) {
        scope.launch {
            try {
                when (val queue = WaniKaniRepository.lessonsQueue(reactApplicationContext)) {
                    is WaniKaniLessonQueue.Available -> promise.resolve(
                        Arguments.createMap().apply {
                            putString("status", "available")
                            putArray(
                                "items",
                                Arguments.createArray().apply {
                                    queue.items.forEach { pushMap(it.toJsMap()) }
                                }
                            )
                        }
                    )

                    WaniKaniLessonQueue.Empty -> promise.resolve(status("empty"))
                    WaniKaniLessonQueue.Locked -> promise.resolve(status("locked"))
                    WaniKaniLessonQueue.NeedsToken -> promise.resolve(status("needsToken"))
                    WaniKaniLessonQueue.AuthRejected -> promise.resolve(status("authRejected"))
                    is WaniKaniLessonQueue.Unreachable -> promise.resolve(
                        unreachable(queue.failure, queue.retryAfterSeconds)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * Starts one lesson with `PUT /assignments/<id>/start`, which moves it into the review queue.
     * Enforces the device-local daily limit before starting a new assignment. Same-day successful
     * assignment retries resolve as started without another request. Other outcomes are started,
     * dailyLimitReached (with the limit and count), needsToken, authRejected or failed.
     */
    @ReactMethod
    fun startLesson(assignmentId: Double, promise: Promise) {
        if (!assignmentId.isFinite() || assignmentId % 1.0 != 0.0 ||
            assignmentId !in 1.0..Int.MAX_VALUE.toDouble()
        ) {
            promise.reject("WK_INVALID_ASSIGNMENT_ID", "Assignment id must be a positive whole number.")
            return
        }
        val id = assignmentId.toInt()
        scope.launch {
            try {
                lessonStartMutex.withLock {
                    if (!WaniKaniRepository.isConfigured(reactApplicationContext)) {
                        promise.resolve(status("needsToken"))
                        return@withLock
                    }
                    if (learningPreferences.wasStartedToday(id)) {
                        promise.resolve(status("started"))
                        return@withLock
                    }
                    val preferences = learningPreferences.read()
                    val dailyLimit = preferences.dailyLimit
                    if (dailyLimit != null && preferences.learnedToday >= dailyLimit) {
                        promise.resolve(dailyLimitReached(dailyLimit, preferences.learnedToday))
                        return@withLock
                    }
                    when (val result = WaniKaniRepository.startLesson(reactApplicationContext, id)) {
                        WaniKaniLessonStart.Started -> {
                            learningPreferences.recordStartedAssignment(id)
                            promise.resolve(status("started"))
                        }
                        WaniKaniLessonStart.NeedsToken -> promise.resolve(status("needsToken"))
                        WaniKaniLessonStart.AuthRejected -> promise.resolve(status("authRejected"))
                        is WaniKaniLessonStart.Unreachable -> promise.resolve(
                            unreachable(result.failure, result.retryAfterSeconds)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    /**
     * The account's own numbers for the dashboard: the level, what is due now, the next review time,
     * the next 24 hours by the hour, and the SRS spread of everything the account has started.
     *
     * Resolves the cached snapshot while it is younger than five minutes and a fresh read otherwise.
     * [refreshStats] is the same read with the cache ignored — the dashboard's refresh action — and a
     * refresh that lands moments after another run answers from that run instead of spending a second
     * run of eight requests.
     *
     * A read that fails *resolves* rather than rejecting (`status: "failed"` with the failure's name
     * and, for a rate limit, the seconds to wait), because a failure here is an answer the dashboard
     * shows beside the numbers it already has, and a rejected promise cannot carry the wait.
     */
    @ReactMethod
    fun stats(promise: Promise) {
        readStats(promise) { waniKaniStats(reactApplicationContext).read() }
    }

    /** Asks WaniKani again whatever the cache says — the dashboard's refresh action. As [stats]. */
    @ReactMethod
    fun refreshStats(promise: Promise) {
        readStats(promise) { waniKaniStats(reactApplicationContext).refresh() }
    }

    private fun readStats(promise: Promise, read: suspend () -> WaniKaniStatsResult) {
        scope.launch {
            try {
                when (val result = read()) {
                    is WaniKaniStatsResult.Available -> promise.resolve(result.toJsMap())
                    WaniKaniStatsResult.NeedsToken -> promise.resolve(status("needsToken"))
                    WaniKaniStatsResult.AuthRejected -> promise.resolve(status("authRejected"))
                    is WaniKaniStatsResult.Unreachable -> promise.resolve(
                        unreachable(result.failure, result.retryAfterSeconds)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REPOSITORY, e.message ?: "WaniKani failed", e)
            }
        }
    }

    private fun WaniKaniStatsResult.Available.toJsMap(): WritableMap = Arguments.createMap().apply {
        putString("status", "available")
        putBoolean("cached", cached)
        putBoolean("stale", stale)
        failure?.let { putString("failure", it.name) }
        retryAfterSeconds?.let { putDouble("retryAfterSeconds", it.toDouble()) }
        cacheFailure?.let { putString("cacheFailure", it.name) }
        putMap(
            "snapshot",
            Arguments.createMap().apply {
                putString("username", snapshot.username)
                putInt("level", snapshot.level)
                putInt("maxLevelGranted", snapshot.maxLevelGranted)
                putString("subscriptionType", snapshot.subscriptionType)
                putInt("dueNow", snapshot.dueNow)
                putInt("lessonsNow", snapshot.lessonsNow)
                snapshot.nextReviewAt?.let { putDouble("nextReviewAt", it.toDouble()) }
                putArray(
                    "forecast",
                    Arguments.createArray().apply {
                        snapshot.forecast.forEach { bucket ->
                            pushMap(
                                Arguments.createMap().apply {
                                    putDouble("availableAt", bucket.availableAt.toDouble())
                                    putInt("count", bucket.count)
                                }
                            )
                        }
                    }
                )
                putArray(
                    "stages",
                    Arguments.createArray().apply {
                        snapshot.stages.forEach { stage ->
                            pushMap(
                                Arguments.createMap().apply {
                                    putString("key", stage.key)
                                    putString("label", stage.label)
                                    putInt("count", stage.count)
                                }
                            )
                        }
                    }
                )
                putDouble("fetchedAt", snapshot.fetchedAt.toDouble())
            }
        )
    }

    private fun status(value: String): WritableMap =
        Arguments.createMap().apply { putString("status", value) }

    private fun dailyLimitReached(limit: Int, learnedToday: Int): WritableMap =
        Arguments.createMap().apply {
            putString("status", "dailyLimitReached")
            putInt("dailyLimit", limit)
            putInt("learnedToday", learnedToday)
        }

    private fun WaniKaniLearningPreferences.Snapshot.toJsMap(): WritableMap =
        Arguments.createMap().apply {
            if (dailyLimit == null) putNull("dailyLimit") else putInt("dailyLimit", dailyLimit)
            putInt("learnedToday", learnedToday)
        }

    /**
     * A request that did not go through: the failure's name, and the wait WaniKani asked for when
     * there is one.
     */
    private fun unreachable(failure: WaniKaniFailure, retryAfterSeconds: Long?): WritableMap =
        Arguments.createMap().apply {
            putString("status", "failed")
            putString("failure", failure.name)
            retryAfterSeconds?.let { putDouble("retryAfterSeconds", it.toDouble()) }
        }

    private fun WaniKaniReviewItem.toJsMap(): WritableMap = Arguments.createMap().apply {
        putInt("subjectId", subjectId)
        putString("type", type)
        // Both are written even when they are null: the screen reads `characters` and
        // `primaryReading` as nullable rather than as possibly absent.
        putString("characters", characters)
        putArray("meanings", meanings.toJsArray())
        putString("primaryMeaning", primaryMeaning)
        putArray("readings", readings.toJsArray())
        putString("primaryReading", primaryReading)
    }

    private fun WaniKaniLessonItem.toJsMap(): WritableMap = Arguments.createMap().apply {
        putInt("assignmentId", assignmentId)
        putInt("subjectId", subjectId)
        putString("type", type)
        // Written even when null: the screen reads `characters` and `primaryReading` as nullable
        // rather than as possibly absent, exactly as the review item does.
        putString("characters", characters)
        putInt("level", level)
        putArray("meanings", meanings.toJsArray())
        putString("primaryMeaning", primaryMeaning)
        putArray("readings", readings.toJsArray())
        putString("primaryReading", primaryReading)
        putString("readingMnemonic", readingMnemonic)
        putString("meaningMnemonic", meaningMnemonic)
        putArray(
            "components",
            Arguments.createArray().apply {
                components.forEach { component ->
                    pushMap(
                        Arguments.createMap().apply {
                            putInt("id", component.id)
                            component.character?.let { putString("character", it) }
                            component.name?.let { putString("name", it) }
                        }
                    )
                }
            }
        )
        putArray(
            "contextSentences",
            Arguments.createArray().apply {
                contextSentences.forEach { sentence ->
                    pushMap(
                        Arguments.createMap().apply {
                            putString("japanese", sentence.japanese)
                            putString("english", sentence.english)
                        }
                    )
                }
            }
        )
        documentUrl?.let { putString("documentUrl", it) }
    }

    private fun WaniKaniConnection.toJsMap(): WritableMap = Arguments.createMap().apply {
        when (this@toJsMap) {
            is WaniKaniConnection.Connected -> {
                putString("status", "connected")
                putInt("maxLevelGranted", maxLevelGranted)
            }

            is WaniKaniConnection.InvalidToken -> putString("status", "invalidToken")
            is WaniKaniConnection.Unavailable -> putString("status", "unreachable")
        }
    }

    private fun WaniKaniSubject.toJsMap(): WritableMap = Arguments.createMap().apply {
        when (val subject = this@toJsMap) {
            is WaniKaniSubject.Kanji -> {
                putString("kind", "kanji")
                putInt("id", subject.id)
                putString("character", subject.character)
                putInt("level", subject.level)
                putArray("onyomi", subject.onyomi.toJsArray())
                putArray("kunyomi", subject.kunyomi.toJsArray())
                putArray(
                    "components",
                    Arguments.createArray().apply {
                        subject.components.forEach { component ->
                            pushMap(
                                Arguments.createMap().apply {
                                    putInt("id", component.id)
                                    component.character?.let { putString("character", it) }
                                    component.name?.let { putString("name", it) }
                                }
                            )
                        }
                    }
                )
                putString("readingMnemonic", subject.readingMnemonic)
            }

            is WaniKaniSubject.Radical -> {
                putString("kind", "radical")
                putInt("id", subject.id)
                subject.character?.let { putString("character", it) }
                putInt("level", subject.level)
                subject.svg?.let { putString("svg", it) }
            }
        }
        putArray("meanings", meanings.toJsArray())
        putString("primaryMeaning", primaryMeaning)
        putString("meaningMnemonic", meaningMnemonic)
        documentUrl?.let { putString("documentUrl", it) }
    }

    private fun List<String>.toJsArray() = Arguments.createArray().apply {
        forEach { pushString(it) }
    }

    private fun rejectFailure(promise: Promise, failure: WaniKaniFailure) {
        promise.reject("WK_${failure.name}", failure.name)
    }

    companion object {
        const val NAME = "GatenWaniKani"

        private const val CODE_REPOSITORY = "E_WANIKANI"
    }
}
