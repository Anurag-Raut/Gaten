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
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import com.gaten.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Gaten"

/** AnkiDroid's model type for a standard note type. A cloze note type (1) carries its own markup. */
private const val STANDARD_MODEL_TYPE = 0

/** How many hex characters of a layout's fingerprint the note type it creates is named after. */
private const val NAME_FINGERPRINT_LENGTH = 16

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

/** What the customise screen can offer from the AnkiDroid collection Gaten is talking to. */
data class AnkiCollection(
    /** Deck names as AnkiDroid spells them, case-insensitively sorted. */
    val decks: List<String>,
    /** Note type ids and names, standard note types only, sorted by name. */
    val models: Map<Long, String>
)

/**
 * Adds looked-up words to AnkiDroid through its instant-add API.
 *
 * [AddContentApi] talks to AnkiDroid's `flashcards` ContentProvider, not to a network service: the
 * export stays on-device, and the app's INTERNET permission is only ever used for the optional OCR
 * model download and the optional WaniKani reference pages (see the README). AnkiDroid must be
 * declared in the manifest's `<queries>` for that provider to be visible at all on Android 11+, and
 * a write needs its `READ_WRITE_DATABASE` permission. The
 * overlay is drawn from the capture service, which can check that permission but never ask for
 * it, so [shareFallback] is what covers the ungranted case.
 *
 * Where a note goes comes from [AnkiSettings]: the deck of the configured name, its tags, and either
 * Gaten's own note type carrying the configured templates or a note type the user picked. A note
 * type Gaten creates is named after the templates it holds and its id is cached per template
 * fingerprint, so editing a layout allocates a new note type instead of rewriting one the cards
 * already in the collection were built from. Every add re-checks what it is writing to: AnkiDroid
 * renumbers ids when a deck or note type is deleted, and a note type the user edited may no longer
 * report the fields the mapping was made for.
 */
object AnkiExporter {
    /** AnkiDroid's database permission, checked before every read and write. */
    private val PERMISSION: String = AddContentApi.READ_WRITE_PERMISSION

    /** The app's settings file shared with [AnkiPreferences]. */
    private const val PREFS_NAME = "gaten_settings"

    /** Deck id cache, kept only while AnkiDroid still reports that id under the configured deck name. */
    private const val KEY_DECK_ID = "anki_deck_id"

    /** Note type id cache for Gaten's default layout, from before layouts were editable. */
    private const val KEY_MODEL_ID = "anki_model_id"

    /** Note type id cache, one entry per template fingerprint. */
    private const val KEY_MODEL_ID_PREFIX = "anki_model_id:"

    /** Sentinel for "no cached id": AnkiDroid's ids are positive. */
    private const val NO_ID = -1L

    /** The single card template every Gaten note type gets. */
    private val CARD_TEMPLATE_NAMES = arrayOf(AnkiSettings.DEFAULT_DECK_NAME)

    /** Gaten's note types are sorted by their first field, the one AnkiDroid checks duplicates on. */
    private const val SORT_FIELD_INDEX = 0

    /** AnkiDroid's package, addressed by the share fallback. */
    private const val ANKI_DROID_PACKAGE = "com.ichi2.anki"

    /**
     * Serializes adds and duplicate checks, so two taps on the same word cannot both create the deck
     * or the note type, nor both write the same note.
     */
    private val addLock = Mutex()

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
     * The decks and standard note types the customise screen offers, from two provider queries. A
     * cloze note type is left out — its text has to carry `{{c1::…}}` markup Gaten never produces.
     *
     * Reads nothing without AnkiDroid's database permission and writes nothing at all. Every
     * AnkiDroid call is a provider round-trip, so this runs on [Dispatchers.IO] and fails by result.
     */
    suspend fun collection(context: Context): Result<AnkiCollection> = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext Result.failure(missingPermission())
        try {
            val models = standardModels(context).entries
                .sortedBy { it.value.name.lowercase() }
                .associate { it.key to it.value.name }
            // A provider that cannot list decks is reported, never presented as an empty collection.
            val decks = AddContentApi(context).deckList
                ?: error("AnkiDroid did not return its decks")
            Result.success(
                AnkiCollection(
                    decks = decks.values.filterNotNull().sortedBy { it.lowercase() },
                    models = models
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading AnkiDroid's decks and note types failed", e)
            Result.failure(e)
        }
    }

    /**
     * The field names of note type [modelId] in Anki's order, for the mapping the customise screen
     * builds. Fails when AnkiDroid has no such note type or it is a cloze note type; writes nothing.
     */
    suspend fun modelFields(context: Context, modelId: Long): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (!granted(context)) return@withContext Result.failure(missingPermission())
            try {
                val fields = standardModels(context)[modelId]?.fields
                if (fields == null) {
                    return@withContext Result.failure(
                        IllegalStateException("AnkiDroid has no standard note type $modelId")
                    )
                }
                Result.success(fields)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading the fields of AnkiDroid note type $modelId failed", e)
                Result.failure(e)
            }
        }

