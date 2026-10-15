/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one rule that decides which content may be shown: what the subscription grants, and when that
 * grant stops being true while the app has heard nothing from WaniKani.
 */
class WaniKaniEntitlementTest {

    @Test
    fun `a free account is granted the free level`() {
        assertEquals(3, WaniKaniEntitlement(3, WaniKaniEntitlement.Type.FREE, null).maxLevelAt(0))
        // Even a body that contradicts itself: no paid type, no paid content.
        assertEquals(3, WaniKaniEntitlement(60, WaniKaniEntitlement.Type.FREE, null).maxLevelAt(0))
    }

    @Test
    fun `an unknown subscription type is treated as free`() {
        assertEquals(3, WaniKaniEntitlement(60, WaniKaniEntitlement.Type.UNKNOWN, null).maxLevelAt(0))
        assertEquals(3, WaniKaniEntitlement(3, WaniKaniEntitlement.Type.UNKNOWN, null).maxLevelAt(0))
    }

    @Test
    fun `a lifetime account keeps what WaniKani granted`() {
        val lifetime = WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null)
        assertEquals(60, lifetime.maxLevelAt(0))
        assertEquals(60, lifetime.maxLevelAt(Long.MAX_VALUE))
    }

    @Test
    fun `a recurring account holds its grant until the period ends`() {
        val recurring = WaniKaniEntitlement(60, WaniKaniEntitlement.Type.RECURRING, 1_000L)
        assertEquals(60, recurring.maxLevelAt(0))
        assertEquals(60, recurring.maxLevelAt(999))
        assertEquals(3, recurring.maxLevelAt(1_000))
        assertEquals(3, recurring.maxLevelAt(9_999))
    }

    @Test
    fun `a recurring account with no readable period end is treated as expired`() {
        assertEquals(3, WaniKaniEntitlement(60, WaniKaniEntitlement.Type.RECURRING, null).maxLevelAt(0))
    }

    @Test
    fun `the granted level is clamped to the levels WaniKani has`() {
        assertEquals(3, WaniKaniEntitlement(0, WaniKaniEntitlement.Type.LIFETIME, null).maxLevelAt(0))
        assertEquals(60, WaniKaniEntitlement(9_999, WaniKaniEntitlement.Type.LIFETIME, null).maxLevelAt(0))
    }

    @Test
    fun `a cached entitlement survives a round trip`() {
        val entitlements = listOf(
            WaniKaniEntitlement(3, WaniKaniEntitlement.Type.FREE, null),
            WaniKaniEntitlement(60, WaniKaniEntitlement.Type.RECURRING, 1_544_495_139_485L),
            WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null),
            WaniKaniEntitlement(60, WaniKaniEntitlement.Type.UNKNOWN, null)
        )
        for (entitlement in entitlements) {
            assertEquals(entitlement, decodeCachedEntitlement(entitlement.toJson()))
        }
    }

    @Test
    fun `an unreadable cached entitlement is no entitlement, and never a grant`() {
        assertNull(decodeCachedEntitlement("not json"))
        assertNull(decodeCachedEntitlement(""))
        assertNull(decodeCachedEntitlement("[1, 2]"))

        // Readable, but with nothing usable in it: free levels, not the 60 a truncated file claims.
        val nothing = decodeCachedEntitlement("""{"nothing":"recurring"}""")!!
        assertEquals(WaniKaniEntitlement.Type.UNKNOWN, nothing.type)
        assertEquals(3, nothing.maxLevelAt(0))

        val writtenWithoutPeriod = """{"max_level_granted":60,"type":"recurring","period_ends_at":null}"""
        assertEquals(3, decodeCachedEntitlement(writtenWithoutPeriod)!!.maxLevelAt(0))

        val truncated = """{"max_level_granted":60,"type":"recurring"}"""
        assertEquals(3, decodeCachedEntitlement(truncated)!!.maxLevelAt(0))
    }
}
