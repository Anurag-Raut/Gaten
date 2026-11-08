/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.gaten.app.ocr.PpOcrModel
import java.io.IOException

/**
 * Turns a failed recognition download into the code JS is handed, and a running one into the
 * throttled phase events it receives. Both live here, free of Android, so a JVM test can pin the
 * mapping and the rate without a device.
 */

/** The rejection codes a recognition download can fail with, beyond the cancellation code. */
internal object DownloadFailure {
    /** No network: DNS, a connect or read timeout, or an I/O fault on the transfer itself. */
    const val OFFLINE = "DOWNLOAD_OFFLINE"

    /** Not enough free storage: checked before the download, or reported as ENOSPC by a write. */
    const val NO_SPACE = "DOWNLOAD_NO_SPACE"

    /** The bytes arrived but did not match the file's pinned size or checksum. */
    const val VERIFY_FAILED = "DOWNLOAD_VERIFY_FAILED"

    /** The catch-all: a failure the reader's remedy does not enumerate. */
    const val GENERIC = "RECOGNITION_DOWNLOAD_FAILED"

    /**
     * The code for [error]. The transfer and integrity cases come from the model's own reason; a
     * bare [IOException] is a transport fault; and a write refused for space is recognized by its
     * message even when the failure that wrapped it hides it further down the cause chain.
     */
    fun code(error: Throwable): String = when {
        isOutOfSpace(error) -> NO_SPACE
        error is PpOcrModel.DownloadException -> when (error.reason) {
            PpOcrModel.DownloadException.Reason.TRANSPORT -> OFFLINE
            PpOcrModel.DownloadException.Reason.INTEGRITY -> VERIFY_FAILED
            PpOcrModel.DownloadException.Reason.STORAGE -> GENERIC
        }
        error is IOException -> OFFLINE
        else -> GENERIC
    }

    /** True when [error], or anything it wrapped, is the filesystem's out-of-space failure. */
    fun isOutOfSpace(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val message = current.message
            if (message != null &&
                (message.contains("ENOSPC", ignoreCase = true) ||
                    message.contains("No space left", ignoreCase = true))
            ) {
                return true
            }
            current = current.cause
            depth += 1
        }
        return false
    }

    private const val MAX_CAUSE_DEPTH = 8
}

/**
 * Rates the model downloader's per-chunk byte reports into the phase events JS sees: at most one
 * "downloading" event per [intervalMs], a single "verifying" event the moment every byte is in
 * (so the bar reaches full once and only once), and nothing at all after [stop] — a settled promise
 * is never followed by progress. Byte counts are clamped to never run backwards, so a retry that
 * restarts a file cannot drag the bar back with it.
 *
 * [nowMs] is injected so the rate is testable without a clock; the module passes the device's
 * monotonic uptime.
 */
internal class DownloadProgressThrottle(
    private val intervalMs: Long,
    private val nowMs: () -> Long,
    private val emit: (phase: String, bytesDone: Long, bytesTotal: Long) -> Unit
) {
    private var lastEmittedAt = UNSET
    private var lastBytes = 0L
    private var verifyingEmitted = false
    private var stopped = false

    /** One byte-progress report; [bytesDone] is cumulative across the engine's files. */
    fun onBytes(bytesDone: Long, bytesTotal: Long) {
        if (stopped) return
        val done = maxOf(bytesDone, lastBytes)
        if (bytesTotal > 0 && done >= bytesTotal) {
            if (!verifyingEmitted) {
                verifyingEmitted = true
                lastBytes = bytesTotal
                emit(VERIFYING, bytesTotal, bytesTotal)
            }
            return
        }
        val now = nowMs()
        if (lastEmittedAt == UNSET || now - lastEmittedAt >= intervalMs) {
            lastEmittedAt = now
            lastBytes = done
            emit(DOWNLOADING, done, bytesTotal)
        }
    }

    /** Ends this download's reporting; nothing more is emitted, whatever arrives next. */
    fun stop() {
        stopped = true
    }

    companion object {
        const val DOWNLOADING = "downloading"
        const val VERIFYING = "verifying"

        private const val UNSET = Long.MIN_VALUE
    }
}
