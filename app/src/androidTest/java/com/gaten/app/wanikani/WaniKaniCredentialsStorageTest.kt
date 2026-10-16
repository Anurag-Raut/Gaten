/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The token file refusing the write, against the real Android Keystore: `save` has to report "not
 * stored" and leave nothing a later `read` could return, rather than believe the token went in.
 *
 * The token directory is redirected to a scratch dir under the test's own cache, so the app's real
 * file is never read, written or deleted. The save path generates its key *before* it touches the
 * file, so this test can leave a key behind on a device that already holds the app's token - which
 * is why it is skipped whenever that alias (or a real token) exists, and why it deletes only an
 * alias it proved it created itself.
 */
@RunWith(AndroidJUnit4::class)
class WaniKaniCredentialsStorageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "wk-credentials-test")

    /** The app's own key alias, from `KeystoreCredentials`; it is the one this test must not disturb. */
    private val alias = "gaten-wanikani-token"

    /** The dummy written in the second half: never sent anywhere, and never near the app's store. */
    private val dummy = "00000000-0000-0000-0000-000000000000"

    private var createdKey = false

    @Before
    fun skipWhenADeviceAlreadyHoldsTheToken() {
        assumeFalse(appKeyExists())
        assumeFalse(File(context.noBackupFilesDir, "wanikani/token").isFile)
        root.deleteRecursively()
    }

    @After
    fun forgetOnlyWhatThisTestCreated() {
        root.deleteRecursively()
        if (createdKey) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test
    fun refusedTokenWriteLeavesNoTokenAndSucceedsWhenWritable() {
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        val tokenDir = File(noBackup, "wanikani").apply { mkdirs() }
        try {
            assertTrue("the scratch token directory must not be writable", tokenDir.setWritable(false))

            val refused = credentialsIn(noBackup)
            val stored = refused.save(dummy)
            // Taken before any assertion below, so cleanup still removes the key the save path may
            // have generated on its way to the file even when one of those assertions fails.
            createdKey = appKeyExists()

            assertFalse(stored)
            assertFalse(refused.hasToken())
            assertNull(refused.read())
            assertFalse(File(tokenDir, "token").exists())
        } finally {
            tokenDir.setWritable(true)
        }

        // The same store, with a directory that takes writes again: the refusal above was the
        // directory's, and nothing else about the store is broken.
        val working = credentialsIn(noBackup)
        val stored = working.save(dummy)
        createdKey = appKeyExists()

        assertTrue(stored)
        assertEquals(dummy, working.read())
    }

    /** The real store, with its `noBackupFilesDir` pointed at a scratch directory. */
    private fun credentialsIn(noBackup: File): WaniKaniCredentials =
        KeystoreCredentials(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = noBackup
        })

    private fun appKeyExists(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
}
