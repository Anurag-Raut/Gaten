/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.media

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.gaten.app.capture.GatenCaptureService
import com.gaten.app.lifecycle.LookupDismissal
import com.gaten.app.lifecycle.NotificationEvent
import com.gaten.app.lifecycle.classifyNotificationEvent

/**
 * The notification listener, for the two things Gaten needs access to notifications for.
 *
 * [MediaPauser] reads the active media sessions through it — the platform gates
 * `MediaSessionManager.getActiveSessions` on notification access, and Gaten will not ask for the
 * signature permission — and the posted notifications themselves are the only signal left for the
 * interruptions that used to arrive as accessibility events: an incoming call or a firing alarm
 * takes the screen, and a lookup open at that moment closes before their own window appears.
 *
 * Only metadata is read, through [classifyNotificationEvent]: the category, the style template, the
 * published call type and whether there is a full-screen intent. No notification title, text or
 * other content is read, kept, logged or stored.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val dismissal = classifyNotificationEvent(
            NotificationEvent(
                packageName = sbn.packageName,
                ownPackage = packageName,
                category = notification.category,
                styleTemplate = notification.extras?.getString(Notification.EXTRA_TEMPLATE),
                callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    notification.extras?.getInt(
                        Notification.EXTRA_CALL_TYPE,
                        Notification.CallStyle.CALL_TYPE_UNKNOWN
                    )
                } else {
                    null
                },
                hasFullScreenIntent = notification.fullScreenIntent != null
            )
        )
        if (dismissal == LookupDismissal.None) return
        val service = GatenCaptureService.instance
        if (service == null) {
            Log.d(TAG, "$dismissal posted but lookup mode is off")
            return
        }
        service.dismissForInterruption(dismissal, sbn.packageName)
    }

    companion object {
        private const val TAG = "Gaten"
    }
}
