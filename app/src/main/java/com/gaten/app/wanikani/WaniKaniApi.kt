/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/** What one request to WaniKani answered. */
internal sealed interface WaniKaniFetch {

    /** A 2xx response body, decoded as UTF-8. */
    data class Body(val text: String) : WaniKaniFetch

    /** 401 or 403: the token is wrong, revoked, or not allowed to read subjects. */
    data object Unauthorized : WaniKaniFetch

    /** 404: there is no such resource. */
    data object NotFound : WaniKaniFetch

    /** Nothing was answered; [failure] says why. */
    data class Failed(val failure: WaniKaniFailure) : WaniKaniFetch
}

/**
 * The only code in Gaten that talks to WaniKani, as read-only GETs over HTTPS.
 *
 * What it refuses, and why:
 *  - Any URL [WaniKaniPolicy] does not allow, before a socket is touched - the API and its image
 *    host, over HTTPS, and nothing else.
 *  - Redirects: they are not followed at all (`instanceFollowRedirects = false`), so a 3xx is just
 *    an unhandled status. That is what keeps a `Authorization` header from ever being replayed to
 *    whoever a redirect points at.
 *  - An `Authorization` header on an image request, by construction: only [get] takes a token, and
 *    only the API's own URL is ever passed to it.
 *  - Bodies over a fixed size, by declared length and while reading, so a wrong URL or a hostile
 *    answer cannot make the app allocate an unbounded amount.
 *  - An image body that is not SVG, because that text goes to a WebView in the UI.
 *
 * Everything runs on [Dispatchers.IO], never throws (cancellation excepted, which is rethrown), and
 * answers with a value. The base url and the allow-rule are fixed: there is no way to aim this at
 * another host, so the only URLs it can ever contact are the ones [WaniKaniPolicy] names.
 *
 * `open` so a unit test can stand in for it and exercise the lookup's decisions - cache first,
 * refresh, entitlement - without a socket.
 */
internal open class WaniKaniApi {

    /** `GET <base>/<path>?<query>` with the token, for the endpoints that take one. */
    open suspend fun get(path: String, query: List<Pair<String, String>>, token: String): WaniKaniFetch {
        if (!looksLikeToken(token)) return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        val url = try {
            resolve(path, query)
        } catch (e: URISyntaxException) {
            return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        }
        if (!WaniKaniPolicy.isAllowed(url)) return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        return withContext(Dispatchers.IO) { fetch(url, token, MAX_JSON_BYTES) { WaniKaniFetch.Body(it) } }
    }

