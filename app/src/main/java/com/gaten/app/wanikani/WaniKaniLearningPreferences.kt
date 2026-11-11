/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The store the daily counters live in: one document for the day's activity and one number for the
 * goal, every write answering whether it took. An interface so the counting - and its failure path -
 * can be exercised on a plain JVM, without a `SharedPreferences` and without a device.
 */
internal interface WaniKaniLearningStorage {

    /** The stored activity document, or null when none is stored. */
    fun activity(): String?

    /** The daily lesson goal exactly as it sits on disk: 0 when nothing was ever stored. */
    fun dailyLimit(): Int

    /** Stores [activity]; false when the document is not on disk afterwards. */
    fun saveActivity(activity: String): Boolean

    /** Stores [limit]; false when it did not take. */
    fun saveDailyLimit(limit: Int): Boolean
}

/**
 * This device's daily lesson goal, and what the account connected to it has done today: the
 * assignments whose lessons were started, and the distinct subjects whose reviews WaniKani accepted.
 *
 * - Scoped to the account and to the local calendar day. Both sets sit beside a digest of the stored
 *   token - the digest, never the token, and never across the bridge to JS - so another account on
 *   the same device starts from zero, and a day the clock has left behind is dropped, not carried on.
 * - Only what WaniKani accepted, once: a teach screen, a typed answer, a refused submission and a
 *   failed request count for nothing, and a subject reviewed three times in a day is one subject.
 * - Written under the account the action began under: the digest is taken before the request whose
 *   answer finishes the action, so an answer landing after the credential was replaced is dropped
 *   rather than counted for the account that replaced it.
 * - Never a reason to lose a review: [read] reports a counter that could not be kept as
 *   [Snapshot.activityFailure] instead of throwing, and the write WaniKani accepted is never resent.
 * - The goal, alone, is the device's rather than the account's.
 *
 * The counters the old unscoped version left behind - a day and a set of started assignments with no
 * account beside them - are deleted on the next write rather than attributed to whoever is connected
 * now: which account did that work is what they do not say. The goal's key is unchanged.
 */
