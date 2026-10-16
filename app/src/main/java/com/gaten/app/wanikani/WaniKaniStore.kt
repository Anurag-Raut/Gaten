/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The subject cache: one file per subject id, the character -> id index, one file per radical image,
 * the answers that there is nothing to cache, and the subscription entitlement.
 *
 * The entitlement and the two small indexes are kept; the content files are not:
 *
 * - **Positive content never expires.** A subject's meanings, mnemonics and readings do not change,
 *   so a subject that is cached is answered from the cache forever, with no request. Those entries,
 *   and the images, are what the [budgetBytes] bound applies to: past it the least recently used
 *   files are deleted (a read touches `lastModified`), oldest first, until the cache fits again.
 *   Never evicted: the entitlement - it is what a lookup is allowed to show - and the two indexes,
 *   which are tiny and load-bearing for the offline path.
 * - **Missing answers do expire.** WaniKani keeps adding subjects, so "there is no subject for this
 *   character" is recorded with the day it was observed, and the client decides how long to trust it;
 *   an entry that is unreadable, unparsable or older than that is a miss, and the next tap asks again.
 *
 * Reads never throw: a missing, unreadable or corrupt entry is simply a miss. Writes throw nothing
 * either, but every one of them answers whether the entry is on disk, and the caller decides what a
 * failed write means - a subject that could not be cached was still shown, while an entitlement that
 * could not be cached is the difference between gating content correctly offline and not, so that one
 * becomes [WaniKaniFailure.STORAGE] rather than being swallowed.
 *
 * Writes go through a unique temp file and a rename, so a concurrent reader sees the previous content
 * or the complete new one, never a half-written entry, and two writers of the same entry cannot
 * interleave. The temp file is removed on every path, and a failed rename leaves the entry that was
 * already there untouched - except on a filesystem that refuses to replace a file, where the old
 * entry is deleted so the new one can take its place, and a second failure is reported.
 *
 * [dir] is the cache root; the app puts it in `noBackupFilesDir`, beside the token but not inside it,
 * so clearing the cache never touches the credential.
 */
