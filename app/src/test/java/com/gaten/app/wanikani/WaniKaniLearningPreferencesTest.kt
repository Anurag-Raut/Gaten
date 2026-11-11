/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * The daily counters' own decisions: what is counted, what is not, whose day it is, and what a write
 * that did not take means. A fake store and a clock the test moves stand in for the device, so none
 * of this needs Android and none of it needs a WaniKani account.
 */
class WaniKaniLearningPreferencesTest {

    private val credentials = FakeCredentials()
    private val storage = FakeStorage()
    private var day = LocalDate.parse(TODAY)

    private fun preferences() = WaniKaniLearningPreferences(credentials, storage, { day })

    @Test
    fun `lesson starts and reviews are counted once each, per subject, per day`() {
        val preferences = preferences()
        val account = preferences.currentAccount()

        val first = preferences.recordStartedAssignment(7, account)
        assertEquals(1, first.learnedToday)
        assertEquals(0, first.reviewedToday)
        assertEquals(TODAY, first.activityDate)
        assertFalse(first.activityFailure)
        assertTrue(preferences.wasStartedToday(7, account))
        assertEquals(1, storage.activityWrites)

        // The same start again - a retry of a lesson WaniKani already took - is not a second lesson.
        assertEquals(1, preferences.recordStartedAssignment(7, account).learnedToday)
        assertEquals(1, storage.activityWrites)

        // Reviews are their own count, and distinct by subject: three answers over two subjects.
        preferences.recordReviewedSubject(11, account)
        preferences.recordReviewedSubject(12, account)
        val after = preferences.recordReviewedSubject(11, account)
        assertEquals(2, after.reviewedToday)
        assertEquals(1, after.learnedToday)
    }

    @Test
    fun `a new local day starts from zero and keeps the goal`() {
        val preferences = preferences()
        val account = preferences.currentAccount()
        preferences.setDailyLimit(5)
        preferences.recordStartedAssignment(7, account)
        preferences.recordReviewedSubject(11, account)

        day = day.plusDays(1)
        val next = preferences.read()

        assertEquals(NEXT_DAY, next.activityDate)
        assertEquals(0, next.learnedToday)
        assertEquals(0, next.reviewedToday)
        assertEquals(5, next.dailyLimit)
        assertFalse(preferences.wasStartedToday(7, account))

        // The new day's write is a document of its own: yesterday's ids are not carried into it.
        val started = preferences.recordStartedAssignment(8, account)
        assertEquals(1, started.learnedToday)
        assertEquals(0, started.reviewedToday)
    }

    @Test
    fun `another account's day is not this account's, and an answer for a replaced account is dropped`() {
        val first = preferences()
        val firstAccount = first.currentAccount()
        first.setDailyLimit(3)
        first.recordStartedAssignment(7, firstAccount)
        first.recordReviewedSubject(11, firstAccount)
        assertEquals(1, first.read().learnedToday)

        // The reader connects another account on the same device.
        credentials.token = OTHER_TOKEN
        val second = preferences()
        val secondAccount = second.currentAccount()
        assertNotEquals(firstAccount, secondAccount)
        assertEquals(0, second.read().learnedToday)
        assertEquals(0, second.read().reviewedToday)
        assertFalse(second.wasStartedToday(7, secondAccount))
        // The goal is the device's, not the account's, so it is still there.
        assertEquals(3, second.read().dailyLimit)

        // An answer the first account's action earned, landing after the credential was replaced, is
        // not counted for the account that is here - and is not written at all.
        val writes = storage.activityWrites
        val dropped = second.recordReviewedSubject(11, firstAccount)
        assertEquals(writes, storage.activityWrites)
        assertEquals(0, dropped.reviewedToday)
        assertEquals(0, dropped.learnedToday)
    }

