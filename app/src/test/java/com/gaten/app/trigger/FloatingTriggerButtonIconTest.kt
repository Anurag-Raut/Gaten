/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import com.gaten.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The floating button's mark, as the two assets it is built from: the [R.drawable.ic_trigger_g]
 * vector — the black Gaten G, cropped to its ink box, one flat `evenOdd` ink that is never tinted —
 * and the white disk `FloatingTriggerButton` draws it on.
 *
 * These are the values a later edit must not move: the viewport is the artwork's own ink box (no
 * adaptive-icon padding), the path is one black `evenOdd` fill, and the button reads this drawable
 * with no tint, on a flat white disk with a 14% black keyline. A tinted or padded mark, or a mark
 * on the accent disk this button used to wear, is exactly what this fixes.
 */
class FloatingTriggerButtonIconTest {

    /** The drawable and the button source, found from wherever the test JVM was started. */
    private fun source(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            File(dir, relativePath).takeIf { it.isFile }?.let { return it }
            File(dir, "app/$relativePath").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw IllegalStateException("could not find $relativePath from ${System.getProperty("user.dir")}")
    }

    /** The `<vector>` root of the mark drawable. */
    private fun markVector(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val vector = factory.newDocumentBuilder()
            .parse(source("src/main/res/drawable/ic_trigger_g.xml"))
            .documentElement
        assertEquals("vector", vector.tagName)
        return vector
    }

    /** The one `<path>` in the mark vector. */
    private fun markPath(): Element {
        val paths = markVector().getElementsByTagName("path")
        assertEquals(1, paths.length)
        return paths.item(0) as Element
    }

    private fun attr(element: Element, name: String): String =
        element.getAttributeNS("http://schemas.android.com/apk/res/android", name)

    /**
     * The mark is the approved G cropped to its ink box: a 760x666 viewport (the source artwork's
     * own box, not the 108 dp launcher canvas), sized to about 27x24 dp, with the letterform's own
     * path — not an empty or placeholder one.
     */
    @Test
    fun theMarkIsCroppedToItsInkBox() {
        val vector = markVector()
        assertEquals("760", attr(vector, "viewportWidth"))
        assertEquals("666", attr(vector, "viewportHeight"))
        assertEquals("27dp", attr(vector, "width"))
        assertEquals("23.66dp", attr(vector, "height"))
        assertTrue(
            "the path must be the letterform's own, not a placeholder",
            attr(markPath(), "pathData").length > 1000
        )
    }

    /**
     * The mark is one flat ink: black, `evenOdd`, and tinted by nobody. A missing `evenOdd` would
     * fill the counters of the G solid; a `tint` would let a theme repaint the app's own mark.
     */
    @Test
    fun theMarkIsOneFlatBlackInkAndNeverTinted() {
        val path = markPath()
        assertEquals("#000000", attr(path, "fillColor"))
        assertEquals("evenOdd", attr(path, "fillType"))

        val vector = markVector()
        val android = "http://schemas.android.com/apk/res/android"
        val nodes = vector.getElementsByTagName("*")
        val tinted = vector.hasAttributeNS(android, "tint") ||
            (0 until nodes.length).any {
                (nodes.item(it) as Element).hasAttributeNS(android, "tint")
            }
        assertFalse("the mark must not carry a tint of its own", tinted)
    }

    /** The drawable is a real resource the app can reference by name. */
    @Test
    fun theMarkIsACompiledDrawable() {
        assertNotEquals(0, R.drawable.ic_trigger_g)
    }

    /**
     * The bubble draws this mark, untinted, on the flat white disk — and no longer wears the
     * Quick Settings glyph or the accent tint. The QS tile keeps `ic_qs_lookup`; this file must not.
     */
    @Test
    fun theBubbleUsesTheUntintedMarkNotTheQuickSettingsGlyph() {
        val buttonSource = source("src/main/java/com/gaten/app/trigger/FloatingTriggerButton.kt")
            .readText()

        assertTrue(
            "the bubble must draw the Gaten mark",
            buttonSource.contains("R.drawable.ic_trigger_g")
        )
        assertFalse(
            "the Quick Settings glyph must not be drawn by the floating button",
            buttonSource.contains("ic_qs_lookup")
        )
        assertFalse(
            "the mark must not be tinted",
            buttonSource.contains("onPrimary")
        )
        assertFalse(
            "the accent disk is gone from this button",
            buttonSource.contains("accentRoles")
        )
        assertTrue("the disk fills white", buttonSource.contains("DISC_FILL = -0x1"))
        assertTrue(
            "the keyline is black at 14%",
            buttonSource.contains("DISC_KEYLINE = 0x24000000")
        )
    }
}
