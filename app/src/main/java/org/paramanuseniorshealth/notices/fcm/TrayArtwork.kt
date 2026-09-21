package org.paramanuseniorshealth.notices.fcm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/**
 * Everything the notification tray sees, and nothing else.
 *
 * A notification crosses a Binder transaction capped near 1MB, and going over does not lose the
 * picture -- it throws TransactionTooLargeException and loses the entire notification. So the tray
 * needs its own budget, kept in one place, away from the files on disk.
 *
 * The budget used to live implicitly in [NoticeImageStore.decodeDownsampled], sized for A4 pages
 * rendered at 800x400. Portrait photographs are the case it was never sized for: a 976x1600 poster
 * downsamples to 488x800, which is 780KB in RGB_565 -- and the same bitmap was then also handed to
 * setLargeIcon, so one notice carried it twice.
 *
 * Two bitmaps, deliberately different sizes:
 *  - [bigPicture] is the expanded view, 640x320 in RGB_565, about 400KB;
 *  - [largeIcon] is the collapsed thumbnail, 192x192, about 74KB, because the tray draws it at
 *    roughly 48dp and sending a full-size bitmap to be scaled into a square is pure waste.
 *
 * Together they are comfortably under half the cap, with room for the text and the PendingIntent.
 *
 * Nothing here touches a stored file. What the user opens, shares or saves is always exactly what
 * the sender published.
 */
object TrayArtwork {

    /** 2:1, matching what BigPictureStyle expects, at the largest size the budget allows. */
    const val PICTURE_WIDTH = 640
    const val PICTURE_HEIGHT = 320

    /** The collapsed thumbnail. The tray draws it around 48dp; this is generous for that. */
    const val ICON_SIZE = 192

    /**
     * Below this, an image is a logo rather than a picture.
     *
     * A favicon is 128px and looks absurd stretched across a notification; a YouTube thumbnail is
     * 320x180 at its smallest and looks right. This is the line between them, and it is why a link
     * card sometimes fills the tray and sometimes does not.
     *
     * The test is applied in [NoticeNotifications.updatePicture], and only to a link image -- a
     * sender's photograph and a rendered circular page are both meant to be looked at, whatever
     * size they arrive in.
     */
    const val MIN_PICTURE_DIMENSION = 240

    /** Whether [bitmap] is worth showing as the expanded picture at all. */
    fun isPictureWorthy(bitmap: Bitmap): Boolean =
        bitmap.width >= MIN_PICTURE_DIMENSION && bitmap.height >= MIN_PICTURE_DIMENSION

    /**
     * The expanded picture: margins trimmed, letterboxed onto 2:1, in RGB_565.
     *
     * Letterboxed rather than centre-cropped because BigPictureStyle's own crop would take the top
     * and bottom off a portrait notice -- the letterhead and the date and signature, precisely the
     * parts that make it look official rather than like a rumour. Choosing to lose nothing is worth
     * the bars, and the bars are filled with the poster's own border colour rather than black, so
     * on a white sheet they disappear into it.
     */
    fun bigPicture(source: Bitmap): Bitmap {
        val cropped = trimmed(source)
        val box = fit(cropped.width, cropped.height, PICTURE_WIDTH, PICTURE_HEIGHT)

        val canvasBitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(0xFF000000.toInt() or MarginCrop.borderColour(source::getPixel))
            drawBitmap(cropped, null, Rect(box.left, box.top, box.right, box.bottom), null)
        }
        if (cropped !== source) cropped.recycle()

        // RGB_565 halves the cost of the trip through Binder. Text suffers slightly at 16-bit
        // colour; that is the right trade against losing the notification outright.
        val budgeted = canvasBitmap.copy(Bitmap.Config.RGB_565, false)
        return if (budgeted != null) {
            canvasBitmap.recycle()
            budgeted
        } else {
            canvasBitmap
        }
    }

    /** The collapsed thumbnail: a centre square, scaled down. */
    fun largeIcon(source: Bitmap): Bitmap {
        val cropped = trimmed(source)
        val side = minOf(cropped.width, cropped.height)
        val left = (cropped.width - side) / 2
        val top = (cropped.height - side) / 2

        val square = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(
            cropped,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, ICON_SIZE, ICON_SIZE),
            null,
        )
        if (cropped !== source) cropped.recycle()

        val budgeted = square.copy(Bitmap.Config.RGB_565, false)
        return if (budgeted != null) {
            square.recycle()
            budgeted
        } else {
            square
        }
    }

    /** [source] with any uniform border removed, or [source] itself when there is none. */
    private fun trimmed(source: Bitmap): Bitmap {
        val box = MarginCrop.trim(source.width, source.height, source::getPixel)
        if (box.left == 0 && box.top == 0 && box.right == source.width && box.bottom == source.height) {
            return source
        }
        return Bitmap.createBitmap(source, box.left, box.top, box.width, box.height)
    }

    /**
     * Largest centred box of [srcWidth]:[srcHeight] that fits inside [dstWidth]x[dstHeight].
     *
     * Delegates to [PdfPageRenderer.fitLetterbox], which already carries the integer-overflow-safe
     * arithmetic and its own tests. Duplicating it here so the tray and the PDF render could drift
     * apart would be the kind of near-copy that stays correct until exactly one of them is fixed.
     */
    private fun fit(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int) =
        PdfPageRenderer.fitLetterbox(srcWidth, srcHeight, dstWidth, dstHeight)
}