internal class WaniKaniStore(
    private val dir: File,
    private val budgetBytes: Long = CACHE_BUDGET_BYTES
) {

    /** The cached resource JSON of subject [id], or null when it is not cached. */
    fun subject(id: Int): String? = readTouched(File(subjectsDir, "$id.json"))

    /**
     * Caches the resource JSON of subject [id]; false when it is not on disk afterwards.
     *
     * A subject that exists ends any earlier "no such id" answer, which is forgotten here: an answer
     * left behind would come back into force if this file were later evicted by the budget, telling
     * the reader a subject does not exist while it does.
     */
    fun saveSubject(id: Int, json: String): Boolean {
        val saved = written(File(subjectsDir, "$id.json"), json)
        if (saved) forgetMissing(key(SUBJECT, id.toString()))
        return saved
    }

    /** Every cached character -> subject id pair. */
    fun index(): Map<String, Int> {
        val text = read(indexFile) ?: return emptyMap()
        val entries = LinkedHashMap<String, Int>()
        for (line in text.lineSequence()) {
            val separator = line.indexOf('\t')
            if (separator <= 0) continue
            val id = line.substring(separator + 1).toIntOrNull() ?: continue
            entries[line.substring(0, separator)] = id
        }
        return entries
    }

    /**
     * Records that [character] is subject [id], which is what lets a tap on an already seen kanji be
     * answered offline: the cache is keyed by subject id, and the id is only known from an index
     * entry, written by the lookup that fetched the subject and never guessed.
     *
     * An earlier "no such character" answer is forgotten with it - see [saveSubject] for why an
     * answer that has been overtaken must not be left lying around.
     */
    fun putIndex(character: String, id: Int): Boolean {
        val entries = index().toMutableMap()
        if (entries[character] == id) return true
        entries[character] = id
        val text = entries.entries.sortedBy { it.key }.joinToString(separator = "\n", postfix = "\n") {
            "${it.key}\t${it.value}"
        }
        val written = write(indexFile, text)
        if (written) forgetMissing(key(CHARACTER, character))
        return written
    }

    /** The cached SVG document of the image at [url], or null when it is not cached. */
    fun image(url: String): String? = readTouched(File(imagesDir, "${digest(url)}.svg"))

    /** Caches the SVG document of the image at [url]; false when it is not on disk afterwards. */
    fun saveImage(url: String, svg: String): Boolean = written(File(imagesDir, "${digest(url)}.svg"), svg)

    /** The epoch day WaniKani was last found to have no subject for [character], or null. */
    fun missingCharacter(character: String): Long? = missing()[key(CHARACTER, character)]

    /** Records that WaniKani has no subject for [character], as observed on [epochDay]. */
    fun putMissingCharacter(character: String, epochDay: Long): Boolean =
        putMissing(key(CHARACTER, character), epochDay)

    /** The epoch day WaniKani was last found to have no subject with [id], or null. */
    fun missingSubject(id: Int): Long? = missing()[key(SUBJECT, id.toString())]

    /** Records that WaniKani has no subject with [id], as observed on [epochDay]. */
    fun putMissingSubject(id: Int, epochDay: Long): Boolean = putMissing(key(SUBJECT, id.toString()), epochDay)

    /** The entitlement the last successful check stored. */
    fun entitlement(): WaniKaniEntitlement? = read(entitlementFile)?.let { decodeCachedEntitlement(it) }

    /** Stores the entitlement of the last successful check; false when it is not on disk afterwards. */
    fun saveEntitlement(entitlement: WaniKaniEntitlement): Boolean =
        write(entitlementFile, entitlement.toJson())

    /** Forgets every cached subject, index entry, image, missing answer and entitlement. */
    fun clear(): Boolean {
        dir.deleteRecursively()
        return !dir.exists()
    }

    /**
     * The not-found answers, keyed by what was asked about: `character`, or the subject id. Their own
     * file keeps the index - which is a map to subjects that exist - unambiguous, and keeps both tiny.
     */
    private fun missing(): Map<String, Long> {
        val text = read(missingFile) ?: return emptyMap()
        val entries = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val separator = line.indexOf('\t')
            if (separator <= 0) continue
            val day = line.substring(separator + 1).toLongOrNull() ?: continue
            if (day < 0) continue
            entries[line.substring(0, separator)] = day
        }
        return entries
    }

    private fun putMissing(key: String, epochDay: Long): Boolean {
        val entries = missing().toMutableMap()
        if (entries[key] == epochDay) return true
        entries[key] = epochDay
        val text = entries.entries.sortedBy { it.key }.joinToString(separator = "\n", postfix = "\n") {
            "${it.key}\t${it.value}"
        }
        return write(missingFile, text)
    }

    /** Drops a not-found answer that has been overtaken; nothing is written when there was none. */
    private fun forgetMissing(key: String): Boolean {
        val entries = missing().toMutableMap()
        if (entries.remove(key) == null) return true
        val text = entries.entries.sortedBy { it.key }.joinToString(separator = "\n", postfix = "\n") {
            "${it.key}\t${it.value}"
        }
        return write(missingFile, text)
    }

    // This mtime is only this cache's LRU clock; AnkiDroid's collection time does not apply.
    @Suppress("DirectSystemCurrentTimeMillisUsage")
    private fun readTouched(file: File): String? {
        val text = read(file) ?: return null
        file.setLastModified(System.currentTimeMillis())
        return text
    }

    /** Writes an entry, and then keeps the content inside its budget. */
    private fun written(file: File, text: String): Boolean {
        val saved = write(file, text)
        if (saved) evictOverBudget()
        return saved
    }

    /**
     * Deletes the least recently used content - subject files and images, oldest `lastModified` first
     * - until the cache is inside [budgetBytes] again.
     *
     * The entitlement, the character index and the not-found answers are never candidates: the first
     * is what content access is decided from, and the other two are a few lines that the offline path
     * cannot work without. A temp file of a write in flight is never a candidate either. A delete that
     * fails is skipped rather than reported - this is a bound on how much disk the cache may take, not
     * a promise it will take less.
     */
    private fun evictOverBudget() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        val evictable = files
            .filter { !it.name.endsWith(PART) }
            .filter { it.parentFile?.name == SUBJECTS || it.parentFile?.name == IMAGES }
            .sortedBy { it.lastModified() }
        for (file in evictable) {
            if (total <= budgetBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun read(file: File): String? = try {
        if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null
    } catch (e: IOException) {
        null
    }

    private fun write(file: File, text: String): Boolean = try {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(file.name, PART, file.parentFile)
        try {
            temp.writeText(text)
            if (temp.renameTo(file)) {
                true
            } else {
                file.delete()
                temp.renameTo(file)
            }
        } finally {
            temp.delete()
        }
    } catch (e: IOException) {
        false
    }

    /** A stable file name for a url, so the same image is never stored twice. */
    private fun digest(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private val subjectsDir get() = File(dir, SUBJECTS)
    private val imagesDir get() = File(dir, IMAGES)
    private val indexFile get() = File(dir, INDEX)
    private val missingFile get() = File(dir, MISSING)
    private val entitlementFile get() = File(dir, ENTITLEMENT)

    private companion object {
        const val SUBJECTS = "subjects"
        const val IMAGES = "images"
        const val INDEX = "index.tsv"
        const val MISSING = "missing.tsv"
        const val ENTITLEMENT = "entitlement.json"

        /** The suffix of the temp file an entry is written through before it is renamed onto it. */
        const val PART = ".part"

        /** What the whole cache may take before the least recently used content is deleted. */
        const val CACHE_BUDGET_BYTES = 50L * 1024 * 1024

        /** The namespaces of the not-found answers: a character, or a subject id. */
        const val CHARACTER = 'c'
        const val SUBJECT = 'i'

        fun key(namespace: Char, value: String) = "$namespace:$value"
    }
}
