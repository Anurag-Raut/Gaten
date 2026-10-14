/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi
import com.gaten.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Gaten"

/** Whether "Add to Anki" can do anything on this device. */
sealed interface AnkiStatus {
    /** AnkiDroid is not installed; the result sheet hides the action. */
    data object NotInstalled : AnkiStatus

    /** AnkiDroid is installed but has not been granted its database permission yet. */
    data object NeedsPermission : AnkiStatus

    /** AnkiDroid is installed and granted; adding needs no further prompt. */
    data object Ready : AnkiStatus
}

/** How one add attempt ended. */
sealed interface AddResult {
    data object Added : AddResult

    /** The note is already in the collection; nothing was written. */
    data object Duplicate : AddResult

    /** [message] is localised and meant for a toast. */
    data class Failed(val message: String) : AddResult
}

/** What an add attempt reports, before the exporter maps it onto a localised [AddResult]. */
internal enum class AddOutcome { DUPLICATE, ADDED, FAILED }

/**
 * Adds looked-up words to AnkiDroid through its instant-add API.
 *
 * [AddContentApi] talks to AnkiDroid's `flashcards` ContentProvider, not to a network service, so
 * Gaten keeps its no-INTERNET manifest. AnkiDroid must be declared in the manifest's `<queries>`
 * for that provider to be visible at all on Android 11+, and a write needs its
 * `READ_WRITE_DATABASE` permission. The overlay is drawn from the accessibility service, which can
 * check that permission but never ask for it, so [shareFallback] is what covers the ungranted case.
 *
 * The deck and note type are created on first use and their ids cached in the app's settings file,
 * but every add re-checks that they still exist: AnkiDroid renumbers them when the user deletes a
 * deck or a note type, and a stale id makes the provider reject the note.
 */
object AnkiExporter {
    /** AnkiDroid's database permission, checked before every write. */
    private val PERMISSION: String = AddContentApi.READ_WRITE_PERMISSION

    /** The settings file shared with `ocr/OcrPreferences`. */
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_DECK_ID = "anki_deck_id"
    private const val KEY_MODEL_ID = "anki_model_id"

    /** Sentinel for "no cached id": AnkiDroid's ids are positive. */
    private const val NO_ID = -1L

    /** Display name of both the deck and the note type created on the first add. */
    private const val DECK_AND_MODEL_NAME = "Gaten"

    private val NOTE_FIELDS = arrayOf("Expression", "Reading", "Meaning", "Sentence")
    private val CARD_TEMPLATE_NAMES = arrayOf(DECK_AND_MODEL_NAME)
    private val CARD_FRONT = arrayOf("{{Expression}}")
    private val CARD_BACK =
        arrayOf("{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}<br><small>{{Sentence}}</small>")

    /** Index of Expression, the field AnkiDroid checks for a duplicate note. */
    private const val SORT_FIELD_INDEX = 0

    /** Centred and readable; Anki puts the `.nightMode` class on the card in dark mode. */
    private val CARD_CSS = """
        .card { font-family: sans-serif; font-size: 24px; text-align: center; color: #111; background: #fff; }
        .nightMode .card { color: #eee; background: #222; }
        small { font-size: 16px; color: #666; }
        .nightMode small { color: #aaa; }
        """.trimIndent()

    /** AnkiDroid's package, addressed by the share fallback. */
    private const val ANKI_DROID_PACKAGE = "com.ichi2.anki"

    /**
     * Whether the result sheet should offer the action, and whether it has to ask for the database
     * permission first.
     *
     * AnkiDroid is only visible to [AddContentApi.getAnkiDroidPackageName] because the manifest
     * declares it in `<queries>`; without that this reports [AnkiStatus.NotInstalled] on Android 11+
     * even with AnkiDroid installed.
     */
    fun status(context: Context): AnkiStatus = ankiStatus(
        installed = AddContentApi.getAnkiDroidPackageName(context) != null,
        permissionGranted = granted(context)
    )

