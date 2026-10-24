/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

/**
 * A reader's own study material for one subject: the notes WaniKani keeps on their account and the
 * personal synonyms it accepts as answers. One record per subject, created the first time any study
 * information is entered; [id] is what an update addresses.
 *
 * The fields mirror `study_material`'s own data object exactly, so the notes shown are the notes
 * WaniKani holds and a save cannot silently rename a field.
 */
data class WaniKaniStudyMaterial(
    val id: Int,
    val subjectId: Int,
    val meaningNote: String,
    val readingNote: String,
    val meaningSynonyms: List<String>
)

/** What reading a subject's study material answered: the record, none, or why neither was reached. */
sealed interface WaniKaniStudyMaterialRead {

    /** WaniKani answered: [material] is the reader's record, or null when they have none yet. */
    data class Found(val material: WaniKaniStudyMaterial?) : WaniKaniStudyMaterialRead

    /** No token is configured. */
    data object NeedsToken : WaniKaniStudyMaterialRead

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniStudyMaterialRead

    /** Nothing could be answered: [failure] says why, and how long a rate limit asked us to wait. */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniStudyMaterialRead
}

/** What saving a subject's study material answered. */
sealed interface WaniKaniStudyMaterialWrite {

    /** WaniKani accepted the write; [material] is the record as it now stands. */
    data class Saved(val material: WaniKaniStudyMaterial) : WaniKaniStudyMaterialWrite

    /** No token is configured. */
    data object NeedsToken : WaniKaniStudyMaterialWrite

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniStudyMaterialWrite

    /** The write did not go through: [failure] says why, and how long a rate limit asked us to wait. */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniStudyMaterialWrite
}
