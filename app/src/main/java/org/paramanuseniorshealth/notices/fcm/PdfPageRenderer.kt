package org.paramanuseniorshealth.notices.fcm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import kotlin.math.max

/**
 * Turns page one of a notice PDF into a bitmap.
 *
 * **What changed, and why it matters.** This used to render straight onto an 800x400 canvas and
 * write *that* to disk, on the reasoning that the tray was the only consumer. It was not: the same
 * file backed the in-app viewer, so an A4 page reached the reader at roughly 283x400 pixels with
 * white bars either side, and pinch-to-zoom had nothing to zoom into. The comments even claimed the
 * full render was what got stored, which made the bug invisible to anyone reading the code.
 *
 * So the render is now page-sized -- large enough to read a circular's body text at arm's length --
 * and [TrayArtwork] narrows it for the notification. Two consumers, two sizes, neither pretending
 * to be the other.
 *
 * [TARGET_WIDTH] and [TARGET_HEIGHT] remain because [fitLetterbox] is shared geometry, used here
 * and by the tray, and it carries the integer-overflow-safe arithmetic and the tests.
 */
object PdfPageRenderer {

    private const val TAG = "PdfPageRenderer"

    /** 2:1, matching what BigPictureStyle expects. */
    const val TARGET_WIDTH = 800
    const val TARGET_HEIGHT = 400

    /**
     * The long edge of a stored render.
     *
     * An A4 page at 1600px is about 190dpi -- enough that the body text of a circular is legible
     * once zoomed, which is the whole point of keeping a render at all. Larger buys little: the
     * reader who needs more than this is better served by the PDF itself, which is now one tap
     * away.
     */
    private const val RENDER_LONG_EDGE = 1600

    /** Bars match the paper rather than the framework's black, so a notice still reads as a notice. */
    private const val PAPER = Color.WHITE

    /** Plain rectangle so the geometry is unit-testable on the JVM, where android.graphics.Rect is a stub. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Largest centred box of [srcWidth]:[srcHeight] aspect that fits inside [dstWidth]x[dstHeight].
     *
     * For an A4 portrait page in an 800x400 frame this yields roughly 283x400 centred horizontally:
     * the whole page, small, with generous margins either side. Small is acceptable in the tray --
     * the image is a recognition cue and the headline carries the message.
     */
    fun fitLetterbox(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Box {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return Box(0, 0, dstWidth.coerceAtLeast(0), dstHeight.coerceAtLeast(0))
        }
        // Integer-safe comparison of srcW/srcH against dstW/dstH, avoiding floating point.
        val widthLimited = srcWidth.toLong() * dstHeight >= srcHeight.toLong() * dstWidth
        val scaledWidth: Int
        val scaledHeight: Int
        if (widthLimited) {
            scaledWidth = dstWidth
            scaledHeight = ((srcHeight.toLong() * dstWidth) / srcWidth).toInt().coerceAtLeast(1)
        } else {
            scaledHeight = dstHeight
            scaledWidth = ((srcWidth.toLong() * dstHeight) / srcHeight).toInt().coerceAtLeast(1)
        }
        val left = (dstWidth - scaledWidth) / 2
        val top = (dstHeight - scaledHeight) / 2
        return Box(left, top, left + scaledWidth, top + scaledHeight)
    }

    /**
     * Whether [file] is a PDF this device can open.
     *
     * Used before a downloaded circular is kept and handed to a viewer: a captive-portal login page
     * or an HTML error page saved as `.pdf` downloads perfectly and then fails in whichever app the
     * user chose, where the failure looks like their PDF reader being broken.
     */
    fun isReadablePdf(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            renderer.pageCount >= 1
        } catch (e: Exception) {
            Log.w(TAG, "Not a readable PDF: ${file.name}", e)
            false
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    /**
     * How many pages [file] has, or null if it cannot be opened.
     *
     * The renderer already had this and discarded it: [renderFirstPage] opens a `PdfRenderer` and
     * reads `pageCount` purely to check it is at least one. A dispensary circular runs to about
     * 192 pages, which is worth telling the reader before they tap into it.
     */
    fun pageCount(file: File): Int? {
        if (!file.exists() || file.length() == 0L) return null
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            renderer.pageCount.takeIf { it >= 1 }
        } catch (e: Exception) {
            Log.w(TAG, "Could not count pages in ${file.name}", e)
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    /**
     * Renders page one of [file] at up to [RENDER_LONG_EDGE] on its long edge.
     *
     * Returns null for anything that is not a readable PDF -- password-protected files included,
     * which PdfRenderer refuses outright. Callers fall back to a text-only notification, never to
     * no notification.
     */
    fun renderFirstPage(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount < 1) return null

            renderer.openPage(0).use { page ->
                val longEdge = max(page.width, page.height).coerceAtLeast(1)
                val scale = RENDER_LONG_EDGE.toDouble() / longEdge
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                // PdfRenderer will only draw into ARGB_8888, and only onto what it is given, so the
                // page is rendered at its final size rather than rendered large and downscaled.
                val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Erased first: a PDF page is transparent where nothing is drawn, and transparent
                // black behind body text is unreadable in a dark-themed viewer.
                pageBitmap.eraseColor(PAPER)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageBitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not render ${file.name}", e)
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
}
