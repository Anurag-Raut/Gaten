/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.media

import android.service.notification.NotificationListenerService

/**
 * Empty notification listener, declared only so [MediaPauser] can read the media sessions.
 *
 * The platform gates [android.media.session.MediaSessionManager.getActiveSessions] on the caller
 * holding either `MEDIA_CONTENT_CONTROL` (signature|system) or notification-listener access, and
 * Gaten will not ask for the former. This service is the component that access is checked against;
 * it deliberately does nothing with the notifications it is handed.
 *
 * It is not the signal for an interruption: an incoming call or a firing alarm arrives as an
 * ordinary `TYPE_NOTIFICATION_STATE_CHANGED` accessibility event, which the accessibility service
 * already receives and acts on. Nothing here is required for that, or for a lookup at all.
 */
class MediaNotificationListener : NotificationListenerService()
