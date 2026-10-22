/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.lifecycle

/**
 * What an accessibility signal means for a lookup. Every case but [None] takes the lookup down —
 * a call, a firing alarm or a system dialog — and these are interruptions the reader did not
 * cause. Everything else (an ordinary app window, a message notification, an upcoming-alarm
 * reminder) is [None]: those are left to the window focus the overlay loses when something really
 * does take the screen.
 */
enum class LookupDismissal { None, Call, Alarm, PowerMenu, SystemDialog }

/**
 * The signals a window-state event carries, kept free of Android so the policy is unit-tested
 * directly. Accessibility events do not expose the overlay's own window reliably, but Gaten's own
 * events are known by [ownPackage], and the window class and package are what distinguishes the
 * interruptions below.
 */
data class WindowEvent(
    val packageName: String?,
    val ownPackage: String,
    val windowClass: String? = null
)

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
 * The in-call screen of a dialer or a calling app, and the clock's own alert window. Matched by
 * class name: OEM dialers and communication apps keep their own packages, and package-visibility
 * filtering would not resolve the AOSP dialer's role for them anyway. The names are distinctive
 * enough (`InCallActivity`, `InCallUi`, `AlarmActivity`, `AlarmAlert`) that no ordinary app window
 * is caught by them.
 */
private val CALL_CLASS = Regex("(?i)incallactivity|incallui|def_call|callactivity")
private val ALARM_CLASS = Regex("(?i)alarmactivity|alarmalert|def_alarm")

/**
 * The system's own power menu. AOSP's `GlobalActionsDialog` window is an ordinary system window and
 * does not broadcast that it opened, so the window it puts up is the local signal: the SystemUI
 * global-actions window or the framework's dialog class. Ordinary app dialogs do not qualify.
 */
private val POWER_CLASS = Regex("(?i)globalactions|global_action|powerdialog|power_dialog|shutdown")
private val SYSTEM_UI_PACKAGE = Regex("(?i)^com\\.(android|oplus|oneplus)\\.systemui(?:\\.|$)")

/**
 * Decides what a window-state event means for a lookup.
 *
 * The in-call screen is recognised by its own class name in any package, so an OEM dialer or a
 * messaging app's call screen (`InCallActivity`, `InCallUi`) ends the lookup just as the stock
 * dialer's does — package-role queries would not resolve those on Android 11+ package visibility
 * anyway. A clock alarm screen is recognised by its alert class regardless of the OEM package, and the
 * power menu by SystemUI's global-actions window. Anything else, including Gaten's own overlay
 * windows, an ordinary app's activity and a notification shade, is [LookupDismissal.None]: there is
 * deliberately no rule that closes the lookup for whatever window comes forward, so a background
 * app raising a dialog or changing screen does not end a lookup the reader is still using — the
 * overlay's window focus covers a real app switch.
 */
fun classifyWindowEvent(event: WindowEvent): LookupDismissal {
    val packageName = event.packageName
    if (packageName == null || packageName == event.ownPackage) return LookupDismissal.None
    val windowClass = event.windowClass.orEmpty()
    if (windowClass.isEmpty()) return LookupDismissal.None

    if (CALL_CLASS.containsMatchIn(windowClass)) return LookupDismissal.Call
    if (ALARM_CLASS.containsMatchIn(windowClass)) return LookupDismissal.Alarm
    if (SYSTEM_UI_PACKAGE.containsMatchIn(packageName) && POWER_CLASS.containsMatchIn(windowClass)) {
        return LookupDismissal.PowerMenu
    }
    // Some SystemUI versions expose the framework Dialog class instead of GlobalActions.
    // Any system dialog taking the screen ends the lookup; ordinary app dialogs stay unaffected.
    if (SYSTEM_UI_PACKAGE.containsMatchIn(packageName) &&
        (windowClass == "android.app.Dialog" || windowClass == "android.app.AlertDialog" ||
            windowClass.endsWith("SystemUIDialog"))
    ) {
        return LookupDismissal.SystemDialog
    }
    return LookupDismissal.None
}

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
