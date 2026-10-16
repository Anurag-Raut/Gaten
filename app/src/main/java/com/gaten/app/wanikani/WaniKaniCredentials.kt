/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the personal access token lives. An interface so the lookup logic can be tested without a
 * keystore; the app always uses [KeystoreCredentials].
 */
internal interface WaniKaniCredentials {

    /** Whether a token is stored. Cheap: it reads no secret and touches no keystore. */
    fun hasToken(): Boolean

    /** The token, or null when none is stored, or the stored one can no longer be read. */
    fun read(): String?

    /** Stores [token]. False when the keystore or the file refused it. */
    fun save(token: String): Boolean

    /** Forgets the token and the key that encrypted it. True only when neither is left behind. */
    fun clear(): Boolean
}

/**
 * The token in the Android Keystore: an AES-256 key that never leaves it, and beside it, in
 * `noBackupFilesDir`, the AES/GCM ciphertext and its IV.
 *
 * Why it is built this way:
 *  - The key is non-exportable, so the ciphertext alone is useless - reading the file off the device
 *    gets nobody a token.
 *  - It is under `noBackupFilesDir`, so the token is never copied off the device by Android's backup,
 *    and no backup-rule file has to list it.
 *  - Nothing here logs, and nothing here is ever given the token's content beyond `save` and `read`.
 *  - A ciphertext that can no longer be decrypted (the key is gone: a reinstall, a restore, a
 *    cleared keystore) is deleted instead of being reported, so the app falls back to "not
 *    configured" rather than to a token that cannot work.
 */
internal class KeystoreCredentials(context: Context) : WaniKaniCredentials {

    private val file = File(File(context.noBackupFilesDir, DIR), TOKEN)

    override fun hasToken(): Boolean = file.isFile

    override fun read(): String? {
        val stored = try {
            if (file.isFile) file.readText() else return null
        } catch (e: IOException) {
            return null
        }
        val parts = stored.trim().split(':', limit = 2)
        if (parts.size != 2) {
            clear()
            return null
        }
        return try {
            val key = key(createIfMissing = false)
            if (key == null) {
                clear()
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, base64(parts[0])))
            String(cipher.doFinal(base64(parts[1])), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        }
    }

    override fun save(token: String): Boolean {
        try {
            val key = key(createIfMissing = true) ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            // The same temp-then-rename as the cache: a half-written token file is never readable.
            val temp = File.createTempFile(file.name, PART, file.parentFile)
            try {
                temp.writeText(
                    "${Base64.encodeToString(cipher.iv, FLAGS)}:${Base64.encodeToString(ciphertext, FLAGS)}"
                )
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) {
                        return false
                    }
                }
            } finally {
                temp.delete()
            }
            return true
        } catch (e: GeneralSecurityException) {
            return false
        } catch (e: IOException) {
            return false
        }
    }

    override fun clear(): Boolean {
        file.delete()
        try {
            keystore().deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            // Already gone, or the keystore will not take the request; the check below decides.
        } catch (e: IOException) {
            // Same.
        }
        // The token is forgotten once its ciphertext is gone and the key cannot be retrieved. Both
        // are checked, so a clear that only half worked is reported rather than assumed.
        return !hasToken() && key(createIfMissing = false) == null
    }

    /** The Gaten key, when it exists, or a freshly generated one if asked for. */
    private fun key(createIfMissing: Boolean): SecretKey? = try {
        val existing = keystore().getKey(ALIAS, null) as? SecretKey
        when {
            existing != null -> existing
            createIfMissing -> generateKey()
            else -> null
        }
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun base64(text: String): ByteArray = Base64.decode(text, FLAGS)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "gaten-wanikani-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val DIR = "wanikani"
        const val TOKEN = "token"
        const val FLAGS = Base64.NO_WRAP

        /** The suffix of the temp file the token is written through before it is renamed onto it. */
        const val PART = ".part"
    }
}
