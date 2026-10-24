/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

/**
 * The lookup sheet's publish/ready handshake, kept apart from Android so it can be reasoned about
 * and tested on its own.
 *
 * A React surface takes a moment to load, and native can build the first result before the surface
 * has registered its JS listener; an event emitted then is simply lost. So native does not emit
 * until JS reports it is listening: [publish] only remembers the latest state, and [ready] flushes
 * it. Every state after that is delivered immediately. [attach] and [detach] reset the handshake
 * with the session, so a surface from an earlier lookup can never be handed a newer lookup's state.
 *
 * The payload is opaque here on purpose: the real one is a React `WritableMap`, but the state
 * machine does not care, which is what makes it unit-testable without a React runtime.
 */
internal class LookupSheetStateGate {

    private var latest: Any? = null
    private var listening = false

    /** A new session starts: nothing is remembered and JS is not listening yet. */
    fun attach() {
        latest = null
        listening = false
    }

    /** The session is gone: forget both the pending state and the ready mark. */
    fun detach() = attach()

    /**
     * Records [state] as the latest. Returns it when JS is already listening — the caller emits it —
     * or null when it is still loading, in which case [ready] will flush it later.
     */
    fun publish(state: Any?): Any? {
        latest = state
        return if (listening) state else null
    }

    /** JS is listening now. Returns the latest state to flush, or null when there is none yet. */
    fun ready(): Any? {
        listening = true
        return latest
    }

    /** The latest state, for a read that arrives out of band. */
    fun current(): Any? = latest
}
