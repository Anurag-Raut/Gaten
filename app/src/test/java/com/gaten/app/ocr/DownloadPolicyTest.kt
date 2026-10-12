/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class DownloadPolicyTest {

    private fun allowed(url: String) = DownloadPolicy.isAllowed(URI(url))

    @Test
    fun huggingFaceOverHttpsIsAllowed() {
        assertTrue(allowed("https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/x.onnx"))
        assertTrue(allowed("https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt"))
        assertTrue(allowed("https://HUGGINGFACE.CO/x"))
        assertTrue(allowed("https://hf.co/x"))
    }

    @Test
    fun theCdnHuggingFaceRedirectsToIsAllowed() {
        // The hosts the resolve URLs actually land on: the CDN's region hosts and the xet bridge.
        assertTrue(allowed("https://us.aws.cdn.hf.co/xet-bridge-us/abc/x.onnx?sig=1"))
        assertTrue(allowed("https://cas-bridge.xethub.hf.co/x"))
        assertTrue(allowed("https://cdn-lfs.huggingface.co/x"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertFalse(allowed("http://huggingface.co/x"))
        assertFalse(allowed("ftp://huggingface.co/x"))
        assertFalse(allowed("https://example.com/x"))
        assertFalse(allowed("https://huggingface.co.evil.com/x"))
        assertFalse(allowed("https://evil-huggingface.co/x"))
        assertFalse(allowed("https://not-hf.co/x"))
        assertFalse(allowed("https://127.0.0.1:8080/x"))
    }

    @Test
    fun aUrlWithoutAHostIsRefused() {
        assertFalse(allowed("mailto:someone@example.com"))
        assertFalse(allowed("file:/tmp/x.onnx"))
    }
}
