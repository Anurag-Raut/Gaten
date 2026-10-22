/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure policy behind the overlay lifecycle: when a lookup may run, what a window event and a
 * posted notification mean for one that is up, when a frozen capture stops matching the display,
 * and the token that both gates a second capture and keeps a late one from clearing a newer.
 */
class LookupLifecyclePolicyTest {

    private val ownPackage = "com.gaten.app"
    private val dialer = "com.android.dialer"
    private val systemUi = "com.android.systemui"
    private val clock = "com.android.deskclock"

    private fun window(packageName: String, windowClass: String? = null) =
        WindowEvent(packageName = packageName, ownPackage = ownPackage, windowClass = windowClass)

    private fun notification(
        packageName: String,
        category: String? = null,
        callStyle: Boolean = false,
        callType: Int? = null,
        fullScreen: Boolean = false
    ) = NotificationEvent(
        packageName = packageName,
        ownPackage = ownPackage,
        category = category,
        styleTemplate = if (callStyle) "android.app.Notification\$CallStyle" else null,
        callType = callType,
        hasFullScreenIntent = fullScreen
    )

    // --- admission: the screen has to be on and unlocked ---

    @Test
    fun theScreenBeingOnAndUnlockedIsTheOnlyAdmittedState() {
        assertTrue(lookupAdmitted(isInteractive = true, isKeyguardLocked = false))
    }

    @Test
    fun aScreenOffIncludingAlwaysOnDisplayAndSleepLocksTheLookupOut() {
        assertFalse(lookupAdmitted(isInteractive = false, isKeyguardLocked = false))
    }

    @Test
    fun aLockedPhoneLocksTheLookupOut() {
        assertFalse(lookupAdmitted(isInteractive = true, isKeyguardLocked = true))
    }

    // --- window events: a call, a firing alarm, the power menu ---

    @Test
    fun anIncomingCallFromTheStockDialerEndsTheLookup() {
        assertEquals(
            LookupDismissal.Call,
            classifyWindowEvent(window(dialer, "com.android.dialer.incall.InCallActivity"))
        )
    }

    @Test
    fun anOemOrMessagingInCallScreenEndsTheLookupDespiteItsPackage() {
        // Any in-call screen is a call; OEM dialers and communication apps keep their own packages,
        // and package-visibility queries would not resolve their role anyway.
        assertEquals(
            LookupDismissal.Call,
            classifyWindowEvent(window("com.oneplus.dialer", "com.android.dialer.InCallActivity"))
        )
        assertEquals(
            LookupDismissal.Call,
            classifyWindowEvent(window("com.whatsapp", "com.whatsapp.calling.ui.InCallActivity"))
        )
    }

    @Test
    fun theDialerAndChatWindowsThatAreNotInCallDoNotEndTheLookup() {
        assertEquals(
            LookupDismissal.None,
            classifyWindowEvent(window(dialer, "com.android.dialer.dialpad.DialtactsActivity"))
        )
        assertEquals(
            LookupDismissal.None,
            classifyWindowEvent(window("com.example.chat", "com.example.chat.ChatActivity"))
        )
    }

    @Test
    fun theClocksOwnAlarmWindowEndsTheLookup() {
        assertEquals(
            LookupDismissal.Alarm,
            classifyWindowEvent(window(clock, "com.android.deskclock.alarms.AlarmActivity"))
        )
    }

    @Test
    fun anOemAlarmAlertEndsTheLookup() {
        assertEquals(
            LookupDismissal.Alarm,
            classifyWindowEvent(window("com.oplus.alarmclock", "com.oplus.alarmclock.AlarmAlertFullScreen"))
        )
    }

    @Test
    fun aFrameworkSystemDialogEndsTheLookupButAnAppDialogDoesNot() {
        assertEquals(
            LookupDismissal.SystemDialog,
            classifyWindowEvent(window(systemUi, "android.app.Dialog"))
        )
        assertEquals(
            LookupDismissal.None,
            classifyWindowEvent(window("com.example.reader", "android.app.Dialog"))
        )
    }

    @Test
    fun theSystemPowerMenuEndsTheLookup() {
        assertEquals(
            LookupDismissal.PowerMenu,
            classifyWindowEvent(window(systemUi, "com.android.systemui.globalactions.GlobalActionsDialog"))
        )
    }

    @Test
    fun aPowerLookingClassOutsideSystemUiDoesNotEndTheLookup() {
        // "Poweramp"/"Power" titles and classes in arbitrary apps must not close anything.
        assertEquals(
            LookupDismissal.None,
            classifyWindowEvent(window("com.maxmpz.poweramp", "com.maxmpz.poweramp.PowerActivity"))
        )
    }

    @Test
    fun gatenOwnWindowsNeverEndALookup() {
        assertEquals(
            LookupDismissal.None,
            classifyWindowEvent(window(ownPackage, "com.android.dialer.incall.InCallActivity"))
        )
        assertEquals(LookupDismissal.None, classifyWindowEvent(window(ownPackage)))
    }