    /**
     * Adds [card] to the "Gaten" deck, creating the deck and the note type on first use.
     *
     * Every AnkiDroid call is a provider round-trip, so this runs on [Dispatchers.IO]. It never
     * throws but for cancellation: a missing permission, a provider error and a rejected note all
     * come back as [AddResult.Failed].
     */
    suspend fun add(context: Context, card: AnkiCard): AddResult = withContext(Dispatchers.IO) {
        if (!granted(context)) {
            return@withContext AddResult.Failed(context.getString(R.string.anki_error_permission))
        }
        try {
            val api = AddContentApi(context)
            val deckId = ensureDeck(context, api)
                ?: return@withContext AddResult.Failed(context.getString(R.string.anki_error_deck))
            val modelId = ensureModel(context, api, deckId)
                ?: return@withContext AddResult.Failed(context.getString(R.string.anki_error_model))

            val duplicate = !api.findDuplicateNotes(modelId, card.expression).isNullOrEmpty()
            val added = !duplicate && api.addNote(
                modelId,
                deckId,
                arrayOf(card.expression, card.reading, card.meaning, card.sentence),
                null
            ) != null

            when (addOutcome(duplicate, added)) {
                AddOutcome.DUPLICATE -> AddResult.Duplicate
                AddOutcome.ADDED -> AddResult.Added
                AddOutcome.FAILED -> AddResult.Failed(context.getString(R.string.anki_error_failed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Adding a note to AnkiDroid failed", e)
            AddResult.Failed(context.getString(R.string.anki_error_failed))
        }
    }

    /**
     * Checks the existing Gaten note type for [card] without creating a deck or model. A provider
     * failure is treated as "unknown" so the normal add path remains the final guard.
     */
    suspend fun isDuplicate(context: Context, card: AnkiCard): Boolean = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext false
        try {
            val api = AddContentApi(context)
            val models = api.modelList.orEmpty()
            val modelId = resolveId(cachedId(context, KEY_MODEL_ID), models.keys)
                ?: models.entries.firstOrNull { it.value == DECK_AND_MODEL_NAME }?.key
                ?: return@withContext false
            !api.findDuplicateNotes(modelId, card.expression).isNullOrEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Checking for an existing AnkiDroid note failed", e)
            false
        }
    }

    /**
     * Hands [card] to AnkiDroid's own share handler: the fallback for a word that cannot be written
     * through the provider, because the user declined the database permission or because the
     * overlay's service window has no way to ask for it. AnkiDroid opens its "add note" screen with
     * the word filled in and the user confirms it there, so this needs no permission from Gaten.
     *
     * Returns false when nothing can handle the intent, so the caller can say so.
     */
    fun shareFallback(context: Context, card: AnkiCard): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(ANKI_DROID_PACKAGE)
            // Started from the accessibility service, never from an activity, so it needs a task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_SUBJECT, card.expression)
            putExtra(
                Intent.EXTRA_TEXT,
                listOf(card.reading, card.meaning).filter { it.isNotEmpty() }.joinToString("\n")
            )
        }
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for the AnkiDroid share intent", e)
            false
        }
    }

    /**
     * The id of the "Gaten" deck, creating the deck when the cached id is gone.
     *
     * A cached id is only kept while AnkiDroid still lists it; otherwise an existing deck of the
     * same name wins over creating a second one.
     */
    private fun ensureDeck(context: Context, api: AddContentApi): Long? {
        val decks = api.deckList.orEmpty()
        resolveId(cachedId(context, KEY_DECK_ID), decks.keys)?.let { return it }
        val id = decks.entries.firstOrNull { it.value == DECK_AND_MODEL_NAME }?.key
            ?: api.addNewDeck(DECK_AND_MODEL_NAME)
            ?: return null
        cacheId(context, KEY_DECK_ID, id)
        return id
    }

    /**
     * The id of the "Gaten" note type, creating it — four fields, one template — when the cached
     * id is gone. The template's question side is the word alone, the answer side adds reading,
     * meaning and the sentence.
     */
    private fun ensureModel(context: Context, api: AddContentApi, deckId: Long): Long? {
        val models = api.modelList.orEmpty()
        resolveId(cachedId(context, KEY_MODEL_ID), models.keys)?.let { return it }
        val id = models.entries.firstOrNull { it.value == DECK_AND_MODEL_NAME }?.key
            ?: api.addNewCustomModel(
                DECK_AND_MODEL_NAME,
                NOTE_FIELDS,
                CARD_TEMPLATE_NAMES,
                CARD_FRONT,
                CARD_BACK,
                CARD_CSS,
                deckId,
                SORT_FIELD_INDEX
            )
            ?: return null
        cacheId(context, KEY_MODEL_ID, id)
        return id
    }

    private fun cachedId(context: Context, key: String): Long? =
        prefs(context).getLong(key, NO_ID).takeIf { it != NO_ID }

    private fun cacheId(context: Context, key: String, id: Long) {
        prefs(context).edit().putLong(key, id).apply()
    }

    private fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * What [AnkiExporter.status] reports for a device, given what the platform said. Pure, so all three
 * branches are unit-tested without one.
 */
internal fun ankiStatus(installed: Boolean, permissionGranted: Boolean): AnkiStatus = when {
    !installed -> AnkiStatus.NotInstalled
    !permissionGranted -> AnkiStatus.NeedsPermission
    else -> AnkiStatus.Ready
}

/**
 * The id to keep using for the deck or note type: the cached one while AnkiDroid still lists it,
 * null when it is gone and has to be recreated. Pure.
 */
internal fun resolveId(stored: Long?, existing: Collection<Long>): Long? =
    stored?.takeIf(existing::contains)

/** What one add attempt reports, before the exporter maps it onto a localised [AddResult]. Pure. */
internal fun addOutcome(duplicate: Boolean, added: Boolean): AddOutcome = when {
    duplicate -> AddOutcome.DUPLICATE
    added -> AddOutcome.ADDED
    else -> AddOutcome.FAILED
}
