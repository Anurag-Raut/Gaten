/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server for the downloader tests, on `java.base` only: one request per
 * connection, `Connection: close`, requests answered one at a time.
 *
 * It exists so the downloader's redirects, size checks, cancellation and cleanup are exercised over a
 * real socket without adding a test dependency (and without `jdk.httpserver`, which the Android unit
 * test compile cannot see).
 */
internal class FakeHttpServer : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val handlers = ConcurrentHashMap<String, (OutputStream) -> Unit>()
    private val running = AtomicBoolean(true)

    /** Requests answered so far, redirects included. */
    val requests = AtomicInteger()

    val port: Int get() = socket.localPort

    fun url(path: String) = "http://127.0.0.1:$port$path"

    /**
     * Answers [path] with [body] — or with a redirect to [location] — declaring [declaredLength]
     * bytes of Content-Length when that is given, so a response can lie about its size.
     */
    fun serve(
        path: String,
        body: ByteArray = ByteArray(0),
        status: Int = 200,
        location: String? = null,
        declaredLength: Long? = null
    ) {
        handlers[path] = { out ->
            val length = declaredLength ?: body.size.toLong()
            writeHead(out, status, location, length)
            if (location == null) out.write(body)
        }
    }

    /**
     * Answers [path] in two chunks, the second only after [release] — which is how a test can be
     * sure a download is in flight (and cancellable) rather than already finished.
     */
    fun serveInTwoChunks(
        path: String,
        first: ByteArray,
        second: ByteArray,
        started: CountDownLatch,
        release: CountDownLatch
    ) {
        handlers[path] = { out ->
            writeHead(out, 200, null, (first.size + second.size).toLong())
            out.write(first)
            out.flush()
            started.countDown()
            release.await()
            out.write(second)
        }
    }

    fun start() {
        thread(isDaemon = true, name = "fake-http-server") {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    handle(client)
                } catch (_: Exception) {
                    // A client that went away mid-response (a cancelled download) is not a failure.
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket.close() }
    }

    private fun handle(client: Socket) {
        val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        requests.incrementAndGet()

        val out = client.getOutputStream()
        val handler = handlers[path]
        if (handler == null) {
            writeHead(out, 404, null, 0L)
        } else {
            handler(out)
        }
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, location: String?, length: Long) {
        val reason = when (status) {
            200 -> "OK"
            302 -> "Found"
            404 -> "Not Found"
            else -> "Status"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (location != null) append("Location: $location\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
    }
}