    /**
     * Adds [card] to the deck in [settings], creating the deck — and, for Gaten's own note type, the
     * note type — on first use.
     *
     * Every AnkiDroid call is a provider round-trip, so this runs on [Dispatchers.IO], and adds are
     * serialized so two taps cannot both write the same note. It never throws but for cancellation:
     * a missing permission, a provider error, settings that no longer match the note type and a
     * rejected note all come back as [AddResult.Failed].
     */
    suspend fun add(
        context: Context,
        card: AnkiCard,
        settings: AnkiSettings = AnkiPreferences.load(context)
    ): AddResult = withContext(Dispatchers.IO) {
        if (!granted(context)) {
            return@withContext AddResult.Failed(context.getString(R.string.anki_error_permission))
        }
        try {
            addLock.withLock {
                // Wrong deck, tags or mapping: nothing is written, and the user is pointed at the
                // customise screen rather than at a note Gaten guessed at.
                if (settings.validationError() != null) {
                    return@withLock AddResult.Failed(context.getString(R.string.anki_error_configuration))
                }
                val api = AddContentApi(context)
                val fields = settings.fields(card)
                val key = duplicateKey(fields)
                if (key.isBlank()) {
                    return@withLock AddResult.Failed(context.getString(R.string.anki_error_empty_first_field))
                }
                val models = standardModels(context)
                // The note type is resolved — and a chosen one is checked against its fields —
                // before anything is created, so a note type the user edited in AnkiDroid cannot
                // leave a freshly created deck behind on its way to a failure.
                val reusable = activeModelId(context, api, models, settings, deckId = null, create = false)
                if (reusable == null && settings.modelId != null) {
                    return@withLock AddResult.Failed(context.getString(R.string.anki_error_configuration))
                }
                val deckId = ensureDeck(context, api, settings.deckName)
                    ?: return@withLock AddResult.Failed(context.getString(R.string.anki_error_deck))
                val modelId = reusable
                    ?: activeModelId(context, api, models, settings, deckId, create = true)
                    ?: return@withLock AddResult.Failed(context.getString(R.string.anki_error_model))
                // A provider that cannot answer the duplicate query must not be read as "no duplicate".
                val duplicates = api.findDuplicateNotes(modelId, key)
                    ?: return@withLock AddResult.Failed(context.getString(R.string.anki_error_failed))
                val duplicate = duplicates.isNotEmpty()
                val added = !duplicate && api.addNote(modelId, deckId, fields, settings.tagSet()) != null
                when (addOutcome(duplicate, added)) {
                    AddOutcome.DUPLICATE -> AddResult.Duplicate
                    AddOutcome.ADDED -> AddResult.Added
                    AddOutcome.FAILED -> AddResult.Failed(context.getString(R.string.anki_error_failed))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Adding a note to AnkiDroid failed", e)
            AddResult.Failed(context.getString(R.string.anki_error_failed))
        }
    }

    /**
     * Whether [card] is already in the note type an add would write to, without creating a deck or a
     * note type.
     *
     * Resolves the note type and the duplicate key exactly as [add] does, so the two can never
     * disagree about what counts as a duplicate. A provider failure, settings that no longer match
     * the note type and a vanished note type all read as false, leaving [add] as the final guard.
     */
    suspend fun isDuplicate(
        context: Context,
        card: AnkiCard,
        settings: AnkiSettings = AnkiPreferences.load(context)
    ): Boolean = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext false
        try {
            addLock.withLock {
                if (settings.validationError() != null) return@withLock false
                val api = AddContentApi(context)
                val key = duplicateKey(settings.fields(card))
                if (key.isBlank()) return@withLock false
                val modelId = activeModelId(
                    context = context,
                    api = api,
                    models = standardModels(context),
                    settings = settings,
                    deckId = null,
                    create = false
                ) ?: return@withLock false
                api.findDuplicateNotes(modelId, key)?.isNotEmpty() == true
            }
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
     * The user picks the deck and the note type there, so [AnkiSettings] does not apply. Returns
     * false when nothing can handle the intent, so the caller can say so.
     */
    fun shareFallback(context: Context, card: AnkiCard): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(ANKI_DROID_PACKAGE)
            // Started from the capture service, never from an activity, so it needs a task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_SUBJECT, card.expression)
            putExtra(Intent.EXTRA_TEXT, shareText(card))
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
     * The id of the note type an add would write to, or null when this configuration cannot be used:
     * a chosen note type AnkiDroid no longer reports, one that has become cloze, or one that now
     * reports fields other than the mapping was made for.
     *
     * [create] — an add, never a duplicate check — allocates Gaten's own note type when no usable one
     * is cached or already present; that, not a duplicate check, is where a note type is created.
     */
    private fun activeModelId(
        context: Context,
        api: AddContentApi,
        models: Map<Long, StandardModel>,
        settings: AnkiSettings,
        deckId: Long?,
        create: Boolean
    ): Long? {
        val chosen = settings.modelId
        if (chosen != null) {
            // Never a fallback from here: the mapping was built for this note type's fields.
            return chosen.takeIf { models[it]?.fields == settings.modelFields }
        }
        return managedModelId(context, api, models, settings, deckId, create)
    }

    /**
     * Gaten's own note type for [settings]'s layout.
     *
     * A layout is only ever found by the id cached under its full template fingerprint: the name
     * carries just a fingerprint prefix, which is not proof that a note type of that name holds
     * these templates. Two exceptions, both for the default layout, whose name is plain "Gaten": the
     * id older installs cached under one fixed key, and the note type AnkiDroid itself has under that
     * name — the one whose template edits in AnkiDroid should survive.
     *
     * A cached id is kept only while AnkiDroid reports the fields it was made for, so a deleted or
     * repurposed note type is never written to, and only for this layout, so a second layout never
     * takes the first layout's note type. AnkiDroid allows two note types to share a name, and Gaten
     * overwrites neither: a new layout is a new note type, created by [create] on an add only.
     */
    private fun managedModelId(
        context: Context,
        api: AddContentApi,
        models: Map<Long, StandardModel>,
        settings: AnkiSettings,
        deckId: Long?,
        create: Boolean
    ): Long? {
        val expected = AnkiSettings.NOTE_FIELDS
        cachedId(context, modelCacheKey(settings))?.takeIf { models[it]?.fields == expected }?.let { return it }
        if (settings.isDefaultTemplates()) {
            cachedId(context, KEY_MODEL_ID)?.takeIf { models[it]?.fields == expected }?.let { return it }
            val name = AnkiSettings.DEFAULT_DECK_NAME
            models.entries.sortedBy { it.key }
                .firstOrNull { it.value.name == name && it.value.fields == expected }
                ?.let { return if (create) cacheModelId(context, settings, it.key) else it.key }
        }
        if (!create) return null
        // A note type remembers the deck its cards land in, so creating one needs the deck resolved.
        val deck = deckId ?: return null
        val created = api.addNewCustomModel(
            managedModelName(settings),
            expected.toTypedArray(),
            CARD_TEMPLATE_NAMES,
            arrayOf(settings.front),
            arrayOf(settings.back),
            settings.css,
            deck,
            SORT_FIELD_INDEX
        ) ?: return null
        return cacheModelId(context, settings, created)
    }

    /**
     * The id of the deck in the settings, creating it when AnkiDroid reports no deck of that exact
     * name. The configured name wins over the cache: a deck the user renamed in AnkiDroid is a deck
     * Gaten should stop writing to, not one to keep by a stale id.
     */
    private fun ensureDeck(context: Context, api: AddContentApi, deckName: String): Long? {
        // No deck list, no deck: creating one on a guess would put the word in a deck of Gaten's own.
        val decks = api.deckList ?: return null
        resolveDeckId(cachedId(context, KEY_DECK_ID), decks, deckName)?.let {
            cacheId(context, KEY_DECK_ID, it)
            return it
        }
        val created = api.addNewDeck(deckName) ?: return null
        cacheId(context, KEY_DECK_ID, created)
        return created
    }

    /**
     * Every standard note type AnkiDroid reports, keyed by id, from one provider query. It is the
     * only way to tell a cloze note type from a standard one, because [AddContentApi]'s own model
     * list reports no types.
     *
     * A provider that returns no cursor throws instead of reading as an empty collection: an empty
     * list means "nothing to reuse, create one", and creating a second note type whenever AnkiDroid
     * hiccups is worse than failing the add.
     */
    private fun standardModels(context: Context): Map<Long, StandardModel> {
        val cursor = context.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI,
            arrayOf(
                FlashCardsContract.Model._ID,
                FlashCardsContract.Model.NAME,
                FlashCardsContract.Model.TYPE,
                FlashCardsContract.Model.FIELD_NAMES
            ),
            null,
            null,
            null
        ) ?: error("AnkiDroid did not return its note types")
        try {
            val models = LinkedHashMap<Long, StandardModel>()
            val id = cursor.getColumnIndexOrThrow(FlashCardsContract.Model._ID)
            val name = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.NAME)
            val type = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.TYPE)
            val fields = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.FIELD_NAMES)
            while (cursor.moveToNext()) {
                if (cursor.getInt(type) != STANDARD_MODEL_TYPE) continue
                models[cursor.getLong(id)] = StandardModel(
                    name = cursor.getString(name).orEmpty(),
                    fields = cursor.getString(fields).orEmpty().split(ANKI_FIELD_SEPARATOR)
                )
            }
            return models
        } finally {
            cursor.close()
        }
    }

    /** Caches [id] for [settings]'s layout, and for the default layout under the older key as well. */
    private fun cacheModelId(context: Context, settings: AnkiSettings, id: Long): Long {
        cacheId(context, modelCacheKey(settings), id)
        if (settings.isDefaultTemplates()) cacheId(context, KEY_MODEL_ID, id)
        return id
    }

    /** The id cache key of [settings]'s exact templates. */
    private fun modelCacheKey(settings: AnkiSettings): String = KEY_MODEL_ID_PREFIX + settings.templateKey()

    private fun cachedId(context: Context, key: String): Long? =
        prefs(context).getLong(key, NO_ID).takeIf { it != NO_ID }

    private fun cacheId(context: Context, key: String, id: Long) {
        prefs(context).edit().putLong(key, id).apply()
    }

    private fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A note type AnkiDroid can be written to: its name, and the field names it reports, in order. */
    private data class StandardModel(val name: String, val fields: List<String>)
}

