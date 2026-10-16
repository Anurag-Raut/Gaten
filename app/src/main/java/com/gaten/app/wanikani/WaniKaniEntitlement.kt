/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

/**
 * The content entitlement WaniKani's `GET /user` grants, and the only thing Gaten keeps from that
 * response.
 *
 * WaniKani's terms require a third-party client to respect the subscription's content limits. The
 * API hands out `max_level_granted` (a free account gets [FREE_LEVEL], a paid one [MAX_LEVEL]) and,
 * for a paid account that is not lifetime, the `period_ends_at` date at which that access ends - so
 * a client that keeps working offline has to hold on to the date and re-check it, which is why
 * [periodEndsAt] is stored and resolved on every read rather than once at connect time.
 */
internal data class WaniKaniEntitlement(
    val maxLevelGranted: Int,
    val type: Type,
    /** When the paid period ends, in epoch milliseconds; null for a free or lifetime account. */
    val periodEndsAt: Long?
) {

    /** What `subscription.type` may say. */
    enum class Type { FREE, RECURRING, LIFETIME, UNKNOWN }

    /**
     * The highest level this account may be shown at [now]. Free and unknown accounts stay at
     * [FREE_LEVEL], a lifetime account keeps what WaniKani granted, and a recurring one holds what
     * WaniKani granted only until its period ends - offline included. A recurring account whose
     * period end is missing or unreadable is treated as already ended, so a corrupt cache can never
     * grant more than a free account.
     */
    fun maxLevelAt(now: Long): Int {
        val granted = maxLevelGranted.coerceIn(FREE_LEVEL, MAX_LEVEL)
        return when (type) {
            Type.FREE, Type.UNKNOWN -> FREE_LEVEL
            Type.LIFETIME -> granted
            Type.RECURRING -> if (periodEndsAt != null && now < periodEndsAt) granted else FREE_LEVEL
        }
    }

    /** The JSON this is cached as; [decodeEntitlement] is the only reader. */
    fun toJson(): String =
        """{"max_level_granted":$maxLevelGranted,"type":"${type.name.lowercase()}",""" +
            """"period_ends_at":${periodEndsAt ?: "null"}}"""

    companion object {
        /** The level a free account is granted; WaniKani's own value for `max_level_granted`. */
        const val FREE_LEVEL = 3

        /** The highest level of content WaniKani has. */
        const val MAX_LEVEL = 60
    }
}
