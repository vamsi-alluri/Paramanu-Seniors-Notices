package org.paramanuseniorshealth.notices.ui

/**
 * Cleans the banner HTML the console stores at `/dispensaries/{id}/info/html` before it is parsed for
 * display.
 *
 * The console sanitises on save, so this is the second line rather than the first. It exists
 * because the phone must never depend on the console having behaved: a banner written before the
 * whitelist existed, or edited by hand in the Firebase console, still has to render safely.
 *
 * What survives: bold, italic, underline, line breaks, paragraphs, links, and the two colour
 * properties the NGO actually uses -- text colour and highlight.
 *
 * What is deliberately dropped:
 *  - **font-size and font-family.** The banner is authored in Gmail at fixed point sizes, which
 *    would fight the reader's system font setting. Emphasis is carried by weight and colour
 *    instead, and size stays under the user's control.
 *  - Everything else: classes, ids, line-height, Word's `mso-` properties, scripts, event handlers.
 *
 * Kept as pure string work so it carries JVM unit tests. The parse into styled text needs Android
 * and is done separately.
 */
object HtmlBanner {

    /**
     * `font` is here only because `contenteditable` still emits it for a colour change. Both the
     * console page and the console server rewrite it to a span before storing, so in practice the
     * phone never sees one -- but a banner hand-edited in the Firebase console could contain it,
     * and dropping it would lose exactly the colour the author was trying to set.
     */
    private val ALLOWED_TAGS =
        setOf("b", "strong", "i", "em", "u", "br", "p", "span", "a", "div", "font")

    private val FONT_COLOR = Regex("""color\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /**
     * Only colour reaches the renderer, under two spellings.
     *
     * Gmail writes the highlight as the `background` shorthand, Word writes `background-color`, and
     * the NGO's banner contains both. Accepting only the long form would have silently dropped the
     * amber highlight from the telephone numbers -- the one piece of formatting most worth keeping.
     * The shorthand is normalised to `background-color` so the renderer sees one spelling.
     */
    private val ALLOWED_STYLES = setOf("color", "background-color", "background")

    private val SAFE_LINK = Regex("^(https?://|mailto:|tel:)", RegexOption.IGNORE_CASE)

    /** `<script>`, `<style>` and their contents, however they are cased or spaced. */
    private val DANGEROUS_BLOCKS =
        Regex("""<\s*(script|style)\b[^>]*>.*?<\s*/\s*\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val COMMENTS = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<\s*(/?)\s*([a-zA-Z0-9]+)([^>]*)>""")
    private val STYLE_ATTR = Regex("""style\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""href\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    fun sanitise(html: String): String {
        var out = DANGEROUS_BLOCKS.replace(html, "")
        out = COMMENTS.replace(out, "")

        out = TAG.replace(out) { match ->
            val closing = match.groupValues[1] == "/"
            val tag = match.groupValues[2].lowercase()
            val attrs = match.groupValues[3]

            if (tag !in ALLOWED_TAGS) return@replace ""
            // A closing </font> becomes </span>, matching the opening tag rewritten below.
            if (closing) return@replace if (tag == "font") "</span>" else "</$tag>"

            when (tag) {
                "br" -> "<br>"
                "font" -> {
                    val colour = FONT_COLOR.find(attrs)
                        ?.let { it.groupValues[2] + it.groupValues[3] }?.trim()
                    if (colour != null && isColour(colour)) "<span style=\"color:$colour\">" else "<span>"
                }
                "span", "p", "div" -> {
                    val style = keptStyle(attrs)
                    if (style.isEmpty()) "<$tag>" else "<$tag style=\"$style\">"
                }
                "a" -> {
                    val href = HREF_ATTR.find(attrs)?.let { it.groupValues[2] + it.groupValues[3] }?.trim()
                    // A link that is not plainly safe becomes plain text rather than disappearing:
                    // dropping the tag keeps the words the reader needs.
                    if (href != null && SAFE_LINK.containsMatchIn(href)) "<a href=\"${escapeAttr(href)}\">" else ""
                }
                else -> "<$tag>"
            }
        }
        return out.trim()
    }

    /** Keeps only colour and background-colour from a style attribute, dropping sizes and fonts. */
    private fun keptStyle(attrs: String): String {
        val raw = STYLE_ATTR.find(attrs)?.let { it.groupValues[2] + it.groupValues[3] } ?: return ""
        return raw.split(";")
            .mapNotNull { declaration ->
                val parts = declaration.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val property = parts[0].trim().lowercase()
                val value = parts[1].trim()
                if (property !in ALLOWED_STYLES || value.isEmpty()) return@mapNotNull null

                // The shorthand can carry more than a colour (`background: url(...) no-repeat`).
                // Only a bare colour is accepted; anything else is discarded rather than guessed at.
                if (!isColour(value)) return@mapNotNull null

                val canonical = if (property == "background") "background-color" else property
                "$canonical:${toHex(value)}"
            }
            .joinToString(";")
    }

    private val HEX = Regex("""^#[0-9a-fA-F]{3,8}$""")
    private val RGB = Regex("""^rgba?\(\s*[0-9.,\s%]+\)$""", RegexOption.IGNORE_CASE)
    private val NAMED = Regex("""^[a-zA-Z]{3,20}$""")

    /** True for a bare colour: a hex triple, an rgb()/rgba() call, or a CSS colour name. */
    private fun isColour(value: String): Boolean =
        HEX.matches(value) || RGB.matches(value) || NAMED.matches(value)

    private val RGB_PARTS = Regex("""rgba?\(([^)]*)\)""", RegexOption.IGNORE_CASE)

    /**
     * Rewrites `rgb(63, 81, 181)` as `#3f51b5`.
     *
     * Android's HTML-to-Spanned converter understands `#hex` and CSS colour names, and **nothing
     * else** -- handed a functional `rgb()` value it produces no span at all and the colour is
     * silently lost, with nothing in the log to say so. Browsers normalise every colour to that
     * form, so anything pasted from Gmail or Word arrives in exactly the notation the parser
     * cannot read. This is the reason the first banner rendered entirely in black.
     *
     * Alpha is dropped rather than approximated: these are highlights on a paper-coloured card,
     * and a wrong opacity would be a subtler and more confusing error than an opaque one.
     */
    private fun toHex(value: String): String {
        val match = RGB_PARTS.find(value) ?: return value
        val numbers = match.groupValues[1]
            .split(",")
            .mapNotNull { it.trim().removeSuffix("%").toFloatOrNull() }
        if (numbers.size < 3) return value
        val (r, g, b) = numbers
        fun channel(v: Float) = v.toInt().coerceIn(0, 255)
        return String.format("#%02x%02x%02x", channel(r), channel(g), channel(b))
    }

    private fun escapeAttr(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** True when there is anything worth rendering once the markup is stripped. */
    fun hasContent(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        return TAG.replace(html, "")
            .replace("&nbsp;", " ")
            .replace(Regex("""&[a-zA-Z#0-9]+;"""), "")
            .isNotBlank()
    }
}