/** The failure [AnkiExporter.collection] and [AnkiExporter.modelFields] report without the permission. */
private fun missingPermission(): Exception =
    IllegalStateException("AnkiDroid's database permission is not granted")

/**
 * What [AnkiExporter.status] reports for a device, given what the platform said. Pure, so all three
 * branches are unit-tested without one.
 */
internal fun ankiStatus(installed: Boolean, permissionGranted: Boolean): AnkiStatus = when {
    !installed -> AnkiStatus.NotInstalled
    !permissionGranted -> AnkiStatus.NeedsPermission
    else -> AnkiStatus.Ready
}

/** What one add attempt reports, before the exporter maps it onto a localised [AddResult]. Pure. */
internal fun addOutcome(duplicate: Boolean, added: Boolean): AddOutcome = when {
    duplicate -> AddOutcome.DUPLICATE
    added -> AddOutcome.ADDED
    else -> AddOutcome.FAILED
}

/**
 * The deck id to keep using: the cached one while AnkiDroid still reports it under [deckName], else
 * the first deck it lists under that exact name, else null so the caller creates the deck. Pure.
 */
internal fun resolveDeckId(cachedId: Long?, decks: Map<Long, String>, deckName: String): Long? =
    cachedId?.takeIf { decks[it] == deckName }
        ?: decks.entries.firstOrNull { it.value == deckName }?.key

