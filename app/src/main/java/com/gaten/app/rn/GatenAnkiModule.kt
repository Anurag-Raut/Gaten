/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.gaten.app.anki.AnkiCard
import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiDecks
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiField
import com.gaten.app.anki.AnkiGrade
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AddResult
import com.gaten.app.anki.AnkiQueue
import com.gaten.app.anki.AnkiReview
import com.gaten.app.anki.AnkiReviewCard
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.anki.AnkiSettings
import com.gaten.app.anki.AnkiStats
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.DictEntry
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The AnkiDroid half of the app, for the RN screens.
 *
 * Every method delegates to [AnkiReview], [AnkiExporter] or [AnkiPreferences] — the same calls
 * [com.gaten.app.ui.anki.AnkiReviewViewModel] makes — and maps the wrapped classes' fail-by-result
 * values onto promise rejections: an [AnkiReviewFailure] becomes the JS error code `ANKI_<NAME>`
 * (NOT_INSTALLED, PERMISSION, NOT_SET_UP, NOT_FOUND, PROVIDER, MALFORMED) with the wrapped detail
 * as the message, and a failed add carries its localised message under `E_ANKI_ADD`.
 *
 * The queue is read the way the ViewModel reads it: [nextCard] answers the card AnkiDroid has
 * scheduled (with the answer token only it accepts), [card] renders it, and [grade] sends the
 * answer for that token — a token is spent by the first grade that reaches it, so a stale screen
 * gets `ignored` rather than an error.
 */
