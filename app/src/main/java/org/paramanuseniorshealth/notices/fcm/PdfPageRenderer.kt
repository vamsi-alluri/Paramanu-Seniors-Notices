package org.paramanuseniorshealth.notices.fcm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Turns page one of a notice PDF into a bitmap suitable for a tray notification.
 *
 * Two constraints shape everything here.
 *
 * **BigPictureStyle wants roughly 2:1 landscape and centre-crops anything else.** Notices are A4
 * portrait, so letting the system crop would silently remove the letterhead at the top and the date
 * and signature at the bottom -- precisely the parts that make a notice look official rather than
 * like a rumour. The page is therefore letterboxed onto a 2:1 canvas here, where we choose what is
 * lost (nothing) instead of the framework choosing.
 *
 * **A notification crosses a Binder transaction capped near 1MB.** `ARGB_8888` costs four bytes a
 * pixel, so the 800x400 target is 1.28MB on its own -- over the limit and enough to throw
 * TransactionTooLargeException, which loses the entire notification rather than just the picture.
 * [toNotificationBitmap] copies to `RGB_565` at two bytes a pixel (640KB) for the tray. The full
 * ARGB render is what gets written to disk for the in-app viewer, where no such cap applies.
 */
object PdfPageRenderer {

    private const val TAG = "PdfPageRenderer"

    /** 2:1, matching what BigPictureStyle expects. */
    const val TARGET_WIDTH = 800
    const val TARGET_HEIGHT = 400

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
     * the whole page, small, with generous white margins either side. Small is acceptable -- the
     * tray image is a recognition cue, and the text headline carries the actual message.
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
     * Renders page one of [file], letterboxed onto a [TARGET_WIDTH]x[TARGET_HEIGHT] canvas.
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
                val box = fitLetterbox(page.width, page.height, TARGET_WIDTH, TARGET_HEIGHT)

                // PdfRenderer will only draw into ARGB_8888, so the page is rendered at its final
                // on-canvas size and then composited, rather than rendered large and downscaled.
                val pageBitmap = Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888)
                pageBitmap.eraseColor(PAPER)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val canvasBitmap =
                    Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
                Canvas(canvasBitmap).apply {
                    drawColor(PAPER)
                    drawBitmap(pageBitmap, null, Rect(box.left, box.top, box.right, box.bottom), null)
                }
                pageBitmap.recycle()
                canvasBitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not render ${file.name}", e)
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    /**
     * Halves the byte cost for the trip through Binder. Text suffers slightly at 16-bit colour;
     * that is the right trade against losing the notification outright, and the in-app viewer still
     * shows the full-quality render.
     */
    fun toNotificationBitmap(source: Bitmap): Bitmap =
        source.copy(Bitmap.Config.RGB_565, false) ?: source
}
