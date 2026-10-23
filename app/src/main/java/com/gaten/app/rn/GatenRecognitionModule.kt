/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.ocr.RecognitionPreference

/** Reads and stores the user's selected OCR reader. */
class GatenRecognitionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun isMangaOcrEnabled(promise: Promise) {
        promise.resolve(RecognitionPreference.isMangaOcrEnabled(reactApplicationContext))
    }

    @ReactMethod
    fun setMangaOcrEnabled(enabled: Boolean, promise: Promise) {
        RecognitionPreference.setMangaOcrEnabled(reactApplicationContext, enabled)
        promise.resolve(null)
    }

    companion object {
        const val NAME = "GatenRecognition"

    }
}
