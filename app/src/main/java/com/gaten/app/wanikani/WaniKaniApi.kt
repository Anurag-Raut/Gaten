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
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** What one request to WaniKani answered. */
internal sealed interface WaniKaniFetch {

    /** A 2xx response body, decoded as UTF-8. */
    data class Body(val text: String) : WaniKaniFetch

    /** 401 or 403: the token is wrong, revoked, or not allowed to read subjects. */
    data object Unauthorized : WaniKaniFetch

    /** 404: there is no such resource. */
    data object NotFound : WaniKaniFetch

    /**
     * Nothing was answered; [failure] says why, and [retryAfterSeconds] when the server said how long
     * to wait - a 429's `Retry-After` or `RateLimit-Reset`. Null when it said nothing, or when the
     * failure happened before an answer could come.
     */
    data class Failed(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniFetch
}

/**
 * The response headers the account-scoped reads cache against.
 *
 * WaniKani sends an `ETag` and a `Last-Modified` with every response and accepts both back as
 * `If-None-Match` / `If-Modified-Since`, answering `304 Not Modified` when nothing changed - which is
 * how a cached copy is revalidated without transferring it again (the request itself still counts
 * against the rate limit; only the body is saved).
 */
internal data class WaniKaniMeta(
    /** The body's `ETag`, to send back as `If-None-Match`. Null when the response carried none. */
    val etag: String?,
    /** The body's `Last-Modified`. Null when the response carried none. */
    val lastModified: String?,
    /** Seconds to wait before asking again, from `Retry-After` or `RateLimit-Reset`. */
    val retryAfterSeconds: Long? = null
)

/** What one request answered, with the headers that came with it. */
internal sealed interface WaniKaniFetchWithMeta {

    /** A 2xx response body, decoded as UTF-8, with the headers to cache beside it. */
    data class Body(val text: String, val meta: WaniKaniMeta) : WaniKaniFetchWithMeta

    /** 304: the copy the request's `If-None-Match` described is still current, so no body came. */
    data class NotModified(val meta: WaniKaniMeta) : WaniKaniFetchWithMeta

    /** 401 or 403: the token is wrong, revoked, or not allowed to read this resource. */
    data object Unauthorized : WaniKaniFetchWithMeta

    /** 404: there is no such resource. */
    data object NotFound : WaniKaniFetchWithMeta

    /** Nothing was answered; [failure] says why, and [retryAfterSeconds] when the server said so. */
    data class Failed(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniFetchWithMeta
}

/**
 * The only code in Gaten that talks to WaniKani: GETs over HTTPS, plus the two writes - the POST that
 * finishes a review and the PUT that starts a lesson.
 *
 * What it refuses, and why:
 *  - Any URL [WaniKaniPolicy] does not allow, before a socket is touched - the API and its image
 *    host, over HTTPS, and nothing else.
 *  - Redirects: they are not followed at all (`instanceFollowRedirects = false`), so a 3xx is just
 *    an unhandled status. That is what keeps a `Authorization` header from ever being replayed to
 *    whoever a redirect points at.
 *  - An `Authorization` header on an image request, by construction: only [get], [getWithMeta] and
 *    [post] take a token, and only the API's own URL is ever passed to one.
 *  - A request body anywhere but on [post] and [put]: a GET is written as a GET with no output
 *    stream, so no body can be sent by accident.
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

    /**
     * `GET <base>/<path>?<query>` with the token and the response headers kept, for the account reads
     * that cache: [ifNoneMatch] makes the request conditional, so a body that has not changed since
     * that `ETag` answers `304` instead of arriving again. The request itself is still made and still
     * counts against the rate limit - what is saved is the transfer, not the request.
     *
     * The policy is the same as [get]'s: the url is checked against [WaniKaniPolicy] before a socket
     * is touched, redirects are not followed, and the body is bounded.
     */
    open suspend fun getWithMeta(
        path: String,
        query: List<Pair<String, String>>,
        token: String,
        ifNoneMatch: String?
    ): WaniKaniFetchWithMeta {
        if (!looksLikeToken(token)) return WaniKaniFetchWithMeta.Failed(WaniKaniFailure.MALFORMED)
        val url = try {
            resolve(path, query)
        } catch (e: URISyntaxException) {
            return WaniKaniFetchWithMeta.Failed(WaniKaniFailure.MALFORMED)
        }
        if (!WaniKaniPolicy.isAllowed(url)) return WaniKaniFetchWithMeta.Failed(WaniKaniFailure.MALFORMED)
        return withContext(Dispatchers.IO) {
            when (val exchange = exchange(url, token, MAX_JSON_BYTES, ifNoneMatch)) {
                is WaniKaniExchange.Failed -> WaniKaniFetchWithMeta.Failed(exchange.failure)
                is WaniKaniExchange.Answered -> when (exchange.code) {
                    in 200..299 -> exchange.text?.let { WaniKaniFetchWithMeta.Body(it, exchange.meta()) }
                        ?: WaniKaniFetchWithMeta.Failed(WaniKaniFailure.TOO_LARGE, exchange.retryAfterSeconds)
                    HTTP_NOT_MODIFIED -> WaniKaniFetchWithMeta.NotModified(exchange.meta())
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> WaniKaniFetchWithMeta.Unauthorized
                    HttpURLConnection.HTTP_NOT_FOUND -> WaniKaniFetchWithMeta.NotFound
                    HTTP_TOO_MANY_REQUESTS -> WaniKaniFetchWithMeta.Failed(
                        WaniKaniFailure.RATE_LIMITED,
                        exchange.retryAfterSeconds
                    )
                    else -> WaniKaniFetchWithMeta.Failed(WaniKaniFailure.SERVER)
                }
            }
        }
    }

