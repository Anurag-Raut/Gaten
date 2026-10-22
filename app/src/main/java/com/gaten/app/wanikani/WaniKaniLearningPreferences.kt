/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.Context
import java.time.LocalDate

/** Stores this device's daily lesson limit and the assignments it successfully started today. */
class WaniKaniLearningPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): Snapshot {
        val today = LocalDate.now().toString()
        val storedDay = preferences.getString(KEY_DAY, null)
        val assignments = if (storedDay == today) {
            preferences.getStringSet(KEY_ASSIGNMENTS, emptySet()).orEmpty()
        } else {
            emptySet()
        }
        return Snapshot(
            dailyLimit = preferences.getInt(KEY_DAILY_LIMIT, 0).takeIf { it > 0 },
            learnedToday = assignments.size,
        )
    }

    @Synchronized
    fun setDailyLimit(limit: Int?): Snapshot {
        require(limit == null || limit in MIN_DAILY_LIMIT..MAX_DAILY_LIMIT) {
            "Daily lesson limit must be between $MIN_DAILY_LIMIT and $MAX_DAILY_LIMIT, or unlimited."
        }
        preferences.edit().apply {
            if (limit == null) remove(KEY_DAILY_LIMIT) else putInt(KEY_DAILY_LIMIT, limit)
        }.apply()
        return read()
    }

    @Synchronized
    fun wasStartedToday(assignmentId: Int): Boolean {
        if (preferences.getString(KEY_DAY, null) != LocalDate.now().toString()) return false
        return preferences.getStringSet(KEY_ASSIGNMENTS, emptySet())
            ?.contains(assignmentId.toString()) == true
    }

    /** Counts a successful assignment start once per local calendar day. */
    @Synchronized
    fun recordStartedAssignment(assignmentId: Int): Snapshot {
        val today = LocalDate.now().toString()
        val assignments = if (preferences.getString(KEY_DAY, null) == today) {
            preferences.getStringSet(KEY_ASSIGNMENTS, emptySet()).orEmpty().toMutableSet()
        } else {
            mutableSetOf()
        }
        assignments.add(assignmentId.toString())
        preferences.edit()
            .putString(KEY_DAY, today)
            .putStringSet(KEY_ASSIGNMENTS, assignments)
            .apply()
        return read()
    }

    data class Snapshot(val dailyLimit: Int?, val learnedToday: Int)

    private companion object {
        const val PREFERENCES = "wanikani_learning_preferences"
        const val KEY_DAILY_LIMIT = "daily_lesson_limit"
        const val KEY_DAY = "learned_day"
        const val KEY_ASSIGNMENTS = "started_assignments"
        const val MIN_DAILY_LIMIT = 1
        const val MAX_DAILY_LIMIT = 1000
    }
}
