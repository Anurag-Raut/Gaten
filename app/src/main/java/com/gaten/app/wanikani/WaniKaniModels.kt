/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

/**
 * WaniKani as a content reference and, on the review screen, as the account's own practice queue:
 * radicals and kanji with their meanings, readings, mnemonics and component relationships for the
 * character the reader tapped, plus the states a connection and a lookup can be in.
 *
 * Almost nothing the user owns is involved. Review statistics, level progressions and study
 * materials are still never requested, stored or shown, and the reference lookup asks nothing of the
 * account at all. The two exceptions are the review screen's own calls, whose shapes live in
 * `WaniKaniReview.kt`: `GET /assignments`, with the subjects behind it, reads what is due right now
 * and keeps none of it, and `POST /reviews` sends a finished review back - the one write Gaten ever
 * makes to WaniKani. The only other user-scoped request is `GET /user`, and all that is kept from it
 * is the subscription's content entitlement (see [WaniKaniEntitlement]) because WaniKani's terms
 * require a third-party client to respect it.
 */

/**
 * Why a request or a cache write did not produce an answer, for the UI to map onto a string
 * resource. Deliberately coarse: the reader either has the content, needs to reconnect, or has to
 * try again.
 */
enum class WaniKaniFailure {
    /** No connection, an unreachable host, or a refused/TLS-failed socket. */
    OFFLINE,

    /** The request exceeded its connect or read timeout. */
    TIMEOUT,

    /** HTTP 429: more than 60 requests in a minute. Never retried on our own. */
    RATE_LIMITED,

    /** A status we do not handle: a redirect we refuse to follow, or a 4xx/5xx with no other meaning. */
    SERVER,

    /** The body was larger than we are willing to read. */
    TOO_LARGE,

    /** A body that is not the JSON, or the SVG image, that was asked for. */
    MALFORMED,

    /** The token could not be stored or read: the Android Keystore or the cache directory refused us. */
    STORAGE
}

/** The outcome of checking a personal access token against WaniKani. */
sealed interface WaniKaniConnection {

    /**
     * The token works. [maxLevelGranted] is the highest subject level this account may be shown -
     * 3 for a free account, 60 for a paid one, and 3 again once a paid period has ended (see
     * [WaniKaniEntitlement]).
     */
    data class Connected(val maxLevelGranted: Int) : WaniKaniConnection

    /** WaniKani rejected the token (401) or forbade it (403): the user has to reconnect. */
    data object InvalidToken : WaniKaniConnection

    /** The token could not be checked at all; [failure] says why. The token is not stored. */
    data class Unavailable(val failure: WaniKaniFailure) : WaniKaniConnection
}

/** What the reader tapped. */
sealed interface WaniKaniTarget {

    /**
     * A character from the dictionary result. [character] is exactly one Unicode code point and may
     * be outside the basic plane (𠮷 is U+20BB7), so it must never be measured in `Char`s.
     */
    data class Kanji(val character: String) : WaniKaniTarget

    /** A component of a kanji already shown, by WaniKani subject id. */
    data class Radical(val id: Int) : WaniKaniTarget
}

/**
 * One WaniKani subject as the UI renders it. Only the two kinds the reader can tap exist here:
 * a [Kanji] or a [Radical]. Vocabulary and kana vocabulary are never targets, and a subject of
 * another kind is reported as [WaniKaniLookup.NoEntry].
 */
sealed interface WaniKaniSubject {

    val id: Int

    /** WaniKani's level for the subject (1..60), the number the subscription entitlement gates on. */
    val level: Int

    /** Every meaning WaniKani accepts, the primary one first. */
    val meanings: List<String>

    /** The primary meaning: a radical's name. Never empty. */
    val primaryMeaning: String

    /** The meaning mnemonic as plain text; see [plainWaniKaniText]. */
    val meaningMnemonic: String

    /**
     * The subject's page on wanikani.com, or null when WaniKani's url was not an https page on that
     * host - which is the only thing the UI may open, so a url that arrived pointing anywhere else
     * (or a cached page edited on the device) is dropped rather than offered.
     */
    val documentUrl: String?

    /** A character the reader tapped, with everything WaniKani says about it. */
    data class Kanji(
        override val id: Int,
        val character: String,
        override val level: Int,
        /** The on'yomi readings, the primary one first; empty when WaniKani lists none. */
        val onyomi: List<String>,
        /** The kun'yomi readings, the primary one first; empty when WaniKani lists none. */
        val kunyomi: List<String>,
        /** The radicals this kanji is built from, in WaniKani's order. */
        val components: List<WaniKaniComponent>,
        override val meanings: List<String>,
        override val primaryMeaning: String,
        override val meaningMnemonic: String,
        /** The reading mnemonic as plain text; empty when WaniKani lists none. */
        val readingMnemonic: String,
        override val documentUrl: String?
    ) : WaniKaniSubject

    /** A radical; a component of a kanji, or a subject looked up by id. */
    data class Radical(
        override val id: Int,
        /** Null when the radical has no character and exists only as an image. */
        val character: String?,
        override val level: Int,
        override val meanings: List<String>,
        override val primaryMeaning: String,
        override val meaningMnemonic: String,
        /**
         * The radical's image as raw SVG document text, ready for a WebView with JavaScript, file
         * and network access all off. Null when the radical has no image, when its image is not
         * cached yet and could not be fetched, or when the radical has a [character] and therefore
         * does not need one. Never the reason a lookup fails.
         */
        val svg: String?,
        override val documentUrl: String?
    ) : WaniKaniSubject
}

/**
 * One radical of a kanji's composition, as a tappable chip. [character] and [name] are null when that
 * component is not cached yet - the first view of a kanji happens offline, or a fetch failed - so the
 * chip still knows what to look up when tapped ([id]).
 *
 * There is no image here: a component is a glyph and a name. The image of a component that has no
 * character is fetched and cached while the kanji is being fetched, for the drill-down into that
 * radical, which draws it from [WaniKaniSubject.Radical.svg].
 */
data class WaniKaniComponent(
    val id: Int,
    val character: String?,
    val name: String?
)

/** What a tap answered. */
sealed interface WaniKaniLookup {

    /**
     * The content. [cached] is true when it came from the local cache; [stale] is true when it came
     * from the cache because the refresh that was asked for failed. [cacheFailure] is set when the
     * answer could not be saved to the device, so the UI can say the content is for this session only
     * instead of leaving the reader to find out offline.
     */
    data class Available(
        val subject: WaniKaniSubject,
        val cached: Boolean,
        val stale: Boolean,
        val cacheFailure: WaniKaniFailure? = null
    ) : WaniKaniLookup

    /**
     * WaniKani has no subject for this character or id - which is answered from the device for up to
     * a week after it was first found out, and asked again once that has passed, since WaniKani keeps
     * adding subjects. The state the UI renders is the same either way.
     */
    data object NoEntry : WaniKaniLookup

    /** The subject exists but its level is above what this account's subscription grants. */
    data object Restricted : WaniKaniLookup

    /** No token is configured. */
    data object NeedsToken : WaniKaniLookup

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniLookup

    /** Nothing could be answered: [failure] says why, and no cached copy exists to fall back on. */
    data class Unreachable(val failure: WaniKaniFailure) : WaniKaniLookup
}
