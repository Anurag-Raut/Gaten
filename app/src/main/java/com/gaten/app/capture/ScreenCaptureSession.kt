/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Gaten"

/** How long one request waits for the frame its attach asks the display for. */
private const val FrameWaitMs = 500L

/** The most images the reader may hand out at once: one rendered frame and the one being read. */
private const val MaxImages = 2

/**
 * One media projection session, and the single screenshot any one trigger asks it for.
 *
 * Android 14 allows exactly one `createVirtualDisplay()` per consent, so the session is built when
 * lookup mode is switched on and lives until the user or the system stops the projection: the
 * consent dialog and the status-bar privacy chip appear once per session, not once per lookup.
 *
 * **Nothing is rendered into this session's buffers, copied or held between lookups.** The virtual
 * display is created *without* a surface — the platform's own description of a virtual display with
 * no surface is that it renders nothing, and its detach operation is documented as having "a similar
 * effect to turning off the screen" — and the [ImageReader] a lookup reads is created for that one
 * request and closed with it. While no trigger is running there is no capture target and no image
 * buffer parked in a queue. Of the capture path, what stays alive for the session is the projection,
 * the foreground service and its notification, and the privacy chip; anything else the app holds —
 * the OCR engines, the frame behind an open lookup — is the app's own and follows its own release
 * rules.
 *
 * A request is: attach the reader's surface (which is what makes the display render the current
 * screen), take the first frame to arrive in it, take the surface away again, and hand that one
 * bitmap back. Two things make the frame the right one. The reader is created for the request, so
 * there is no older frame in it to mistake for the screen; and the surface is dropped as soon as a
 * frame is in hand, so the display stops rendering into a buffer nobody will read. Attaching may
 * render a frame or two before the take-away lands; exactly one is ever delivered, and a later
 * trigger never sees an earlier lookup's picture.
 *
 * Every path that ends a request — the frame arriving, [FrameWaitMs] passing with none, a failure
 * anywhere in the copy or the attach, [cancel], [resize], the projection stopping, [release] —
 * answers at most once, on the main thread, after closing that request's reader and dropping the
 * capture target. Platform calls are guarded individually: a call that throws is logged, its own
 * stage is skipped, and the stages after it still run, because a capture whose display cannot be
 * detached or reshaped is one that cannot be held dormant and is ended rather than trusted.
 *
 * [grab], [cancel] and [release] are main-thread calls (like the overlay and the capture token that
 * guard them); all of the frame state lives on this session's own thread, because a full-screen
 * ARGB_8888 frame is about 10 MB at 1080×2340 and neither the copy nor the reader churn may touch
 * the main thread. Ending the session is claimed with [AtomicBoolean.compareAndSet], so a release on
 * the main thread and a stop arriving on this session's thread cannot both tear it down or both tell
 * the owner it ended.
 */
