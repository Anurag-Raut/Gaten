/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.lifecycle

/**
 * What a posted notification means for a lookup. Every case but [None] takes the lookup down — an
 * incoming call or a firing alarm, both interruptions the reader did not cause. Everything else (a
 * message, an upcoming-alarm reminder, a player notification) is [None]: those are left to the
 * window focus the overlay loses when something really does take the screen.
 */
enum class LookupDismissal { None, Call, Alarm }

/**
 * Notification metadata only: the style template, published call type, category and full-screen
 * intent. A CallStyle is stored in extras; the Notification object itself is not a CallStyle.
 */
data class NotificationEvent(
    val packageName: String?,
    val ownPackage: String,
    val category: String?,
    val styleTemplate: String?,
    val callType: Int?,
    val hasFullScreenIntent: Boolean
)

/**
 * Decides what a posted notification means for a lookup.
 *
 * Only two kinds qualify, and both are the notification that Android posts at the moment something
 * takes the screen: an **incoming call** — a `CallStyle` notification whose call type is incoming —
 * a legacy full-screen CALL notification without a published call type, or a **firing alarm**, an
 * ALARM-category notification with a full-screen intent. That is what
 * keeps a missed-call notification (same package, but not incoming), an upcoming-alarm reminder
 * (ALARM category, no full-screen intent) and an ordinary message or player notification from
 * ending a lookup. Gaten's own notifications never do. The package is not restricted: any app's
 * incoming call counts, not just the one that owns the default-dialer role.
 */
fun classifyNotificationEvent(event: NotificationEvent): LookupDismissal {
    val packageName = event.packageName
    if (packageName == null || packageName == event.ownPackage) return LookupDismissal.None
    if (event.styleTemplate == "android.app.Notification\$CallStyle" && event.callType == 1) {
        return LookupDismissal.Call
    }
    // Older platforms/notifications have no published call type. A full-screen CALL notification
    // is their ringing signal; a known ongoing/screening CallStyle is never mistaken for incoming.
    if (event.category == "call" && event.hasFullScreenIntent &&
        (event.callType == null || event.callType == 0)
    ) {
        return LookupDismissal.Call
    }
    if (event.category == "alarm" && event.hasFullScreenIntent) return LookupDismissal.Alarm
    return LookupDismissal.None
}

/**
 * Whether a lookup may start or a finished capture may go on screen: the screen has to be
 * interactive, i.e. not off, not in an always-on display and not asleep, and the keyguard has to be
 * unlocked, i.e. not locked and not showing. A trigger or a callback that resolves after the screen
 * went off or the phone locked is dropped rather than frozen and shown over the lock screen.
 */
fun lookupAdmitted(isInteractive: Boolean, isKeyguardLocked: Boolean): Boolean =
    isInteractive && !isKeyguardLocked

/** The screen orientations a frozen capture can be rotated away from. */
enum class DisplayOrientation { PORTRAIT, LANDSCAPE, SQUARE, UNDEFINED }

/**
 * Screen geometry captured at trigger time and display time. Dimensions, density and rotation must
 * still match; ordinary view layout and font changes do not affect these values. Rotation is kept
 * separately because a half-turn leaves orientation and dimensions unchanged.
 */
data class DisplayGeometry(
    val orientation: DisplayOrientation,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestScreenWidthDp: Int,
    val densityDpi: Int,
    val rotation: Int = 0
)

/** True when [next] puts the screen in a different shape than [previous] captured. */
fun displayChanged(previous: DisplayGeometry, next: DisplayGeometry): Boolean =
    previous.orientation != next.orientation ||
        previous.screenWidthDp != next.screenWidthDp ||
        previous.screenHeightDp != next.screenHeightDp ||
        previous.smallestScreenWidthDp != next.smallestScreenWidthDp ||
        previous.densityDpi != next.densityDpi ||
        previous.rotation != next.rotation

/**
 * The "is this capture still the one the user asked for?" token and the single-flight flag, kept
 * together because they are the same state: a capture is in flight exactly while [start] has run
 * and [complete] has not.
 *
 * The screenshot answers on the executor and every later step runs on the main thread, so between a
 * trigger and the frame it produces the user can lock the phone, leave the app, or trigger again.
 * Whoever starts a capture takes [start]; whoever finishes a step checks [isCurrent] first.
 * [complete] ends the capture **only if it is still the current one**, so an old callback that
 * resolves after a newer trigger cannot clear the newer trigger's in-flight state — the exact way a
 * lookup would otherwise be ignored for every trigger after the first. [abort] moves the token on
 * without starting anything, which is what a dismissal does.
 *
 * Main-thread only, like the window and the repository it guards: the executor only ever prepares a
 * private copy and posts.
 */
class CaptureToken {

    private var generation = 0L
    private var inFlight = false

    /** The token a new capture runs under; every earlier token and callback is now stale. */
    fun start(): Long {
        inFlight = true
        return ++generation
    }

    /** Moves the token on without starting a capture: whatever is in flight is now stale. */
    fun abort() {
        inFlight = false
        generation++
    }

    /** True while [token] is still the capture in flight. */
    fun isCurrent(token: Long): Boolean = generation == token

    /**
     * Ends the capture [token] belongs to, and only that one: false when a newer capture (or an
     * abort) has moved the token on, in which case the newer capture keeps the flag.
     */
    fun complete(token: Long): Boolean {
        if (!isCurrent(token)) return false
        inFlight = false
        return true
    }

    /** True while a capture has been accepted and has not resolved or been aborted. */
    val busy: Boolean
        get() = inFlight
}