    @Test
    fun `a counter that cannot be written is reported instead of losing the review`() {
        val preferences = preferences()
        val account = preferences.currentAccount()
        preferences.recordStartedAssignment(7, account)
        storage.failActivityWrites = true

        val failed = preferences.recordReviewedSubject(11, account)

        assertTrue(failed.activityFailure)
        assertNull(failed.reviewedToday)
        // The day that is on disk is still readable, so the goal is still checked against it.
        assertEquals(1, failed.learnedToday)
        assertEquals(1, storage.activityWrites)

        // The accepted review is written nowhere else and sent nowhere: reading again reports the same
        // failure and spends no further write.
        val again = preferences.read()
        assertTrue(again.activityFailure)
        assertNull(again.reviewedToday)
        assertEquals(1, storage.activityWrites)

        // A write that does take clears the warning, and folds in what the failed one could not.
        storage.failActivityWrites = false
        val recovered = preferences.recordReviewedSubject(11, account)
        assertFalse(recovered.activityFailure)
        assertEquals(1, recovered.reviewedToday)
        assertEquals(1, recovered.learnedToday)
    }

    @Test
    fun `activity stored with no account beside it is not attributed to the connected account`() {
        val preferences = preferences()

        // The shape the unscoped counters have: a day and ids, and no account anywhere in them.
        storage.activity = """{"day":"$TODAY","started":["7"],"reviewed":["11"]}"""
        val unscoped = preferences.read()
        assertEquals(0, unscoped.learnedToday)
        assertNull(unscoped.reviewedToday)
        assertTrue(unscoped.activityFailure)

        // A document that is not one at all is read the same way rather than read as a guess.
        storage.activity = "{not a document"
        val corrupt = preferences.read()
        assertEquals(0, corrupt.learnedToday)
        assertNull(corrupt.reviewedToday)
    }

    @Test
    fun `the goal has a default and a range, and a goal that could not be saved is thrown`() {
        val preferences = preferences()
        // Nothing stored is not "no goal": the device works to the default.
        assertEquals(DEFAULT_DAILY_LESSONS, preferences.read().dailyLimit)

        // A number an older build could have left behind is read into the range, and is not itself
        // rewritten — the number on disk changes only when the reader saves a goal.
        storage.limit = 0
        assertEquals(DEFAULT_DAILY_LESSONS, preferences.read().dailyLimit)
        storage.limit = 1000
        assertEquals(MAX_DAILY_LESSONS, preferences.read().dailyLimit)
        assertEquals(1000, storage.limit)

        // Both ends of the range are goals; nothing outside it is.
        assertEquals(MIN_DAILY_LESSONS, preferences.setDailyLimit(1).dailyLimit)
        assertEquals(MAX_DAILY_LESSONS, preferences.setDailyLimit(20).dailyLimit)
        for (outside in listOf(0, 21)) {
            try {
                preferences.setDailyLimit(outside)
                fail("$outside is outside the range a device accepts")
            } catch (e: IllegalArgumentException) {
                // The range, checked before anything is stored.
            }
        }

        storage.failLimitWrites = true
        try {
            preferences.setDailyLimit(5)
            fail("a goal that was not saved must not answer as if it were")
        } catch (e: IllegalStateException) {
            assertEquals("The daily lesson goal could not be saved.", e.message)
        }
        // A write that did not take leaves the last goal that did.
        assertEquals(MAX_DAILY_LESSONS, preferences.read().dailyLimit)
    }

    /** A token store without a keystore; the counters are scoped to whatever it holds. */
    private class FakeCredentials : WaniKaniCredentials {
        var token: String? = A_TOKEN

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

    /** A store in a map, whose writes can be made to fail, and which counts the ones that were made. */
    private class FakeStorage : WaniKaniLearningStorage {
        var activity: String? = null

        /** What sits on disk: 0 until a goal is saved, as the preference file has it. */
        var limit: Int = 0
        var failActivityWrites = false
        var failLimitWrites = false
        var activityWrites = 0

        override fun activity(): String? = activity
        override fun dailyLimit(): Int = limit

        override fun saveActivity(activity: String): Boolean {
            if (failActivityWrites) return false
            this.activity = activity
            activityWrites++
            return true
        }

        override fun saveDailyLimit(limit: Int): Boolean {
            if (failLimitWrites) return false
            this.limit = limit
            return true
        }
    }

    private companion object {
        const val A_TOKEN = "a-token"
        const val OTHER_TOKEN = "another-token"
        const val TODAY = "2026-10-25"
        const val NEXT_DAY = "2026-10-26"
    }
}
