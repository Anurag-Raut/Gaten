/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What one sentence's translation is, as the sheet has to present it.
 *
 * [None] is not a failure: it is "there is no translation to show here", either because the models
 * were never downloaded or because there was nothing to translate. A missing model must never
 * surface as an error, since the reader can still read the sentence itself.
 */
sealed interface SentenceTranslation {
    data object None : SentenceTranslation

    data object Translating : SentenceTranslation

    data class Done(val text: String) : SentenceTranslation

    data class Failed(val reason: String) : SentenceTranslation
}

/**
 * On-device Japanese -> English sentence translation, through ML Kit's on-device translation.
 *
 * The language models are downloaded on demand into the app's own storage and are not part of the
 * APK; after that every translation runs on the device with no network at all. Nothing here ever
 * downloads on its own: [download] is called from the home screen's Download button, and only from
 * there, so the app's network behaviour is the reader's decision and never a side effect of a
 * lookup. A lookup that finds no model simply gets [SentenceTranslation.None].
 *
 * Attribution: ML Kit's translation guidelines require the Google Translate attribution and
 * disclaimer wherever a translation is shown. The sheet carries the attribution and the home
 * screen carries the disclaimer; see `README.md`.
 */
object SentenceTranslator {

    /**
     * What the two language models are expected to take, for the settings screen's warning. Google
     * publishes ~30 MB per language model and JA -> EN needs the Japanese one and the English one.
     */
    const val APPROX_SIZE_MB = 60

    /**
     * All of ML Kit's own objects are built on demand rather than held in fields: this object must
     * stay constructible without an Android runtime — [stateFor] is pure and unit-tested on the JVM
     * — and the models and the client are cheap to describe.
     */
    private fun options(): TranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.JAPANESE)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    private fun sourceModel(): TranslateRemoteModel =
        TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()

    private fun targetModel(): TranslateRemoteModel =
        TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()

    /** A translator client. Cheap to build and touches nothing; [Translator.close] it when done. */
    fun newClient(): Translator = Translation.getClient(options())

    /**
     * True when both language models are on the device. False when they are not, and false when the
     * question cannot be answered at all — the sheet then shows the sentence without a translation
     * rather than an error about a feature it may never have been asked to set up. Never throws.
     */
    suspend fun isInstalled(): Boolean {
        val manager = RemoteModelManager.getInstance()
        return try {
            manager.isModelDownloaded(sourceModel()).await() &&
                manager.isModelDownloaded(targetModel()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Downloads both language models. Null when they are in place afterwards, the reason otherwise.
     * Nothing calls this except the home screen's Download button, after the reader has been shown
     * the size. Never throws.
     */
    suspend fun download(requireWifi: Boolean): String? {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        val manager = RemoteModelManager.getInstance()
        return try {
            manager.download(sourceModel(), conditions).await()
            manager.download(targetModel(), conditions).await()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Deletes both language models from the device. Never throws. */
    suspend fun remove() {
        val manager = RemoteModelManager.getInstance()
        try {
            manager.deleteDownloadedModel(sourceModel()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        try {
            manager.deleteDownloadedModel(targetModel()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * [text] in English, as the state the sheet shows. Blank text is nothing to translate; a model
     * that is not there is [SentenceTranslation.None] rather than a failure; anything else is a
     * failure carrying its reason. Cancellation still cancels, as Kotlin requires.
     */
    suspend fun translate(translator: Translator, text: String): SentenceTranslation {
        if (text.isBlank()) return SentenceTranslation.None
        return try {
            SentenceTranslation.Done(translator.translate(text).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stateFor(e)
        }
    }

    /**
     * The state a failure means. `NOT_FOUND` is ML Kit's answer when the language model was never
     * downloaded — the reader never asked for the feature, which is [SentenceTranslation.None], not
     * something to report.
     */
    fun stateFor(error: Throwable): SentenceTranslation =
        if (error is MlKitException) {
            stateForCode(error.errorCode, error.message)
        } else {
            SentenceTranslation.Failed(error.message ?: error.javaClass.simpleName)
        }

    /**
     * The state one ML Kit error code means, split out of [stateFor] because an [MlKitException]
     * cannot be built off a device — its constructor asks `TextUtils` whether the message is empty —
     * while this is plain arithmetic on the code. Pure, and unit-tested directly.
     */
    internal fun stateForCode(errorCode: Int, message: String?): SentenceTranslation =
        if (errorCode == MlKitException.NOT_FOUND) {
            SentenceTranslation.None
        } else {
            SentenceTranslation.Failed(message ?: "ML Kit error $errorCode")
        }

    /**
     * Bridges an ML Kit [Task] into coroutines without kotlinx-coroutines-play-services, the same
     * way [com.gaten.app.ocr.JapaneseOcr] does. Cancelling the coroutine abandons the wait; the
     * task itself still runs to completion, which is the platform's behaviour and not ours to stop.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