    /**
     * `POST <base>/<path>` with the token and [body] as the request.
     *
     * One of the two writes Gaten makes to WaniKani, used to finish a review. The policy is the same as
     * [get]'s - the url is checked against [WaniKaniPolicy] before a socket is touched, redirects are
     * never followed, and the answer is bounded - and the body is written as UTF-8 JSON.
     *
     * 409 and 422 read as [WaniKaniFailure.MALFORMED]: WaniKani refuses a review it cannot accept -
     * a subject that is not due, counts that make no sense - and that is the request being wrong
     * rather than the network, so the caller has to fix it rather than try it again.
     */
    open suspend fun post(path: String, body: String, token: String): WaniKaniFetch =
        write("POST", path, body, token)

    /**
     * `PUT <base>/<path>` with the token and [body] as the request.
     *
     * The other write Gaten makes to WaniKani, used to start a lesson
     * (`PUT /assignments/<id>/start`), which is the only documented way to finish one. Same policy
     * as [post] - the url is checked against [WaniKaniPolicy] before a socket is touched, redirects
     * are never followed, and the answer is bounded - and the body is written as UTF-8 JSON.
     */
    open suspend fun put(path: String, body: String, token: String): WaniKaniFetch =
        write("PUT", path, body, token)

    private suspend fun write(method: String, path: String, body: String, token: String): WaniKaniFetch {
        if (!looksLikeToken(token)) return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        val url = try {
            resolve(path, emptyList())
        } catch (e: URISyntaxException) {
            return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        }
        if (!WaniKaniPolicy.isAllowed(url)) return WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
        return withContext(Dispatchers.IO) {
            when (val exchange = exchange(url, token, MAX_JSON_BYTES, null, body, method)) {
                is WaniKaniExchange.Failed -> WaniKaniFetch.Failed(exchange.failure)
                is WaniKaniExchange.Answered -> when (exchange.code) {
                    in 200..299 -> exchange.text?.let { WaniKaniFetch.Body(it) }
                        ?: WaniKaniFetch.Failed(WaniKaniFailure.TOO_LARGE)
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> WaniKaniFetch.Unauthorized
                    HttpURLConnection.HTTP_NOT_FOUND -> WaniKaniFetch.NotFound
                    HTTP_TOO_MANY_REQUESTS -> WaniKaniFetch.Failed(
                        WaniKaniFailure.RATE_LIMITED,
                        exchange.retryAfterSeconds
                    )
                    HTTP_CONFLICT, HTTP_UNPROCESSABLE -> WaniKaniFetch.Failed(WaniKaniFailure.MALFORMED)
                    else -> WaniKaniFetch.Failed(WaniKaniFailure.SERVER)
                }
            }
        }
    }

