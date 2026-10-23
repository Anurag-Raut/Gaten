/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.views.view.ReactViewGroup
import kotlin.math.sqrt

/** A persistent React container whose revision prop releases the old theme after the new commit. */
class GatenThemeTransitionManager : ViewGroupManager<GatenThemeTransitionView>() {
    override fun getName(): String = "GatenThemeTransition"

    override fun createViewInstance(reactContext: ThemedReactContext): GatenThemeTransitionView =
        GatenThemeTransitionView(reactContext)

    @ReactProp(name = "revision")
    fun setRevision(view: GatenThemeTransitionView, revision: Int) {
        view.commitTheme(revision)
    }

    override fun onDropViewInstance(view: GatenThemeTransitionView) {
        view.clearSnapshot()
        super.onDropViewInstance(view)
    }
}

/**
 * Crossfades one bounded, in-memory snapshot over the live screen without remounting its children.
 * Capture happens before JS applies the theme; the revision prop arrives with the new native view
 * updates, so the fade starts only after that commit. The overlay never takes focus or touches.
 */
class GatenThemeTransitionView(context: ThemedReactContext) : ReactViewGroup(context) {
    private var revision = 0
    private var captureRequest = 0
    private var snapshot: ImageView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expireSnapshot = Runnable { clearSnapshot() }

    /**
     * Copies this container's rendered window region, including WebViews and an interrupted fade.
     * The callback releases JS to apply its theme; stale copies after a resize/commit are discarded.
     */
    fun prepareTransition(window: Window?, onPrepared: (Boolean) -> Unit) {
        if (window == null || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow || !isShown ||
            width == 0 || height == 0
        ) {
            clearSnapshot()
            onPrepared(false)
            return
        }

        val request = ++captureRequest
        val capturedRevision = revision
        val capturedWidth = width
        val capturedHeight = height
        // Bound memory on large phones/tablets; the full-resolution UI remains live underneath.
        val scale = minOf(1.0, sqrt(3_000_000.0 / (width.toDouble() * height)))
        val bitmap = Bitmap.createBitmap(
            maxOf(1, (width * scale).toInt()),
            maxOf(1, (height * scale).toInt()),
            Bitmap.Config.ARGB_8888,
        )
        val location = IntArray(2)
        getLocationInWindow(location)
        val source = Rect(location[0], location[1], location[0] + width, location[1] + height)
        PixelCopy.request(window, source, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                Log.w("GatenThemeTransition", "Could not copy the theme frame (PixelCopy result $result)")
                onPrepared(false)
            } else if (request != captureRequest || capturedRevision != revision ||
                !isAttachedToWindow || width != capturedWidth || height != capturedHeight
            ) {
                onPrepared(false)
            } else {
                clearSnapshot()
                val image = ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_XY
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layout(0, 0, capturedWidth, capturedHeight)
                }
                snapshot = image
                overlay.add(image)
                // A failed/cancelled JS read must never leave an old screen covering the live app.
                postDelayed(expireSnapshot, 1_000)
                onPrepared(true)
            }
        }, mainHandler)
    }

    /** Starts after this mounting transaction, when the new theme's child updates can be drawn. */
    fun commitTheme(nextRevision: Int) {
        if (revision == nextRevision) return
        revision = nextRevision
        val image = snapshot ?: return
        postOnAnimation {
            if (snapshot !== image) return@postOnAnimation
            if (!ValueAnimator.areAnimatorsEnabled()) {
                clearSnapshot()
                return@postOnAnimation
            }
            removeCallbacks(expireSnapshot)
            image.animate()
                .alpha(0f)
                .setDuration(260)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction { if (snapshot === image) clearSnapshot() }
                .start()
        }
    }

    /** Removes only this animation's overlay and releases its bitmap for collection. */
    fun clearSnapshot() {
        captureRequest++
        removeCallbacks(expireSnapshot)
        val image = snapshot ?: return
        snapshot = null
        image.animate().cancel()
        overlay.remove(image)
        image.setImageDrawable(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearSnapshot()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDetachedFromWindow() {
        clearSnapshot()
        super.onDetachedFromWindow()
    }
}
