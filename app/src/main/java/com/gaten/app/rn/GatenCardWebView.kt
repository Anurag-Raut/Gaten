/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The review card's WebView, as a plain Android view under a classic view manager.
 *
 * react-native-webview can neither size itself to its content (an undefined style height lands at
 * 0px, and measuring from injected JavaScript would need the one thing the spec bans here) nor
 * block network loads from props, so the card renders in this own view instead: a WebView
 * configured exactly like [com.gaten.app.ui.anki.AnkiReviewScreen]'s CardHtml — JavaScript off,
 * every form of storage and content access off, network loads blocked — wrapped in a frame whose
 * measure answers the document's own height, so the screen needs no fixed height to show a card.
 *
 * The document is loaded once and kept: an update whose document equals the loaded one re-paints
 * only the background, so a theme change (or any other prop churn) never reloads the page. The
 * loaded document is held in a field, not in the view's [android.view.View.setTag] — React Native
 * keeps its own bookkeeping on the shared tag slot.
 */
class GatenCardWebView(context: Context) : FrameLayout(context) {
    /** What the view is showing: `card` (review card, reports its height) or `svg` (fixed height). */
    enum class Mode { CARD, SVG }

    /** The WebView itself; configured once, here. */
    private val webView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            setSupportZoom(false)
            useWideViewPort = true
        }
        // The card is AnkiDroid's HTML on the reader's page: it is read, not touched, and a tap on
        // it is the screen's business.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isClickable = false
        isFocusable = false
        isEnabled = false
        contentDescription = CONTENT_DESCRIPTION
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        webViewClient = object : WebViewClient() {
            // Nothing on a card is a link the reader follows from here: every navigation is
            // refused, whatever it claims to be.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            /**
             * The document's height is reported as soon as the WebView has it laid out — the commit
             * that precedes the first paint — and again when the page finishes, which is what
             * catches a layout that was not ready at the commit. [reportContentHeight] ignores a
             * height of zero, so the earlier call can never report a document with no layout: a
             * card is never drawn at a height its content has not reached.
             */
            override fun onPageCommitVisible(view: WebView, url: String?) {
                reportContentHeight(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                reportContentHeight(view)
            }
        }
    }

    /** The document currently loaded, so a repeated prop value never reloads the page. */
    private var loadedDocument: String? = null

    /** The last background the manager set, so a first load paints in the theme at once. */
    private var lastColor: Int = 0

    /** The content height last reported, in CSS pixels; 0 until the first page finished. */
    private var reportedCss = 0f

    /** `svg` mode renders at a fixed style height and reports none. */
    var mode: Mode = Mode.CARD

    init {
        addView(webView)
    }

    /**
     * Shows [document], reloading only when it is not the document already on screen. The
     * background carried here is the last one [setBackgroundColor] set, so the very first paint is
     * already the theme's and a theme change later repaints without a reload.
     */
    fun setDocument(document: String) {
        if (document == loadedDocument) return
        loadedDocument = document
        reportedCss = 0f
        webView.setBackgroundColor(lastColor)
        webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
    }

    /**
     * Paints the background alone: a theme change re-colours the loaded card. Both layers take it
     * — the frame, and the WebView whose own white default would otherwise win.
     */
    override fun setBackgroundColor(backgroundColor: Int) {
        lastColor = backgroundColor
        super.setBackgroundColor(backgroundColor)
        webView.setBackgroundColor(backgroundColor)
    }

    /** Stops the load and tears the renderer down; the manager calls this as the view is dropped. */
    fun release() {
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
    }

    /**
     * The width passes through unchanged; the height is the cached content height, honoured as an
     * exact size when the style fixed one (an `svg` at a known height, a card once JS has applied
     * the reported height) and as the content's own height otherwise.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentPx = contentHeightPx()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST ->
                if (contentPx == 0) 0 else contentPx.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))

            else -> contentPx
        }
        // The frame measures the WebView itself: a FrameLayout's own measure would hand the child
        // the parent's unresolved specs, and the WebView's wrap-content answer is the one this
        // view exists to make authoritative.
        webView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    /**
     * Reports the document's height in CSS pixels — the unit a RN style height speaks — when it is
     * known and has moved by more than a pixel since the last report. The WebView's own scale (text
     * zoom, font scale) is part of what it reports back, so the number is the height the reader is
     * actually looking at. A height of zero means the document has no layout yet and is left for
     * the next call rather than reported.
     */
    private fun reportContentHeight(view: WebView) {
        val contentCss = view.contentHeight * view.scale
        if (contentCss <= 0f) return
        if (reportedCss != 0f && abs(contentCss - reportedCss) <= 1f) return
        reportedCss = contentCss
        emitCardHeight(contentCss)
        // The measure pass that matters is the one after this: the frame's height answer comes from
        // the cache this just filled.
        view.requestLayout()
    }

    /** The cached content height in pixels, 0 before the first page has finished. */
    private fun contentHeightPx(): Int {
        @Suppress("DEPRECATION")
        val scale = webView.scale
        val contentCss = webView.contentHeight * scale
        if (contentCss <= 0f) return 0
        return (contentCss * resources.displayMetrics.density).roundToInt()
    }

    /** Sends one height in CSS pixels (= RN dp) to JS; quiet when nothing is listening. */
    private fun emitCardHeight(contentCss: Float) {
        if (mode != Mode.CARD) return
        val reactContext = context as? ReactContext ?: return
        try {
            reactContext
                .getJSModule(RCTEventEmitter::class.java)
                .receiveEvent(
                    id,
                    EVENT_TOP_CARD_HEIGHT,
                    Arguments.createMap().apply { putDouble("height", contentCss.toDouble()) }
                )
        } catch (e: Exception) {
            Log.w(TAG, "Could not report the card height; JS not ready", e)
        }
    }

    companion object {
        private const val TAG = "GatenCardWebView"

        /** What the accessibility layer reads the card as; one string for every card. */
        const val CONTENT_DESCRIPTION = "Card"

        /** The native event name; the JS prop is `onCardHeight`. */
        const val EVENT_TOP_CARD_HEIGHT = "topCardHeight"
    }
}
