package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.paramanuseniorshealth.notices.NotificationAccent
import org.paramanuseniorshealth.notices.R
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * A disc of the level colour with the level's symbol punched out of it, for use as a notification's
 * large icon.
 *
 * The symbol is a hole rather than a drawn glyph, so it shows whatever is behind the notification
 * and stays legible in both light and dark themes without the app having to guess which one is
 * active, or having to pick an ink colour per chip.
 *
 * Overriding the large icon is what makes the level visible at all. `setColor` is advisory and OEM
 * skins largely ignore it, and `setColorized` -- which would tint the notification background -- is
 * dropped by the platform for anything that is not a foreground-service notification. The launcher
 * icon already occupies the other end of the row and cannot vary per notification, which is why
 * this chip carries meaning rather than repeating the app's mark.
 */
object LevelIcon {

    /** Matches the system's own large-icon dimension, so the bitmap is never scaled up. */
    private const val SIZE_DP = 64

    /** Leaves the symbol at half the chip's width, clear of the circle's edge. */
    private const val GLYPH_INSET = 0.25f

    /**
     * Returns null when the message carries neither a level nor a colour: an unclassified message
     * gets no chip at all, so the presence of one means "this has a severity" rather than merely
     * "this is from Paramanu Notices".
     *
     * A colour without a level yields a bare disc. We know which colour the sender asked for, but
     * not what it is meant to signify, so stamping a symbol on it would be inventing meaning.
     */
    fun bitmap(context: Context, color: String?, level: String?): Bitmap? {
        val parsed = NotificationAccent.levelOf(level)
        val chipColor = NotificationAccent.parseHex(color) ?: parsed?.trayColor ?: return null

        val size = (SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val chip = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(chip)
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = chipColor })

        val glyph = parsed?.let { ContextCompat.getDrawable(context, glyphFor(it)) } ?: return chip

        // Drawable.draw takes no Paint, so the glyph is rendered to its own bitmap and the xfermode
        // applied when that is composited: DST_OUT clears the chip wherever the glyph is opaque.
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val inset = (size * GLYPH_INSET).toInt()
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(Canvas(mask))
        canvas.drawBitmap(mask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        })
        mask.recycle()

        return chip
    }

    @DrawableRes
    private fun glyphFor(level: NotificationAccent.Level): Int = when (level) {
        NotificationAccent.Level.INFO -> R.drawable.ic_level_info
        NotificationAccent.Level.SUCCESS -> R.drawable.ic_level_success
        NotificationAccent.Level.WARNING -> R.drawable.ic_level_warning
        NotificationAccent.Level.ERROR -> R.drawable.ic_level_error
    }
}
