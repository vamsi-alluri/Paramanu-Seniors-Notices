package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.PdfPageRenderer
import java.io.File

/**
 * Geometry only. The render itself needs a device, but the letterboxing is where the interesting
 * mistake lives: get it wrong and the framework centre-crops an A4 notice, silently removing the
 * letterhead and the signature.
 */
class PdfPageRendererTest {

    private val w = PdfPageRenderer.TARGET_WIDTH
    private val h = PdfPageRenderer.TARGET_HEIGHT

    @Test
    fun `A4 portrait fits by height and is centred horizontally`() {
        // A4 at 72dpi.
        val box = PdfPageRenderer.fitLetterbox(595, 842, w, h)
        assertEquals("must fill the height", h, box.height)
        assertTrue("must not exceed the width", box.width <= w)
        assertEquals("even margins either side", box.left, w - box.right)
        assertEquals(0, box.top)
    }

    @Test
    fun `the whole page is inside the canvas`() {
        val box = PdfPageRenderer.fitLetterbox(595, 842, w, h)
        assertTrue(box.left >= 0)
        assertTrue(box.top >= 0)
        assertTrue(box.right <= w)
        assertTrue(box.bottom <= h)
    }

    @Test
    fun `aspect ratio is preserved`() {
        val box = PdfPageRenderer.fitLetterbox(595, 842, w, h)
        val expectedWidth = (595.0 / 842.0) * box.height
        assertEquals(expectedWidth, box.width.toDouble(), 1.0)
    }

    @Test
    fun `a landscape page fits by width and is centred vertically`() {
        val box = PdfPageRenderer.fitLetterbox(1600, 400, w, h)
        assertEquals(w, box.width)
        assertEquals("even margins above and below", box.top, h - box.bottom)
    }

    @Test
    fun `a page already at the target ratio fills the canvas exactly`() {
        val box = PdfPageRenderer.fitLetterbox(1600, 800, w, h)
        assertEquals(w, box.width)
        assertEquals(h, box.height)
        assertEquals(0, box.left)
        assertEquals(0, box.top)
    }

    @Test
    fun `degenerate input does not throw`() {
        val box = PdfPageRenderer.fitLetterbox(0, 0, w, h)
        assertEquals(w, box.width)
        assertEquals(h, box.height)
    }

    @Test
    fun `the notification bitmap stays under the binder transaction cap`() {
        // RGB_565 is two bytes per pixel. ARGB_8888 at this size would be 1.28MB and would throw
        // TransactionTooLargeException, losing the notification rather than just the picture.
        val bytes = w * h * 2
        assertTrue("$bytes bytes must stay well under 1MB", bytes < 900_000)
    }

    @Test
    fun `page count declines a missing or empty file`() {
        assertNull(PdfPageRenderer.pageCount(File("does-not-exist.pdf")))
        val empty = File.createTempFile("empty", ".pdf").apply { deleteOnExit() }
        assertNull(PdfPageRenderer.pageCount(empty))
    }
}