internal class WaniKaniLearningPreferences(
    private val credentials: WaniKaniCredentials,
    private val storage: WaniKaniLearningStorage,
    private val today: () -> LocalDate,
) {

    /** The app's own credential and preference file: the only construction production uses. */
    constructor(context: Context) : this(
        KeystoreCredentials(context.applicationContext),
        preferenceStorage(context.applicationContext),
        LocalDate::now,
    )

    /** What a screen reads: the goal, today's counts, and whether they could be kept. */
    data class Snapshot(
        /**
         * The daily lesson goal the device is working to, always a number: [DEFAULT_DAILY_LESSONS]
         * until the reader sets one, and read into [MIN_DAILY_LESSONS]..[MAX_DAILY_LESSONS] when a
         * number an older build stored falls outside it.
         */
        val dailyLimit: Int,
        /** Distinct assignments whose lessons were started today: the number the goal is checked against. */
        val learnedToday: Int,
        /** Distinct subjects reviewed today, or null when the counters could not be read or written. */
        val reviewedToday: Int?,
        /** The local date the counts belong to, `YYYY-MM-DD`. */
        val activityDate: String,
        /** Whether a counter could not be read or written on this device; the screen reports it. */
        val activityFailure: Boolean,
    )

    /**
     * The account the counters are scoped to: a digest of the stored token, or null when none is
     * stored. What a caller takes before it starts an action, so the answer is counted for the
     * account it was made under, not for whichever credential is stored by the time it lands.
     */
    @Synchronized
    fun currentAccount(): String? = accountFingerprint()

    /** Today's goal and counts for the stored account; reads only, so it never writes. */
    @Synchronized
    fun read(): Snapshot {
        val day = today().toString()
        val activity = activityFor(accountFingerprint(), day)
        val storedLimit = storage.dailyLimit()
        return Snapshot(
            // There is no unlimited setting: nothing stored — or a 0 an older build left meaning
            // "unlimited" — works to the default, and a legacy goal above today's range reads as the
            // range's own top. Nothing is rewritten here; the number on disk changes only when the
            // reader saves a goal.
            dailyLimit = when {
                storedLimit <= 0 -> DEFAULT_DAILY_LESSONS
                storedLimit > MAX_DAILY_LESSONS -> MAX_DAILY_LESSONS
                else -> storedLimit
            },
            // The goal is checked against what is on disk, so a counter that could not be written is
            // reported as unavailable rather than hiding the day the reader has already had.
            learnedToday = activity?.started?.size ?: 0,
            reviewedToday = if (storageFailed) null else activity?.reviewed?.size ?: 0,
            activityDate = day,
            activityFailure = storageFailed,
        )
    }

    /**
     * Saves the daily lesson goal, which is always a number: the device has no unlimited setting.
     * Not scoped to an account, because it is the device's own. A goal outside the range is refused
     * here rather than stored, and a write that does not take is thrown: it is the one thing here the
     * caller asked for and did not get, and the screen promises the goal was saved.
     */
    @Synchronized
    fun setDailyLimit(limit: Int): Snapshot {
        require(limit in MIN_DAILY_LESSONS..MAX_DAILY_LESSONS) {
            "Daily lesson limit must be between $MIN_DAILY_LESSONS and $MAX_DAILY_LESSONS."
        }
        check(storage.saveDailyLimit(limit)) { "The daily lesson goal could not be saved." }
        return read()
    }

    /**
     * Whether [assignmentId]'s lesson has already been started today for [account]: what makes a
     * retry of today's own start answer without asking WaniKani again.
     */
    @Synchronized
    fun wasStartedToday(assignmentId: Int, account: String?): Boolean =
        activityFor(account, today().toString())?.started?.contains(assignmentId.toString()) == true

    /** Counts a successful lesson start once per local calendar day, for [account]. */
    @Synchronized
    fun recordStartedAssignment(assignmentId: Int, account: String?): Snapshot =
        record(account) { it.copy(started = it.started + assignmentId.toString()) }

    /** Counts a subject whose review WaniKani accepted once per local calendar day, for [account]. */
    @Synchronized
    fun recordReviewedSubject(subjectId: Int, account: String?): Snapshot =
        record(account) { it.copy(reviewed = it.reviewed + subjectId.toString()) }

    /**
     * The one place a counter is written: the change is applied to today's activity for [account],
     * stored, and the fresh snapshot answers. Nothing is written when the change adds nothing - the
     * same subject reviewed twice in a day is one subject - or when [account] is not the account the
     * stored credential belongs to any more. A storage that throws is a counter that could not be
     * kept, not an error for a caller whose write WaniKani already took.
     */
    private fun record(account: String?, change: (Activity) -> Activity): Snapshot {
        try {
            if (account != null && account == accountFingerprint()) {
                val day = today().toString()
                val stored = storage.activity()?.let(::decodeActivity)
                val current = stored?.takeIf { it.account == account && it.day == day }
                    ?: Activity(account, day, emptySet(), emptySet())
                val updated = change(current)
                if (updated != current) storageFailed = !storage.saveActivity(encodeActivity(updated))
            }
        } catch (e: RuntimeException) {
            storageFailed = true
        }
        return read()
    }

    /**
     * The stored activity when it can be read and belongs to [account] and to [day], or null. Activity
     * belonging to another account or another day is none of this reader's day, and a document that
     * cannot be read as one is a storage failure the next [read] reports.
     */
    private fun activityFor(account: String?, day: String): Activity? {
        val text = storage.activity() ?: return null
        val stored = decodeActivity(text)
        if (stored == null) {
            storageFailed = true
            return null
        }
        return stored.takeIf { it.account == account && it.day == day }
    }

    /**
     * The scope of the counters: the SHA-256 digest of the stored token, or null when none is stored.
     * A digest rather than the token, so the scope can be stored and compared without the credential
     * being written down anywhere new - and it is never handed to JS.
     */
    private fun accountFingerprint(): String? = credentials.read()?.let { token ->
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** The document [decodeActivity] reads back. */
    private fun encodeActivity(activity: Activity): String = JSONObject().apply {
        put(FIELD_ACCOUNT, activity.account)
        put(FIELD_DAY, activity.day)
        put(FIELD_STARTED, JSONArray(activity.started.toList()))
        put(FIELD_REVIEWED, JSONArray(activity.reviewed.toList()))
    }.toString()

    /** The stored document as an [Activity], or null when it is not one this reader can read. */
    private fun decodeActivity(text: String): Activity? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        val account = root.optString(FIELD_ACCOUNT).takeIf { it.isNotEmpty() } ?: return null
        val day = root.optString(FIELD_DAY).takeIf { it.isNotEmpty() } ?: return null
        return Activity(
            account = account,
            day = day,
            started = root.optJSONArray(FIELD_STARTED).ids(),
            reviewed = root.optJSONArray(FIELD_REVIEWED).ids()
        )
    }

    /** The ids of a stored array, with anything that is not a non-empty string left out. */
    private fun JSONArray?.ids(): Set<String> {
        if (this == null) return emptySet()
        val ids = LinkedHashSet<String>(length())
        for (index in 0 until length()) {
            // `isNull` first: AOSP's org.json answers a JSON null with the literal string "null".
            if (isNull(index)) continue
            optString(index).takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        return ids
    }

    /** One account's day: the local date it is, and the ids counted on it. */
    private data class Activity(
        val account: String,
        val day: String,
        val started: Set<String>,
        val reviewed: Set<String>,
    )

    /** Set when a counter could not be read or written, and cleared by the next write that takes. */
    private var storageFailed = false

    private companion object {
        const val FIELD_ACCOUNT = "account"
        const val FIELD_DAY = "day"
        const val FIELD_STARTED = "started"
        const val FIELD_REVIEWED = "reviewed"
    }
}

/** The app's own preference file behind the counters, one `commit` per write so a failed one is known. */
private class PreferenceStorage(private val preferences: SharedPreferences) : WaniKaniLearningStorage {

    override fun activity(): String? = preferences.getString(KEY_ACTIVITY, null)

    override fun dailyLimit(): Int = preferences.getInt(KEY_DAILY_LIMIT, 0)

    override fun saveActivity(activity: String): Boolean =
        editor().putString(KEY_ACTIVITY, activity).commit()

    override fun saveDailyLimit(limit: Int): Boolean =
        editor().putInt(KEY_DAILY_LIMIT, limit).commit()

    /**
     * An editor that also drops the counters the old unscoped version of this class left behind - a
     * day and a set of started assignments with no account beside them. Nothing reads them any more:
     * which account did that work is not in them, so they go rather than being attributed to whoever
     * is connected now. Removing a key that is not there writes nothing.
     */
    private fun editor(): SharedPreferences.Editor = preferences.edit()
        .remove(KEY_LEGACY_DAY)
        .remove(KEY_LEGACY_ASSIGNMENTS)
}

/** The preference file the counters live in. */
private fun preferenceStorage(context: Context): WaniKaniLearningStorage =
    PreferenceStorage(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

private const val PREFERENCES = "wanikani_learning_preferences"

/** The day's activity, as one document; see [WaniKaniLearningPreferences.encodeActivity]. */
private const val KEY_ACTIVITY = "activity"

/** The daily goal; the same key the previous version used, so a configured goal survives. */
private const val KEY_DAILY_LIMIT = "daily_lesson_limit"

/** The daily lesson goal the device accepts, and the one an unset device works to. */
internal const val MIN_DAILY_LESSONS = 1
internal const val MAX_DAILY_LESSONS = 20
internal const val DEFAULT_DAILY_LESSONS = 15

/** The keys the unscoped version stored its activity under; only ever removed. */
private const val KEY_LEGACY_DAY = "learned_day"
private const val KEY_LEGACY_ASSIGNMENTS = "started_assignments"