    /**
     * `GET <url>`, the SVG of a radical, with no credentials on it.
     *
     * The url comes from a subject's `character_images`, so it is only followed when [WaniKaniPolicy]
     * still allows it; a body that is not an SVG document is refused rather than handed to the UI.
     */
    open suspend fun getImage(url: String): WaniKaniFetch {
        val image = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        }
        if (!WaniKaniPolicy.isAllowed(image)) return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        return withContext(Dispatchers.IO) {
            fetch(image, null, MAX_IMAGE_BYTES) { text ->
                if (looksLikeSvg(text)) WaniKaniFetch.Body(text) else WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
            }
        }
    }

    private suspend fun fetch(
        url: URI,
        token: String?,
        maxBytes: Int,
        accept: (String) -> WaniKaniFetch
    ): WaniKaniFetch {
        val connection = try {
            url.toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        }
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Wanikani-Revision", REVISION)
        connection.setRequestProperty("Accept", "application/json, image/svg+xml")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        return try {
            when (val code = connection.responseCode) {
                in 200..299 -> readBody(connection, maxBytes)?.let(accept)
                    ?: WaniKaniFetch.Failed(WaniKaniFailure.TOO_LARGE)
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> WaniKaniFetch.Unauthorized
                HttpURLConnection.HTTP_NOT_FOUND -> WaniKaniFetch.NotFound
                HTTP_TOO_MANY_REQUESTS -> WaniKaniFetch.Failed(WaniKaniFailure.RATE_LIMITED)
                else -> WaniKaniFetch.Failed(WaniKaniFailure.SERVER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            WaniKaniFetch.Failed(WaniKaniFailure.TIMEOUT)
        } catch (e: IOException) {
            WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The body as UTF-8, or null when it is larger than [maxBytes] - by its declared length, and
     * again while reading, since a server may understate it or stream without one.
     */
    private suspend fun readBody(connection: HttpURLConnection, maxBytes: Int): String? {
        if (connection.contentLengthLong > maxBytes) return null
        val body = ByteArrayOutputStream(minOf(connection.contentLengthLong.coerceAtLeast(0L), maxBytes.toLong()).toInt())
        connection.inputStream.use { input ->
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(chunk)
                if (read < 0) break
                if (body.size() + read > maxBytes) return null
                body.write(chunk, 0, read)
            }
        }
        return String(body.toByteArray(), StandardCharsets.UTF_8)
    }

    /** The full request url: the base path plus [path], with [query] percent-encoded. */
    private fun resolve(path: String, query: List<Pair<String, String>>): URI {
        val target = StringBuilder(WANIKANI_API_BASE.path.trimEnd('/')).append('/').append(path.trimStart('/'))
        if (query.isNotEmpty()) {
            target.append('?')
            query.forEachIndexed { index, (name, value) ->
                if (index > 0) target.append('&')
                target.append(name).append('=').append(URLEncoder.encode(value, "UTF-8"))
            }
        }
        val authority = if (WANIKANI_API_BASE.port > 0) {
            "${WANIKANI_API_BASE.host}:${WANIKANI_API_BASE.port}"
        } else {
            WANIKANI_API_BASE.host
        }
        return URI("${WANIKANI_API_BASE.scheme}://$authority$target")
    }

    private companion object {
        const val REVISION = "20170710"
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val CHUNK_BYTES = 16 * 1024
        const val MAX_JSON_BYTES = 1 shl 20
        const val MAX_IMAGE_BYTES = 512 shl 10
    }
}

/**
 * Which URLs Gaten may contact: the WaniKani API and its image host, over HTTPS, with no credentials
 * embedded in the URL. Pure and separate, so the rule the requests are written against is the rule
 * that is tested, and an allow-list rather than a block-list so an image url that arrives pointing
 * somewhere unexpected is refused instead of followed.
 */
internal object WaniKaniPolicy {

    private val ALLOWED_HOSTS = setOf("api.wanikani.com", "cdn.wanikani.com")

    private val ALLOWED_DOCUMENT_HOSTS = setOf("wanikani.com", "www.wanikani.com")

    /** True when [url] may be contacted. */
    fun isAllowed(url: URI): Boolean {
        if (!url.scheme.equals("https", ignoreCase = true)) return false
        if (url.userInfo != null) return false
        val host = url.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS
    }

    /**
     * True when [url] may be handed to the system to open: an https page on wanikani.com, and
     * nothing else. Subjects carry their own `document_url`, and a cached one is text on the device
     * that no one re-signs, so what the UI offers to open is checked here rather than trusted.
     */
    fun isAllowedDocument(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val page = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!page.scheme.equals("https", ignoreCase = true)) return false
        if (page.userInfo != null) return false
        val host = page.host?.lowercase() ?: return false
        return host in ALLOWED_DOCUMENT_HOSTS
    }
}

/**
 * Whether [token] can be put in a header at all. WaniKani's tokens are plain ASCII, and the point of
 * the check is to reject anything that is not - a control character or a space here would be header
 * injection, and an empty one cannot authenticate.
 */
internal fun looksLikeToken(token: String): Boolean =
    token.isNotEmpty() && token.length <= 256 && token.all { it.code in 0x21..0x7E }

/** Whether the fetched image is an SVG document at all, before it is cached for the WebView. */
private fun looksLikeSvg(text: String): Boolean = text.take(SVG_HEAD_BYTES).contains("<svg", ignoreCase = true)

private const val SVG_HEAD_BYTES = 2048

private val WANIKANI_API_BASE: URI = URI("https://api.wanikani.com/v2")
