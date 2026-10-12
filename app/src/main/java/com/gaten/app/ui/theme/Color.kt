/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The palette is near-monochrome neutrals plus exactly one accent: indigo (藍).
 *
 * Only the roles Gaten actually draws with are overridden. The container roles
 * (primaryContainer, errorContainer, surfaceContainer*) are the tonal neighbours of those hues in
 * Material's own Indigo/Red palettes: leaving them at the library defaults would pull the baseline
 * purple scheme back into the UI, since dynamic colour is deliberately off.
 */

// --- Light ---

val LightBackground = Color(0xFFFAFAF9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F1EF)
val LightSurfaceContainer = Color(0xFFF1F1EF)
val LightSurfaceContainerHigh = Color(0xFFEAEAE7)
val LightSurfaceContainerHighest = Color(0xFFE4E4E1)
val LightOnSurface = Color(0xFF1A1A1A)
val LightOnSurfaceVariant = Color(0xFF6B6B6B)
val LightOutline = Color(0xFFE4E4E1)
val LightPrimary = Color(0xFF3F51B5)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE8EAF6)
val LightOnPrimaryContainer = Color(0xFF1A237E)
val LightError = Color(0xFFC62828)
val LightErrorContainer = Color(0xFFFFEBEE)
val LightOnErrorContainer = Color(0xFFB71C1C)

// --- Dark ---

val DarkBackground = Color(0xFF111111)
val DarkSurface = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF242424)
val DarkSurfaceContainer = Color(0xFF242424)
val DarkSurfaceContainerHigh = Color(0xFF2B2B2B)
val DarkSurfaceContainerHighest = Color(0xFF2E2E2E)
val DarkOnSurface = Color(0xFFEDEDED)
val DarkOnSurfaceVariant = Color(0xFF9A9A9A)
val DarkOutline = Color(0xFF2E2E2E)
val DarkPrimary = Color(0xFF9FA8DA)
val DarkOnPrimary = Color(0xFF111111)
val DarkPrimaryContainer = Color(0xFF283593)
val DarkOnPrimaryContainer = Color(0xFFE8EAF6)
val DarkError = Color(0xFFEF9A9A)
val DarkErrorContainer = Color(0xFFB71C1C)
val DarkOnErrorContainer = Color(0xFFFFEBEE)
