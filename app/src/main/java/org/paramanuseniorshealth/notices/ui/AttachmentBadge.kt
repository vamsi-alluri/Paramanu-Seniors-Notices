package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * What the reader is told about an attachment before they commit to opening it: type, page count,
 * size -- whichever of those are known.
 *
 * [label] and [formatSize] carry no Android import on purpose, so they are plain JVM functions a
 * unit test can call directly. The composable below is a thin skin over the string they produce.
 */
object AttachmentBadge {

    private const val KB = 1_024L
    private const val MB = 1_024L * 1_024L

    /**
     * `PDF · 192 pages · 5.6 MB`, degrading a field at a time as [pages] and [bytes] go unknown --
     * both are null until the document has been fetched or the pipeline has published them, which
     * is the ordinary state for a fresh notice, not a failure to report.
     *
     * A [pages] of zero or less is bad upstream data, not a real page count, so it is treated the
     * same as unknown rather than rendered as "0 pages".
     */
    fun label(isPdf: Boolean, pages: Int?, bytes: Long?): String {
        val kind = if (isPdf) "PDF" else "Image"
        val pageText = pages?.takeIf { it > 0 }?.let { if (it == 1) "1 page" else "$it pages" }
        val sizeText = formatSize(bytes)
        // U+00B7 (middle dot), spaces either side, matching the badge table in the spec.
        return listOfNotNull(kind, pageText, sizeText).joinToString(" · ")
    }

    /**
     * `5.6 MB`, `300 kB`, `12 kB` -- one decimal place above a megabyte, none below, SI units
     * because that is what the file manager on the phone will also say.
     *
     * Ceiling division below a megabyte, so a real file never reads as `0 kB`: the one reading that
     * would look like an error rather than a small file. An empty file (`bytes <= 0`) is the
     * exception -- there is nothing to round up to.
     *
     * [Locale.ROOT], not the default locale: `%.1f` under a Devanagari-digit default locale
     * renders in Devanagari digits, while the page count next to it in [label] is plain
     * `Int.toString` and stays ASCII -- one badge, two digit systems, for readers in their
     * eighties who do not need that puzzle.
     */
    fun formatSize(bytes: Long?): String? {
        if (bytes == null) return null
        return when {
            bytes <= 0 -> "0 kB"
            bytes < MB -> "${(bytes + KB - 1) / KB} kB"
            else -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / MB)
        }
    }
}

/**
 * The WhatsApp-style label along an expanded attachment's bottom edge.
 *
 * A dark scrim behind light text rather than a caption drawn below the picture: it is part of the
 * picture, not a line of text under it, so it costs no vertical space and reads as a property of
 * the thing rather than an annotation on it. Callers overlay this at [androidx.compose.ui.Alignment.BottomStart]
 * inside a `Box` wrapping the attachment image, and never draw it on the 56dp collapsed thumbnail --
 * at that size it would be illegible.
 */
@Composable
fun AttachmentBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}