    // --- notifications: incoming call and firing alarm only ---

    @Test
    fun anIncomingCallNotificationEndsTheLookupWhateverTheApp() {
        // Not just the default dialer: any app's incoming call (CallStyle, incoming type) counts.
        assertEquals(
            LookupDismissal.Call,
            classifyNotificationEvent(notification("com.whatsapp", callStyle = true, callType = 1))
        )
        assertEquals(
            LookupDismissal.Call,
            classifyNotificationEvent(notification(dialer, callStyle = true, callType = 1))
        )
    }

    @Test
    fun aMissedCallNotificationDoesNotEndTheLookup() {
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(notification(dialer, category = "missed_call"))
        )
    }

    @Test
    fun aLegacyRingingCallEndsTheLookupButAnOngoingCallDoesNot() {
        assertEquals(
            LookupDismissal.Call,
            classifyNotificationEvent(notification(dialer, category = "call", fullScreen = true))
        )
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(
                notification(dialer, category = "call", callStyle = true, callType = 2, fullScreen = true)
            )
        )
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(notification(dialer, category = "call"))
        )
    }

    @Test
    fun aFiringAlarmNotificationWithAFullScreenIntentEndsTheLookup() {
        assertEquals(
            LookupDismissal.Alarm,
            classifyNotificationEvent(
                notification(clock, category = "alarm", fullScreen = true)
            )
        )
    }

    @Test
    fun anUpcomingAlarmReminderDoesNotEndTheLookup() {
        // The clock's "alarm in 30 minutes" notification is ALARM category without a full-screen
        // intent; only the one that actually fires shows the intent.
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(
                notification(clock, category = "alarm", fullScreen = false)
            )
        )
    }

    @Test
    fun messagesMusicAndGatenOwnNotificationsDoNotEndALookup() {
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(notification("com.example.chat", category = "msg"))
        )
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(notification("com.example.player", category = "transport"))
        )
        assertEquals(
            LookupDismissal.None,
            classifyNotificationEvent(
                notification(ownPackage, callStyle = true, callType = 1)
            )
        )
    }

    // --- display changes: a frozen capture stops matching its screen ---

    private fun geometry(
        orientation: DisplayOrientation = DisplayOrientation.PORTRAIT,
        width: Int = 412,
        height: Int = 915,
        smallestWidth: Int = 412,
        densityDpi: Int = 420
    ) = DisplayGeometry(orientation, width, height, smallestWidth, densityDpi)

    @Test
    fun aRotationUnderTheFrozenCaptureIsADisplayChange() {
        assertTrue(displayChanged(geometry(), geometry(orientation = DisplayOrientation.LANDSCAPE)))
    }

    @Test
    fun aHalfTurnChangesTheDisplayEvenWhenSizeAndOrientationStayTheSame() {
        assertTrue(displayChanged(geometry(), geometry().copy(rotation = 2)))
    }

    @Test
    fun aFoldingPhoneChangingTheSmallestWidthIsADisplayChange() {
        assertTrue(displayChanged(geometry(), geometry(width = 673, smallestWidth = 673)))
    }

    @Test
    fun aDisplaySwapChangingTheDensityIsADisplayChange() {
        assertTrue(displayChanged(geometry(), geometry(densityDpi = 320)))
    }

    @Test
    fun anOrdinaryLayoutRoundWithoutStateChangeIsNotADisplayChange() {
        assertFalse(displayChanged(geometry(), geometry()))
    }

    // --- capture token: the single-flight gate that a stale callback cannot release ---

    @Test
    fun aNewTriggerMakesTheFirstCapturesCallbackStale() {
        val token = CaptureToken()
        val first = token.start()
        val second = token.start()

        assertFalse("the first capture must not clobber the second", token.isCurrent(first))
        assertTrue(token.isCurrent(second))
    }

    @Test
    fun aSuccessfulCaptureCompletesOnlyItselfAndFreesTheNextTrigger() {
        val token = CaptureToken()
        val first = token.start()
        assertTrue(token.busy)

        assertTrue(token.complete(first))
        assertFalse("the next trigger must be accepted", token.busy)
    }

    @Test
    fun aStaleFailureDoesNotClearTheNewerCapturesRequest() {
        val token = CaptureToken()
        val first = token.start()
        val second = token.start()

        // The old capture resolving (success or failure) must not clear the newer one's request:
        // this is the case where the first lookup's completion would otherwise leave every later
        // trigger ignored.
        assertFalse(token.complete(first))
        assertTrue("the newer capture is still in flight", token.busy)
        assertTrue(token.complete(second))
        assertFalse(token.busy)
    }

    @Test
    fun aDismissalAbortsTheCaptureAndFreesTheNextTrigger() {
        val token = CaptureToken()
        val first = token.start()

        // Screen off, a call, the user leaving: the pending capture is aborted, so its late
        // screenshot is stale and the next trigger is accepted.
        token.abort()
        assertFalse(token.isCurrent(first))
        assertFalse(token.complete(first))
        assertFalse(token.busy)
    }
}