    /** One answered request, or a failure that happened before an answer could come. */
    private sealed interface WaniKaniExchange {

        /**
         * The request was answered. [text] is the bounded body of a 2xx - null for any other status,
         * and null for a 2xx whose body was larger than the caller's cap.
         */
        class Answered(
            val code: Int,
            val etag: String?,
            val lastModified: String?,
            val retryAfterSeconds: Long?,
            val text: String?
        ) : WaniKaniExchange {
            fun meta(): WaniKaniMeta = WaniKaniMeta(etag, lastModified, retryAfterSeconds)
        }

        /** Nothing answered: offline, timed out, or the connection failed some other way. */
        data class Failed(val failure: WaniKaniFailure) : WaniKaniExchange
    }

    /**
     * The request itself: the method, headers and limits for every caller, with the status, the cache
     * headers and the bounded body handed back for whoever asked to interpret.
     *
     * A [body] makes this a POST, and only then: the connection is asked for an output stream when,
     * and only when, there is something to write, so no GET can carry a body by accident.
     */
    private suspend fun exchange(
        url: URI,
        token: String?,
        maxBytes: Int,
        ifNoneMatch: String?,
        body: String? = null,
        method: String? = null
    ): WaniKaniExchange {
        val connection = try {
            url.toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return WaniKaniExchange.Failed(WaniKaniFailure.OFFLINE)
        }
        connection.requestMethod = method ?: if (body == null) "GET" else "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Wanikani-Revision", REVISION)
        connection.setRequestProperty("Accept", "application/json, image/svg+xml")
        if (ifNoneMatch != null) connection.setRequestProperty("If-None-Match", ifNoneMatch)
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
        }
        return try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            WaniKaniExchange.Answered(
                code = code,
                etag = connection.getHeaderField(HEADER_ETAG),
                lastModified = connection.getHeaderField(HEADER_LAST_MODIFIED),
                retryAfterSeconds = waitFor(connection),
                text = if (code in 200..299) readBody(connection, maxBytes) else null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            WaniKaniExchange.Failed(WaniKaniFailure.TIMEOUT)
        } catch (e: IOException) {
            WaniKaniExchange.Failed(WaniKaniFailure.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * How long the server asked us to wait, from `Retry-After` (seconds; its HTTP-date form is not
     * used by this API, so a date is ignored) or from `RateLimit-Reset` (epoch seconds).
     */
    private fun waitFor(connection: HttpURLConnection): Long? {
        connection.getHeaderField(HEADER_RETRY_AFTER)?.trim()?.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        val reset = connection.getHeaderField(HEADER_RATE_LIMIT_RESET)?.trim()?.toLongOrNull() ?: return null
        return (reset - Instant.now().epochSecond).coerceAtLeast(0)
    }

    /** The shape the reads that do not cache headers use: one body, or a failure. */
    private suspend fun fetch(
        url: URI,
        token: String?,
        maxBytes: Int,
        accept: (String) -> WaniKaniFetch
    ): WaniKaniFetch = when (val exchange = exchange(url, token, maxBytes, null)) {
        is WaniKaniExchange.Failed -> WaniKaniFetch.Failed(exchange.failure)
        is WaniKaniExchange.Answered -> when {
            exchange.code in 200..299 -> exchange.text?.let(accept)
                ?: WaniKaniFetch.Failed(WaniKaniFailure.TOO_LARGE)
            exchange.code == HttpURLConnection.HTTP_UNAUTHORIZED || exchange.code == HttpURLConnection.HTTP_FORBIDDEN -> WaniKaniFetch.Unauthorized
            exchange.code == HttpURLConnection.HTTP_NOT_FOUND -> WaniKaniFetch.NotFound
            exchange.code == HTTP_TOO_MANY_REQUESTS -> WaniKaniFetch.Failed(
                WaniKaniFailure.RATE_LIMITED,
                exchange.retryAfterSeconds
            )
            else -> WaniKaniFetch.Failed(WaniKaniFailure.SERVER)
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
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val HEADER_ETAG = "ETag"
        const val HEADER_LAST_MODIFIED = "Last-Modified"
        const val HEADER_RETRY_AFTER = "Retry-After"
        const val HEADER_RATE_LIMIT_RESET = "RateLimit-Reset"
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
