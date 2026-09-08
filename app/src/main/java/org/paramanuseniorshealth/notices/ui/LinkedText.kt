package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

private const val URL_TAG = "url"

/**
 * Text with any links in it tappable. Used for notice bodies and for the standing information at
 * the top of the list, both of which are typed by people who paste links the way they would into a
 * WhatsApp message.
 *
 * Links are underlined as well as coloured. Colour alone is a weak signal for readers with failing
 * colour vision, which is a large share of this audience.
 *
 * [color] and [linkColor] are parameters rather than fixed, because this is drawn on two different
 * surfaces: the notice card and the tinted information header. Reading the ambient content colour
 * as the default keeps the common case correct without the caller thinking about it.
 */
@Composable
fun LinkedText(
    text: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    val resolved = if (color != Color.Unspecified) color else LocalContentColor.current

    val annotated: AnnotatedString = remember(text, linkColor) {
        val links = BodyText.links(text)
        if (links.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                var cursor = 0
                for (link in links) {
                    if (link.start > cursor) append(text.substring(cursor, link.start))
                    pushStringAnnotation(tag = URL_TAG, annotation = link.url)
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ) {
                        append(text.substring(link.start, link.end))
                    }
                    pop()
                    cursor = link.end
                }
                if (cursor < text.length) append(text.substring(cursor))
            }
        }
    }

    if (annotated.getStringAnnotations(URL_TAG, 0, annotated.length).isEmpty()) {
        Text(text = annotated, style = style, color = resolved, modifier = modifier)
    } else {
        ClickableText(
            text = annotated,
            style = style.copy(color = resolved),
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(URL_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { onOpenUrl(it.item) }
            },
        )
    }
}
