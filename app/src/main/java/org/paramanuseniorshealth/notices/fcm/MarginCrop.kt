package org.paramanuseniorshealth.notices.fcm

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the picture inside a picture that has been shipped with a border around it.
 *
 * Posters arrive this way constantly. The SBEBA camp poster is 976x1600 -- a white sheet sitting on
 * a light-grey ground, with a margin baked into the file by whoever exported it. On a phone that
 * margin becomes bars either side of the notification, which read as the app having failed to load
 * something rather than as the poster's own design. The framework cannot help: as far as it is
 * concerned those grey pixels are the image.
 *
 * So this measures the border and gives back the box inside it, and the tray uses that box. It is
 * applied to the tray bitmap ONLY -- the stored file, and therefore everything the user opens,
 * shares or saves, is always exactly what the sender published. A crop heuristic that could destroy
 * the original would not be worth having, however good.
 *
 * Pure: [trim] takes a pixel-reading function rather than a Bitmap, so the whole of it runs as a
 * JVM unit test. The interesting cases are all arithmetic.
 */
object MarginCrop {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * How far a channel may drift from the reference and still count as the same colour.
     *
     * Generous, because a JPEG border is never one flat value: compression alone moves it by
     * several levels, and a scanned page's white drifts further than that. Too tight and nothing is
     * ever trimmed, which is the failure that looks like the feature was never written.
     */
    private const val TOLERANCE = 12

    /**
     * Never remove more than this fraction from any one side.
     *
     * The guard against the case this heuristic would otherwise ruin: a photograph that happens to
     * be mostly sky, or a poster on a solid ground with its content low down. Trimming half an
     * image because the top of it is uniform would be worse than the bars.
     */
    private const val MAX_TRIM_FRACTION = 0.35

    /** At most this many probes along a row or column; a 4000px scan does not need 4000 reads. */
    private const val SAMPLES_PER_LINE = 48

    /**
     * The largest box inside [width]x[height] whose edges are not the border colour.
     *
     * [pixel] returns a packed `0xRRGGBB` (alpha ignored -- a JPEG has none, and a transparent
     * border is not the case this exists for). Returns the full frame when there is no uniform
     * border, when the image is too small to judge, or when trimming would exceed the guard.
     */
    fun trim(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Box {
        val full = Box(0, 0, width, height)
        if (width < 16 || height < 16) return full

        // The reference is the average of the four corners rather than one of them, so a single
        // stray pixel -- a rounded corner, a compression artefact -- cannot decide the whole crop.
        val corners = listOf(
            pixel(0, 0),
            pixel(width - 1, 0),
            pixel(0, height - 1),
            pixel(width - 1, height - 1),
        )
        if (corners.any { !near(it, corners[0]) }) return full
        val reference = corners[0]

        val maxTrimX = (width * MAX_TRIM_FRACTION).toInt()
        val maxTrimY = (height * MAX_TRIM_FRACTION).toInt()

        var left = 0
        while (left < maxTrimX && columnIsBorder(left, height, reference, pixel)) left++

        var right = width
        while (width - right < maxTrimX && columnIsBorder(right - 1, height, reference, pixel)) right--

        var top = 0
        while (top < maxTrimY && rowIsBorder(top, width, reference, pixel)) top++

        var bottom = height
        while (height - bottom < maxTrimY && rowIsBorder(bottom - 1, width, reference, pixel)) bottom--

        // Each loop stops for one of two reasons: it found content, or it ran into the guard. When
        // BOTH sides of an axis stopped at the guard, no content was found along that axis at all
        // -- the image is a single colour, or close enough to one. Trimming it to the guard would
        // hand back an arbitrary middle rectangle of a blank image and call it a crop.
        val axisBlank = (left >= maxTrimX && width - right >= maxTrimX) ||
            (top >= maxTrimY && height - bottom >= maxTrimY)

        val trimmed = Box(left, top, right, bottom)
        return if (axisBlank || trimmed.isEmpty) full else trimmed
    }

    /** The border colour to fill letterbox bars with, so the bars read as part of the sheet. */
    fun borderColour(pixel: (x: Int, y: Int) -> Int): Int = pixel(0, 0)

    private fun columnIsBorder(
        x: Int,
        height: Int,
        reference: Int,
        pixel: (x: Int, y: Int) -> Int,
    ): Boolean {
        val step = max(1, height / SAMPLES_PER_LINE)
        var y = 0
        while (y < height) {
            if (!near(pixel(x, y), reference)) return false
            y += step
        }
        return true
    }

    private fun rowIsBorder(
        y: Int,
        width: Int,
        reference: Int,
        pixel: (x: Int, y: Int) -> Int,
    ): Boolean {
        val step = max(1, width / SAMPLES_PER_LINE)
        var x = 0
        while (x < width) {
            if (!near(pixel(x, y), reference)) return false
            x += step
        }
        return true
    }

    private fun near(colour: Int, reference: Int): Boolean =
        abs((colour shr 16 and 0xFF) - (reference shr 16 and 0xFF)) <= TOLERANCE &&
            abs((colour shr 8 and 0xFF) - (reference shr 8 and 0xFF)) <= TOLERANCE &&
            abs((colour and 0xFF) - (reference and 0xFF)) <= TOLERANCE
}
