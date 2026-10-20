/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * The view manager behind [GatenCardWebView]: the review card's — and the radical image's —
 * WebView, for JS that needs a WebView the stock react-native-webview cannot give (wrap-content
 * height, blocked network loads, no JavaScript).
 *
 * Three props: `document` (the full HTML; a repeated value never reloads the page, only the
 * background repaints), `backgroundColor` (any CSS colour the bridge's colour converter reads —
 * `#RRGGBB`, `transparent` — inherited from [com.facebook.react.uimanager.BaseViewManager], so it
 * lands as the ARGB int it already converts and is re-set on every update), and `mode` (`card`,
 * the default — reports its content height through `onCardHeight`; or `svg` — a fixed style
 * height, no height events). Card events arrive as `topCardHeight` with `height` in CSS pixels,
 * the unit RN styles use.
 */
class GatenCardWebViewManager : SimpleViewManager<GatenCardWebView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): GatenCardWebView =
        GatenCardWebView(reactContext)

    /** The renderer goes with the view: a dropped card stops loading and frees the WebView. */
    override fun onDropViewInstance(view: GatenCardWebView) {
        view.release()
    }

    /** Loads [document]; the view reloads only when it is not the document already on screen. */
    @ReactProp(name = "document")
    fun setDocument(view: GatenCardWebView, document: String?) {
        view.setDocument(document.orEmpty())
    }

    /**
     * The background, behind the card and on the WebView itself — the base class's own prop, kept
     * so a theme change re-paints the loaded card without a reload.
     */
    override fun setBackgroundColor(view: GatenCardWebView, color: Int) {
        view.setBackgroundColor(color)
    }

    @ReactProp(name = "mode")
    fun setMode(view: GatenCardWebView, mode: String?) {
        view.mode = when (mode) {
            "svg" -> GatenCardWebView.Mode.SVG
            else -> GatenCardWebView.Mode.CARD
        }
    }

    /** `onCardHeight` on JS, `topCardHeight` on native — a direct event, view-scoped. */
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        mapOf(
            GatenCardWebView.EVENT_TOP_CARD_HEIGHT to
                mapOf("registrationName" to JS_EVENT_CARD_HEIGHT)
        )

    companion object {
        const val NAME = "GatenCardWebView"

        const val JS_EVENT_CARD_HEIGHT = "onCardHeight"
    }
}