class GatenAnkiModule(
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

    /** Whether review can run here: `notInstalled`, `needsPermission` or `ready`. */
    @ReactMethod
    fun status(promise: Promise) {
        promise.resolve(
            when (AnkiReview.status(reactApplicationContext)) {
                AnkiStatus.NotInstalled -> "notInstalled"
                AnkiStatus.NeedsPermission -> "needsPermission"
                AnkiStatus.Ready -> "ready"
            }
        )
    }

    /** The deck "Add to Anki" exports into, the deck picker's default selection. */
    @ReactMethod
    fun getConfiguredDeckName(promise: Promise) {
        promise.resolve(AnkiReview.configuredDeckName(reactApplicationContext))
    }

    /**
     * Opens AnkiDroid itself — the Blocked screen's action for a collection that is not set up, the
     * media-files note and a refused answer alike. Resolves `true` when the launch intent was
     * started, `false` when AnkiDroid is invisible (`<queries>`, Android 11+) or launches nothing:
     * the same silent failure [com.gaten.app.ui.anki.AnkiReviewScreen.openAnkiDroid] has, so the
     * screen can hide the action rather than report an error.
     */
    @ReactMethod
    fun openAnkiDroid(promise: Promise) {
        val packageName = AddContentApi.getAnkiDroidPackageName(reactApplicationContext)
        val intent = packageName?.let {
            reactApplicationContext.packageManager.getLaunchIntentForPackage(it)
        }
        if (intent == null) {
            promise.resolve(false)
            return
        }
        // From the activity the intent lands on its own task stack, the way the Compose screen
        // opened it; from the application context a new task is required at all.
        val activity = getCurrentActivity()
        intent.addFlags(if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0)
        try {
            if (activity != null) activity.startActivity(intent) else {
                reactApplicationContext.startActivity(intent)
            }
            promise.resolve(true)
        } catch (e: ActivityNotFoundException) {
            promise.resolve(false)
        }
    }

    /** Every deck AnkiDroid has, sorted the way the wrapped class sorts them; rejects on failure. */
    @ReactMethod
    fun decks(promise: Promise) {
        scope.launch {
            try {
                when (val result = AnkiReview.decks(reactApplicationContext)) {
                    is AnkiDecks.Available -> promise.resolve(result.decks.toDecksArray())
                    is AnkiDecks.Failed -> rejectFailure(promise, result.failure, result.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_PROVIDER, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * One count per Anki browser search, in order, with `null` for a search this AnkiDroid refuses.
     *
     * Read-only by construction: each search is answered from the provider's card search with an
     * `_id`-only projection, so nothing is rendered and nothing is written — no card is fetched, no
     * template is migrated, no queue token is armed and no answer is sent. A blank search, more
     * than [AnkiStats.MAX_QUERIES] searches, or one longer than [AnkiStats.MAX_QUERY_LENGTH] is
     * rejected as `E_ANKI_STATS` before AnkiDroid is asked at all.
     */
    @ReactMethod
    fun counts(queries: ReadableArray?, promise: Promise) {
        val searches = try {
            requireNotNull(queries) { "a list of searches is required" }
                .toArrayList()
                .map { element ->
                    element as? String ?: throw IllegalArgumentException("a search must be a string")
                }
        } catch (e: IllegalArgumentException) {
            promise.reject(CODE_STATS, e.message ?: "bad searches", e)
            return
        }
        scope.launch {
            try {
                when (val result = AnkiStats.counts(reactApplicationContext, searches)) {
                    is AnkiStats.Counts.Available ->
                        promise.resolve(
                            Arguments.createArray().apply {
                                result.counts.forEach { count ->
                                    if (count == null) pushNull() else pushInt(count)
                                }
                            }
                        )

                    is AnkiStats.Counts.Failed ->
                        rejectFailure(promise, result.failure, result.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                promise.reject(CODE_STATS, e.message ?: "bad searches", e)
            } catch (e: Exception) {
                promise.reject(CODE_PROVIDER, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * The card AnkiDroid has queued for [deckId].
     *
     * Resolves `{ kind: "due", token, noteId, ord, buttonLabels, mediaFiles }` — the token is the
     * only thing [grade] may answer with — or `{ kind: "empty" }` when nothing is due. Anything
     * else rejects with the queue's own failure.
     */
    @ReactMethod
    fun nextCard(deckId: Double, promise: Promise) {
        scope.launch {
            try {
                when (val queue = AnkiReview.nextCard(reactApplicationContext, deckId.toLong())) {
                    is AnkiQueue.Due -> promise.resolve(
                        Arguments.createMap().apply {
                            putString("kind", "due")
                            putDouble("token", queue.token.toDouble())
                            putDouble("noteId", queue.noteId.toDouble())
                            putInt("ord", queue.ord)
                            putArray("buttonLabels", queue.buttonLabels.toJsArray())
                            putArray("mediaFiles", queue.mediaFiles.toJsArray())
                        }
                    )

                    is AnkiQueue.Empty -> promise.resolve(
                        Arguments.createMap().apply { putString("kind", "empty") }
                    )

                    is AnkiQueue.Failed -> rejectFailure(promise, queue.failure, queue.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_PROVIDER, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * One card of the collection as AnkiDroid renders it: question and answer HTML, the note type's
     * CSS to place around them, and the note's fields in the note type's own order.
     */
    @ReactMethod
    fun card(noteId: Double, ord: Int, promise: Promise) {
        scope.launch {
            try {
                when (val card = AnkiReview.card(reactApplicationContext, noteId.toLong(), ord)) {
                    is AnkiReviewCard.Available -> promise.resolve(card.toJsMap())
                    is AnkiReviewCard.Failed -> rejectFailure(promise, card.failure, card.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_PROVIDER, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * Answers the card [token] was issued for with [ease] (1..the card's own button count), after
     * [timeTakenMs] spent on it.
     *
     * Resolves `submitted` (acknowledged — the card may still come back), `notFound` (the card is
     * gone) or `ignored` (nothing was sent: a stale screen or a second tap, not an error to
     * report). A refusal rejects with the grade's own failure.
     */
    @ReactMethod
    fun grade(token: Double, ease: Int, timeTakenMs: Double, promise: Promise) {
        scope.launch {
            try {
                when (
                    val result = AnkiReview.grade(
                        reactApplicationContext,
                        token.toLong(),
                        ease,
                        timeTakenMs.toLong()
                    )
                ) {
                    is AnkiGrade.Submitted -> promise.resolve("submitted")
                    is AnkiGrade.NotFound -> promise.resolve("notFound")
                    is AnkiGrade.Ignored -> promise.resolve("ignored")
                    is AnkiGrade.Failed -> rejectFailure(promise, result.failure, result.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_PROVIDER, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * What the customise screen can offer: every deck name and every standard note type, as
     * `{ decks: string[], models: { [id: string]: string } }`.
     */
    @ReactMethod
    fun collection(promise: Promise) {
        scope.launch {
            try {
                AnkiExporter.collection(reactApplicationContext).fold(
                    onSuccess = { collection ->
                        val models = Arguments.createMap()
                        for ((id, name) in collection.models) {
                            models.putString(id.toString(), name)
                        }
                        promise.resolve(
                            Arguments.createMap().apply {
                                putArray("decks", collection.decks.toJsArray())
                                putMap("models", models)
                            }
                        )
                    },
                    onFailure = { promise.reject(CODE_EXPORT, it.message ?: "AnkiDroid failed", it) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_EXPORT, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /** The field names of the standard note type [modelId] names, in Anki's order. */
    @ReactMethod
    fun modelFields(modelId: Double, promise: Promise) {
        scope.launch {
            try {
                AnkiExporter.modelFields(reactApplicationContext, modelId.toLong()).fold(
                    onSuccess = { promise.resolve(it.toJsArray()) },
                    onFailure = { promise.reject(CODE_EXPORT, it.message ?: "AnkiDroid failed", it) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_EXPORT, e.message ?: "AnkiDroid failed", e)
            }
        }
    }

    /**
     * Adds one note from [card] (`{ expression, reading, meaning }`) into the configured deck.
     *
     * Resolves `added` or `duplicate`; a refusal — a missing permission, a broken configuration, a
     * provider error — rejects with `E_ANKI_ADD` and the localised message the Compose sheet
     * showed.
     */
    @ReactMethod
    fun addNote(card: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val result = AnkiExporter.add(
                    reactApplicationContext,
                    AnkiCard(
                        expression = card.getString("expression").orEmpty(),
                        reading = card.getString("reading").orEmpty(),
                        meaning = card.getString("meaning").orEmpty()
                    )
                )
                when (result) {
                    is AddResult.Added -> promise.resolve("added")
                    is AddResult.Duplicate -> promise.resolve("duplicate")
                    is AddResult.Failed -> promise.reject(CODE_ADD, result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_ADD, e.message ?: "Adding the note failed", e)
            }
        }
    }

    /**
     * Hands one entry to AnkiDroid's own add screen — the fallback for a word that cannot be
     * written through the provider, because the database permission is not granted.
     *
     * `entry` is `{ primary, kana, glosses, pos }`; the shared note is built exactly the way
     * [AnkiCard.from] builds it from a dictionary entry. Resolves `true` when AnkiDroid took the
     * share, `false` when nothing on the device can handle it (the caller says so).
     */
    @ReactMethod
    fun shareToAnkiDroid(entry: ReadableMap, promise: Promise) {
        val dictEntry = DictEntry(
            id = 0,
            primary = entry.getString("primary").orEmpty(),
            kanji = emptyList(),
            kana = stringsOf(entry.getArray("kana")),
            glosses = stringsOf(entry.getArray("glosses")),
            pos = stringsOf(entry.getArray("pos")),
            common = false
        )
        promise.resolve(AnkiExporter.shareFallback(reactApplicationContext, AnkiCard.from(dictEntry)))
    }

    /**
     * Whether [card] (`{ expression, reading, meaning }`) is already in the deck an add would
     * write to, judged against the saved settings — the duplicate check `addNote` would make,
     * offered on its own so the button can open saying "already in Anki". Resolves `false` on a
     * provider failure, a broken configuration or a vanished note type, never rejects.
     */
    @ReactMethod
    fun isDuplicate(card: ReadableMap, promise: Promise) {
        scope.launch {
            val ankiCard = AnkiCard(
                expression = card.getString("expression").orEmpty(),
                reading = card.getString("reading").orEmpty(),
                meaning = card.getString("meaning").orEmpty()
            )
            promise.resolve(AnkiExporter.isDuplicate(reactApplicationContext, ankiCard))
        }
    }

    /**
     * Asks the reader for AnkiDroid's database permission, the way the Compose screens did when
     * their "Add to Anki" button was tapped under `needsPermission`. Resolves `true` when the
     * reader granted it, `false` when they did not or there is no permission-aware activity to ask
     * through. Nothing is exported on a grant — exporting stays the button's next tap.
     */
    @ReactMethod
    fun requestPermission(promise: Promise) {
        val activity = getCurrentActivity() as? PermissionAwareActivity
        if (activity == null) {
            promise.resolve(false)
            return
        }
        activity.requestPermissions(
            arrayOf(AddContentApi.READ_WRITE_PERMISSION),
            PERMISSION_REQUEST_CODE,
            object : PermissionListener {
                override fun onRequestPermissionsResult(
                    requestCode: Int,
                    permissions: Array<String>,
                    grantResults: IntArray
                ): Boolean {
                    promise.resolve(
                        grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED
                    )
                    return true
                }
            }
        )
    }

    /** The saved export settings, plus [AnkiSettings.validationError]'s verdict on them. */
    @ReactMethod
    fun getPreferences(promise: Promise) {
        promise.resolve(AnkiPreferences.load(reactApplicationContext).toJsMap())
    }

    /** The selected review decks; null means all decks for existing installs. */
    @ReactMethod
    fun getStudyDeckIds(promise: Promise) {
        scope.launch {
            try {
                val ids = AnkiPreferences.loadStudyDeckIds(reactApplicationContext)
                if (ids == null) {
                    promise.resolve(null)
                } else {
                    promise.resolve(Arguments.createArray().apply { ids.forEach { pushDouble(it.toDouble()) } })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_SETTINGS, e.message ?: "Could not read Anki study decks", e)
            }
        }
    }

    /** Saves selected review deck ids independently of the export destination. */
    @ReactMethod
    fun saveStudyDeckIds(ids: ReadableArray?, promise: Promise) {
        scope.launch {
            try {
                val selected = ids?.let { array ->
                    (0 until array.size()).map { index ->
                        require(!array.isNull(index) && array.getType(index) == ReadableType.Number) {
                            "Anki deck ids must be numbers"
                        }
                        val value = array.getDouble(index)
                        require(
                            value.isFinite() && value > 0.0 && value <= MAX_SAFE_JS_DECK_ID.toDouble() &&
                                value % 1.0 == 0.0
                        ) { "Anki deck ids must be positive safe integers" }
                        value.toLong()
                    }
                }
                AnkiPreferences.saveStudyDeckIds(reactApplicationContext, selected)
                promise.resolve(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_SETTINGS, e.message ?: "Could not save Anki study decks", e)
            }
        }
    }

    /**
     * Saves [settings], the same shape [getPreferences] answers with; the wrapped class normalises
     * the deck name and the tags on the way in. The stored values are judged by
     * [AnkiSettings.validationError] rather than replaced: an invalid configuration is saved as it
     * is and its error reported here, the way the customise screen behaves.
     */
    @ReactMethod
    fun savePreferences(settings: ReadableMap, promise: Promise) {
        val saved = AnkiSettings(
            deckName = settings.getString("deckName") ?: AnkiSettings.DEFAULT_DECK_NAME,
            tags = settings.getString("tags").orEmpty(),
            modelId = readable(settings, "modelId")?.toDouble()?.toLong(),
            modelFields = stringsOf(settings.getArray("modelFields")),
            fieldSources = stringsOf(settings.getArray("fieldSources"))
                .mapNotNull { source -> AnkiField.entries.firstOrNull { it.name == source } },
            front = settings.getString("front") ?: AnkiSettings.DEFAULT_FRONT,
            back = settings.getString("back") ?: AnkiSettings.DEFAULT_BACK,
            css = settings.getString("css") ?: AnkiSettings.DEFAULT_CSS
        )
        AnkiPreferences.save(reactApplicationContext, saved)
        promise.resolve(saved.validationError()?.name)
    }

    private fun AnkiSettings.toJsMap(): WritableMap = Arguments.createMap().apply {
        putString("deckName", deckName)
        putString("tags", tags)
        if (modelId != null) putDouble("modelId", modelId.toDouble()) else putNull("modelId")
        putArray("modelFields", modelFields.toJsArray())
        putArray("fieldSources", fieldSources.map { it.name }.toJsArray())
        putString("front", front)
        putString("back", back)
        putString("css", css)
        putString("validationError", validationError()?.name)
    }

    private fun AnkiDeck.toJsMap(): WritableMap = Arguments.createMap().apply {
        putDouble("id", id.toDouble())
        putString("name", name)
        putInt("newCount", newCount)
        putInt("learnCount", learnCount)
        putInt("reviewCount", reviewCount)
        putBoolean("filtered", filtered)
    }

    private fun List<AnkiDeck>.toDecksArray(): WritableArray =
        Arguments.createArray().apply { forEach { pushMap(it.toJsMap()) } }

    private fun AnkiReviewCard.Available.toJsMap(): WritableMap = Arguments.createMap().apply {
        putDouble("noteId", noteId.toDouble())
        putDouble("modelId", modelId.toDouble())
        putInt("ord", ord)
        putString("deckName", deckName)
        putString("question", question)
        putString("answer", answer)
        putString("css", css)
        putArray("fieldNames", fieldNames.toJsArray())
        putArray("fields", fields.toJsArray())
        putBoolean("typedAnswer", typedAnswer)
    }

    private fun List<String>.toJsArray(): WritableArray =
        Arguments.createArray().apply { forEach { pushString(it) } }

    /** The value at [key] when it is present and not null; JS `null` and absence are the same. */
    private fun readable(map: ReadableMap, key: String): Double? =
        if (map.hasKey(key) && !map.isNull(key)) map.getDouble(key) else null

    /** The strings of a JS array; a missing array is no strings. */
    private fun stringsOf(array: com.facebook.react.bridge.ReadableArray?): List<String> =
        array?.toArrayList()?.filterIsInstance<String>().orEmpty()

    companion object {
        const val NAME = "GatenAnki"

        private const val CODE_PROVIDER = "E_ANKI_PROVIDER"
        private const val CODE_STATS = "E_ANKI_STATS"
        private const val CODE_EXPORT = "E_ANKI_EXPORT"
        private const val CODE_ADD = "E_ANKI_ADD"
        private const val CODE_SETTINGS = "E_ANKI_SETTINGS"
        private const val PERMISSION_REQUEST_CODE = 5417
        private const val MAX_SAFE_JS_DECK_ID = 9_007_199_254_740_991L
    }
}
