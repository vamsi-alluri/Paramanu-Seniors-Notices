package org.paramanuseniorshealth.notices.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat

private const val URL_TAG = "banner-url"

/**
 * Renders the dispensary banner from the HTML stored at `/info/html`.
 *
 * The markup arrives already whitelisted -- by the console page on paste, by the console server on
 * save, and by [HtmlBanner] again here on the way in, because the phone must never depend on either
 * of the other two having behaved.
 *
 * Parsing goes through the platform's own HTML-to-Spanned converter rather than a hand-written
 * parser. It understands exactly the subset the whitelist permits -- bold, italic, underline,
 * `color`, `background-color` and links -- and it has seen far more malformed markup than anything
 * written for this project ever will.
 *
 * **No font size is applied.** The authors write at fixed point sizes in Gmail and Word; honouring
 * those would override whatever text size the reader has chosen on their phone, which for this
 * audience is often turned right up. Emphasis is carried by weight, underline and colour instead.
 */
@Composable
fun BannerText(
    html: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val annotated = remember(html) { html.toBannerAnnotatedString() }
    val style = MaterialTheme.typography.bodyLarge.copy(color = color)

    if (annotated.getStringAnnotations(URL_TAG, 0, annotated.length).isEmpty()) {
        androidx.compose.material3.Text(text = annotated, style = style, modifier = modifier)
    } else {
        ClickableText(
            text = annotated,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(URL_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { onOpenUrl(it.item) }
            },
        )
    }
}

/**
 * Converts whitelisted HTML into styled text.
 *
 * Spans are applied in the order the platform reports them, and Compose merges overlapping styles,
 * so a bold red highlighted run comes through with all three intact.
 */
internal fun String.toBannerAnnotatedString(): AnnotatedString {
    val spanned: Spanned = HtmlCompat.fromHtml(
        HtmlBanner.sanitise(this),
        HtmlCompat.FROM_HTML_MODE_COMPACT,
    )

    return buildAnnotatedString {
        // trimEnd: the converter leaves trailing newlines after a closing block tag, which would
        // otherwise show as blank lines at the bottom of the card.
        val text = spanned.toString().trimEnd()
        append(text)

        for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
            val start = spanned.getSpanStart(span)
            val end = minOf(spanned.getSpanEnd(span), text.length)
            if (start !in 0..end || start == end) continue

            when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    Typeface.BOLD_ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                        start, end,
                    )
                }

                is UnderlineSpan -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline), start, end,
                )

                is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)

                is BackgroundColorSpan -> addStyle(SpanStyle(background = Color(span.backgroundColor)), start, end)

                is URLSpan -> {
                    // Underlined as well as coloured: colour alone is a weak signal for readers
                    // with failing colour vision, which is much of this audience.
                    addStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline),
                        start, end,
                    )
                    addStringAnnotation(URL_TAG, span.url, start, end)
                }
            }
        }
    }
}
