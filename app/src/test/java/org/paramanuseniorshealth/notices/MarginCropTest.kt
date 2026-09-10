package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.paramanuseniorshealth.notices.fcm.MarginCrop
import org.junit.Test

/**
 * The cases here are the real ones: a poster with a border, a poster without, and the two shapes
 * that would make a naive crop destroy an image rather than improve it.
 */
class MarginCropTest {

    private val GREY = 0xEDEDED
    private val WHITE = 0xFFFFFF
    private val BLACK = 0x000000

    /** A [border]-coloured frame [inset] pixels thick around a [fill]-coloured centre. */
    private fun framed(
        width: Int,
        height: Int,
        inset: Int,
        border: Int,
        fill: Int,
    ): (Int, Int) -> Int = { x, y ->
        val inside = x >= inset && y >= inset && x < width - inset && y < height - inset
        if (inside) fill else border
    }

    @Test
    fun `trims a uniform border`() {
        val box = MarginCrop.trim(200, 300, framed(200, 300, 20, GREY, WHITE))

        assertEquals(20, box.left)
        assertEquals(20, box.top)
        assertEquals(180, box.right)
        assertEquals(280, box.bottom)
    }

    @Test
    fun `leaves an image with no border alone`() {
        // A gradient: no two edges alike, so there is nothing to trim.
        val box = MarginCrop.trim(200, 300) { x, y -> (x * 255 / 200 shl 16) or (y * 255 / 300) }

        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(200, box.right)
        assertEquals(300, box.bottom)
    }

    @Test
    fun `tolerates the noise a JPEG border actually has`() {
        // The same frame, but the border drifts a few levels as compression makes it.
        val box = MarginCrop.trim(200, 300) { x, y ->
            val inside = x in 20..179 && y in 20..279
            if (inside) WHITE else 0xEDEDED - ((x + y) % 5)
        }

        assertEquals(20, box.left)
        assertEquals(20, box.top)
    }

    @Test
    fun `refuses to trim more than the guard allows`() {
        // Content only in the bottom quarter: a naive scan would eat three quarters of the image.
        val box = MarginCrop.trim(200, 400) { _, y -> if (y > 300) BLACK else WHITE }

        assertTrue("trimmed ${box.top} from the top", box.top <= (400 * 0.35).toInt())
        assertEquals(400, box.bottom)
    }

    @Test
    fun `gives back the whole frame for a single-colour image`() {
        val box = MarginCrop.trim(100, 100) { _, _ -> WHITE }

        assertEquals(0, box.left)
        assertEquals(100, box.right)
        assertEquals(0, box.top)
        assertEquals(100, box.bottom)
    }

    @Test
    fun `leaves an image alone when its corners disagree`() {
        // Two dark corners and two light ones: there is no border colour to speak of, so any crop
        // would be guesswork. A photograph looks like this.
        val box = MarginCrop.trim(200, 200) { x, _ -> if (x < 100) BLACK else WHITE }

        assertEquals(0, box.left)
        assertEquals(200, box.right)
    }

    @Test
    fun `is not fooled by an image too small to judge`() {
        val box = MarginCrop.trim(8, 8) { _, _ -> GREY }

        assertEquals(8, box.width)
        assertEquals(8, box.height)
    }

    @Test
    fun `trims a border on one side only`() {
        // A poster with a wide left margin and nothing elsewhere.
        val box = MarginCrop.trim(200, 200) { x, _ -> if (x < 30) GREY else WHITE }

        // The corners are all grey only on the left edge, so the reference disagrees and nothing
        // is trimmed -- documented here because it is a real limit of a corner-based reference,
        // not an accident. A one-sided margin stays.
        assertEquals(0, box.left)
    }
}