class ScreenCaptureSession(
    private val projection: MediaProjection,
    initialWidth: Int,
    initialHeight: Int,
    initialDensityDpi: Int,
    /** Called on the main thread, with this session, when it ends without the app asking. */
    private val onStopped: (ScreenCaptureSession) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("gaten-capture").apply { start() }
    private val captureHandler = Handler(thread.looper)

    /** The screen's shape, kept so the next request's reader matches the display. */
    private var width = initialWidth
    private var height = initialHeight
    private var densityDpi = initialDensityDpi

    /** The one request in flight, if any: its reader and the answer it has not been given yet. */
    private var pending: Request? = null

    private val timeout: Runnable = Runnable { pending?.let { finish(it, null) } }

    @Volatile
    private var display: VirtualDisplay? = null

    /** Claimed once, by whichever thread ends the session first. */
    private val closed = AtomicBoolean(false)

    private val isClosed: Boolean
        get() = closed.get()

    init {
        // Android 14 requires a registered callback before the virtual display is created, and the
        // session goes down with the projection whenever the user stops it from the status-bar chip,
        // the screen locks, another app starts capturing, or the service is destroyed.
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "Media projection stopped by the system or the user")
                    // Only the call that actually claimed the teardown tells the owner: a release
                    // that got there first already did, and two notifications would be one too many.
                    if (teardown(stopProjection = false)) {
                        mainHandler.post { onStopped(this@ScreenCaptureSession) }
                    }
                }
            },
            captureHandler
        )
        captureHandler.post {
            val created = try {
                projection.createVirtualDisplay(
                    "gaten-capture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    // No surface: a display without one renders nothing at all, so the session starts
                    // dormant instead of rendering frames nobody asked for.
                    null,
                    null,
                    captureHandler
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not create the virtual display", e)
                null
            }
            if (isClosed) {
                // Ended while the display was being created: hand it straight back.
                try {
                    created?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not release the virtual display of an ended session", e)
                }
                return@post
            }
            display = created
            if (created == null) {
                // No mirror means no lookup will ever work: the session ends itself and tells its
                // owner, which is what takes lookup mode back off instead of leaving a projection
                // running that can never capture anything.
                failSession("Could not create the virtual display - ending the session")
            }
        }
    }

    /**
     * Takes one screenshot and hands it to [onFrame] on the main thread, or null when the session is
     * down, the screen produced no frame within [FrameWaitMs], or the request could not be set up. A
     * request that is already in flight is answered null first — this is single-flight by
     * construction, and the mirror never has two capture targets at once.
     */
    fun grab(onFrame: (Bitmap?) -> Unit) {
        if (isClosed) {
            onFrame(null)
            return
        }
        captureHandler.post {
            if (isClosed || display == null) {
                mainHandler.post { onFrame(null) }
                return@post
            }
            pending?.let { finish(it, null) }
            if (isClosed) {
                // That finish ended the session; this request still gets its one answer.
                mainHandler.post { onFrame(null) }
                return@post
            }
            val request = Request(onFrame)
            val reader = try {
                newReader(width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Could not create the reader for a lookup", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory for the reader of a lookup", e)
                null
            }
            if (reader == null) {
                mainHandler.post { onFrame(null) }
                return@post
            }
            request.reader = reader
            pending = request
            try {
                reader.setOnImageAvailableListener({ onImageAvailable(request, it) }, captureHandler)
                // Attaching the reader is what makes the display render the current screen into it.
                display?.surface = reader.surface
            } catch (e: Exception) {
                Log.e(TAG, "Could not attach the reader for a lookup", e)
                // Whatever was wired up is reconciled here: one null answer, the reader closed, the
                // target dropped — or the session ended when it cannot be dropped.
                finish(request, null)
                return@post
            }
            // A bounded wait: a display that never renders must not leave an attach standing.
            captureHandler.postDelayed(timeout, FrameWaitMs)
        }
    }

    /**
     * Drops the request in flight, if any, without waiting for its frame: the lookup it belonged to
     * is over — dismissed, replaced, or interrupted — and its reader and capture target go with it.
     * A request started after this call is unaffected, because the work runs in call order.
     */
    fun cancel() {
        captureHandler.post { pending?.let { finish(it, null) } }
    }

    /**
     * Gives the display a new shape after a rotation or a fold, without rebuilding it: one projection
     * allows one display, and no reader is allocated here — the next request creates its own at the
     * size in force then. A request already in flight describes the screen's old shape, so it is
     * answered null rather than frozen at the wrong size. A display that will not take the new shape
     * is one that cannot serve the next lookup, so the session ends rather than reporting itself
     * ready.
     */
    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (isClosed || width <= 0 || height <= 0) return
        captureHandler.post {
            if (isClosed) return@post
            if (width == this.width && height == this.height && densityDpi == this.densityDpi) {
                return@post
            }
            val current = display ?: return@post
            Log.d(TAG, "Resizing the capture to ${width}x$height @${densityDpi}dpi")
            pending?.let { finish(it, null) }
            if (isClosed) return@post
            try {
                current.resize(width, height, densityDpi)
            } catch (e: Exception) {
                failSession("Could not resize the virtual display - ending the session: $e")
                return@post
            }
            this.width = width
            this.height = height
            this.densityDpi = densityDpi
        }
    }

    /**
     * Ends the session: the display is given up, the projection is stopped so the privacy chip goes
     * with it, and any request in flight is answered null. Idempotent, and safe to call from the
     * projection's own stop callback, which arrives on this session's thread.
     */
    fun release() {
        teardown(stopProjection = true)
    }

    /**
     * Tears the session down, once, whichever thread claims it first — answers the request in flight,
     * gives the display up, stops the projection, and quits the worker.
     *
     * Multi-step by nature, and every stage is guarded on its own so that a platform call which
     * throws skips only itself: a display that will not release must still not keep the projection
     * and the worker alive. Returns whether this call is the one that claimed the teardown, which is
     * also what decides whether the owner is told.
     */
    private fun teardown(stopProjection: Boolean): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        captureHandler.post {
            captureHandler.removeCallbacks(timeout)
            try {
                pending?.let { finish(it, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not answer the request in flight while the session ends", e)
            }
            try {
                display?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release the virtual display", e)
            } finally {
                display = null
            }
            if (stopProjection) {
                try {
                    projection.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop the media projection", e)
                }
            }
            thread.quitSafely()
        }
        return true
    }

    /**
     * Ends a session that can no longer be trusted — the capture target will not come off, or the
     * display will not take a new shape — for the caller to use after it has answered its own
     * request. Lookup mode goes off with it, which is better than staying on over a capture that may
     * still be running.
     */
    private fun failSession(reason: String) {
        Log.e(TAG, reason)
        if (teardown(stopProjection = true)) {
            mainHandler.post { onStopped(this@ScreenCaptureSession) }
        }
    }

    /** One request: the reader it renders into and the answer it has not been given yet. */
    private class Request(val onFrame: (Bitmap?) -> Unit) {
        var reader: ImageReader? = null
        var answered = false
    }

    /**
     * A frame arrived for [request] on this session's thread. The capture target is dropped as soon
     * as the frame is in hand — the display has nothing further to render for this lookup — and the
     * image is closed whatever the copy does with it.
     */
    private fun onImageAvailable(request: Request, reader: ImageReader) {
        if (isClosed || pending !== request || request.answered) return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Could not take the captured frame", e)
            finish(request, null)
            return
        } ?: return
        val detached = detach()
        val bitmap = try {
            copyOf(image)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w(TAG, "Could not close the captured image", e)
            }
        }
        finish(request, bitmap, detached)
    }

    /**
     * Answers [request] at most once, on the main thread, and in every path leaves the capture target
     * off and that request's reader closed — the frame arriving, the wait running out, a failure in
     * the copy or the attach, a cancellation, the session ending.
     *
     * A bitmap that is not handed to the callback is recycled, and a target that will not come off
     * costs the capture its result: the session is ended rather than a screenshot returned while the
     * display may still be rendering into it.
     */
    private fun finish(request: Request, bitmap: Bitmap?, detached: Boolean = false) {
        if (request.answered) {
            bitmap?.recycle()
            return
        }
        request.answered = true
        if (pending === request) pending = null
        captureHandler.removeCallbacks(timeout)
        val targetOff = detached || detach()
        closeReader(request)
        if (!targetOff && !isClosed) {
            bitmap?.recycle()
            mainHandler.post { request.onFrame(null) }
            failSession("The capture target could not be detached - ending the session")
            return
        }
        mainHandler.post { request.onFrame(bitmap) }
    }

    /** Removes the request's reader listener and closes its reader, logging instead of throwing. */
    private fun closeReader(request: Request) {
        val reader = request.reader ?: return
        request.reader = null
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not detach the reader's listener", e)
        }
        try {
            reader.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not close the reader", e)
        }
    }

    /**
     * Takes the surface away, so the display stops rendering into a buffer nobody will read. True
     * when the capture target is off — including when there is no display left to take it from.
     */
    private fun detach(): Boolean {
        val current = display ?: return true
        return try {
            current.surface = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not detach the capture target", e)
            false
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MaxImages)

    /**
     * Copies one mirrored frame into a software bitmap. Every read from the platform image — its
     * planes, its size, the plane's strides — happens inside the guarded block, because any of them
     * can throw on an image the platform has already taken back; a failure, including running out of
     * memory for the copy, is logged, leaves nothing behind, and answers null.
     *
     * Rows come back padded to the GPU's stride, so a padded frame is repacked through a buffer that
     * lives only for this copy while a frame that is already tight — the normal case — is copied in
     * one call.
     */
    private fun copyOf(image: Image): Bitmap? {
        var bitmap: Bitmap? = null
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val frameWidth = image.width
            val frameHeight = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (pixelStride != 4) {
                Log.w(TAG, "Unexpected pixel stride $pixelStride in the captured frame")
                return null
            }
            bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            val source = plane.buffer
            if (rowStride == frameWidth * 4) {
                source.rewind()
                bitmap.copyPixelsFromBuffer(source)
            } else {
                val row = ByteArray(frameWidth * 4)
                val packed = ByteBuffer.allocate(frameWidth * 4 * frameHeight)
                for (y in 0 until frameHeight) {
                    source.position(y * rowStride)
                    source.get(row, 0, row.size)
                    packed.put(row)
                }
                packed.rewind()
                bitmap.copyPixelsFromBuffer(packed)
            }
            // Handed over only on success; every other way out of this block recycles it.
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy the captured frame", e)
            bitmap?.recycle()
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for the captured frame", e)
            bitmap?.recycle()
            null
        }
    }
}
