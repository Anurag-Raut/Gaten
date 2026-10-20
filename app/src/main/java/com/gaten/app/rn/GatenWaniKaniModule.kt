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
import com.gaten.app.wanikani.WaniKaniLookup
import com.gaten.app.wanikani.WaniKaniRepository
import com.gaten.app.wanikani.WaniKaniStatsResult
import com.gaten.app.wanikani.WaniKaniSubject
import com.gaten.app.wanikani.WaniKaniTarget
import com.gaten.app.wanikani.waniKaniStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The WaniKani reference, for the RN screens.
 *
 * Everything delegates to [WaniKaniRepository] — the token lives where it always has
 * (`noBackupFilesDir/wanikani/token`), the cache beside it, and the entitlement check stays inside
 * `connect`. The surface is exactly what the Compose screens consumed: the token's state and the
 * three ways to change it, and the lookup behind a tapped character — a kanji by its character, a
 * radical by its subject id, the same targets [com.gaten.app.ui.wanikani.WaniKaniDetail] offers.
 * Vocabulary is not a target the repository knows, so there is no vocabulary lookup here either.
 *
 * Failures follow the wrapped shapes: a lookup's `Unreachable` rejects with `WK_<failure>`
 * (OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, TOO_LARGE, MALFORMED, STORAGE), and the states that are
 * answers rather than errors — no entry, restricted by the subscription, no token, a rejected
 * token — resolve as the lookup's `status`.
 */
class GatenWaniKaniModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = bridgeScope()

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
                        Arguments.createMap().apply {
                            putString("status", "failed")
                            putString("failure", result.failure.name)
                            result.retryAfterSeconds?.let { putDouble("retryAfterSeconds", it.toDouble()) }
                        }
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