/**
 * The value AnkiDroid matches a duplicate note against: the first field of the note
 * (`AddContentApi.findDuplicateNotes`). [AnkiSettings.fields] puts the first mapped field first, so
 * whichever source the user mapped there is the duplicate key of a chosen note type. Pure, and shared
 * by an add and a duplicate check so the two cannot disagree.
 */
internal fun duplicateKey(fields: Array<String>): String = fields.firstOrNull().orEmpty()

/**
 * [card] as AnkiDroid's share sheet takes it: the fields the card carries, one per line, without the
 * HTML Anki renders them with.
 *
 * The share sheet is plain text, so the inline markup comes off and the escaping Anki fields need is
 * undone — `&lt;` in a gloss is a printed "<", which is exactly what this text has to show. `&amp;`
 * is decoded last so that decoding happens once: turning it into `&` first would make a literal
 * "&lt;" in the dictionary text come out as "<". Empty fields are left out, as with the note itself.
 */
internal fun shareText(card: AnkiCard): String =
    listOf(card.reading, card.meaning)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .replace("<br>", "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

/**
 * The name a new note type for [settings]'s layout gets: "Gaten" while the templates are the defaults,
 * else "Gaten" and a fingerprint of the layout, so the note type a changed layout creates is
 * recognisable in AnkiDroid and never looks like the note types already in the collection.
 *
 * The name is for the user's benefit — a layout is found again by its cached id, not by its name.
 * Pure.
 */
internal fun managedModelName(settings: AnkiSettings): String = if (settings.isDefaultTemplates()) {
    AnkiSettings.DEFAULT_DECK_NAME
} else {
    AnkiSettings.DEFAULT_DECK_NAME + " " + settings.templateKey().take(NAME_FINGERPRINT_LENGTH)
}
