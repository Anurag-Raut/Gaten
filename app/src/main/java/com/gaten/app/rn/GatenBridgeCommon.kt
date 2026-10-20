/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import com.facebook.react.bridge.Promise
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.dict.JmdictRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The scope every bridge module runs its promises on. [invalidate] cancels one module's own. */
internal fun bridgeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Rejects [promise] with [failure]'s own name as the JS error code, [detail] as the message. */
internal fun rejectFailure(promise: Promise, failure: AnkiReviewFailure, detail: String?) {
    promise.reject("ANKI_${failure.name}", detail ?: failure.name)
}

/**
 * The app's one open [JmdictRepository], shared by every caller that asks through the bridge —
 * the same lazy-singleton shape [com.gaten.app.ui.search.SearchViewModel] uses for its own copy.
 *
 * [JmdictRepository.open] copies the database out of the assets on first use, which is why the
 * work is guarded by a mutex: two screens asking at once must not race the extraction.
 */
internal object GatenDictStore {
    private val mutex = Mutex()
    private var repository: JmdictRepository? = null

    suspend fun repository(context: Context): JmdictRepository {
        repository?.let { return it }
        return mutex.withLock {
            repository ?: JmdictRepository.open(context.applicationContext).also { repository = it }
        }
    }
}
