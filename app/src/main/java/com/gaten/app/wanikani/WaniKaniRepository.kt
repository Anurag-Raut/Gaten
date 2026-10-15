/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.Context
import java.io.File

/**
 * What the UI calls: the connection to WaniKani, and the reference content behind a tapped character.
 *
 * The app is an unofficial, third-party WaniKani reference. WaniKani is used for content only - the
 * meanings, readings, mnemonics and radical relationships of the subject the reader just tapped - and
 * the one account-scoped request, `GET /user`, exists solely because WaniKani's terms require a
 * client to respect what the subscription grants. Nothing WaniKani owns is bundled in the APK, no
 * dictionary or Anki path here reads it, and nothing is ever sent back to WaniKani.
 *
 * Everything is synchronous-or-suspend over the app's own directories: the credential in
 * `noBackupFilesDir/wanikani/token` and the cache in `noBackupFilesDir/wanikani/cache`, so neither
 * is copied off the device by Android's backup and clearing the cache can never touch the token.
 * A client is built per call; the operations themselves are serialized inside it, and a lookup is
 * safe from any dispatcher with any context.
 */
object WaniKaniRepository {

    /** Whether a token is stored; cheap enough to call while a screen composes. */
    fun isConfigured(context: Context): Boolean = client(context).isConfigured()

    /**
     * Checks [token] with WaniKani and stores it when it works. The check is also what refreshes the
     * subscription's content entitlement.
     */
    suspend fun connect(context: Context, token: String): WaniKaniConnection = client(context).connect(token)

    /** Checks the stored token again, e.g. from a "recheck subscription" action. */
    suspend fun reconnect(context: Context): WaniKaniConnection = client(context).reconnect()

    /**
     * Forgets the token and every cached subject, image and entitlement. False when either the token
     * or something in the cache could not be cleared, so the caller can say so instead of promising a
     * clean device.
     */
    suspend fun disconnect(context: Context): Boolean = client(context).disconnect()

    /**
     * The content behind one tap. Reads the cache first (a subject the reader has already seen is
     * answered with no request at all); [refresh] skips the cache and asks WaniKani, falling back to
     * the cached copy - marked stale - when that fails.
     */
    suspend fun lookup(
        context: Context,
        target: WaniKaniTarget,
        refresh: Boolean = false
    ): WaniKaniLookup = client(context).lookup(target, refresh)

    /** A client over the app's own directories, cheap enough to build per call. */
    private fun client(context: Context): WaniKaniClient {
        val app = context.applicationContext
        return WaniKaniClient(File(app.noBackupFilesDir, CACHE), KeystoreCredentials(app))
    }

    /**
     * The cache root: beside the token file (`wanikani/token`), never inside it, so clearing the
     * cache on disconnect cannot delete the credential.
     */
    private const val CACHE = "wanikani/cache"
}
