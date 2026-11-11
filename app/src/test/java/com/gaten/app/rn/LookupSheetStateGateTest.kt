/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lookup sheet's publish/ready handshake: a state built before the RN surface is listening must
 * survive until it is, later states must go straight through, and a closed session must never leak
 * its state into the next one.
 */
class LookupSheetStateGateTest {

    @Test
    fun `a state published before ready is held and flushed on ready`() {
        val gate = LookupSheetStateGate()
        gate.attach()

        assertNull("nothing is emitted while the surface is still loading", gate.publish("first"))
        assertEquals("first", gate.ready())
    }

    @Test
    fun `only the latest state survives until ready`() {
        val gate = LookupSheetStateGate()
        gate.attach()

        gate.publish("primary")
        gate.publish("final")
        assertEquals("final", gate.ready())
    }

    @Test
    fun `a state published after ready goes straight through`() {
        val gate = LookupSheetStateGate()
        gate.attach()
        gate.ready()

        assertEquals("late", gate.publish("late"))
    }

    @Test
    fun `ready with no state yet flushes nothing`() {
        val gate = LookupSheetStateGate()
        gate.attach()

        assertNull(gate.ready())
        assertNull("and a later state still goes straight through", gate.publish(null))
        assertEquals("now", gate.publish("now"))
    }

    @Test
    fun `a closed session cannot leak its state into the next one`() {
        val gate = LookupSheetStateGate()
        gate.attach()
        gate.ready()
        gate.publish("old session")

        // The old surface goes away and a new lookup opens.
        gate.detach()
        gate.attach()

        assertNull("the old session's state is gone, not flushed into the new one", gate.ready())
        assertEquals("a new session's state still reaches it", "new", gate.publish("new"))
    }

    @Test
    fun `attach marks the surface as not yet listening`() {
        val gate = LookupSheetStateGate()
        gate.attach()
        gate.ready()
        gate.attach()
        assertNull("a fresh session is closed to events until it reports ready", gate.publish("x"))
    }
}
