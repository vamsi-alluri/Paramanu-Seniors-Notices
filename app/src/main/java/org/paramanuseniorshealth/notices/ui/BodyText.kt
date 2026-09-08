package org.paramanuseniorshealth.notices.ui

/**
 * Finds links in the plain text of a notice body.
 *
 * Kept as a pure function so it carries JVM unit tests: the interesting cases are all about where a
 * link *ends*, and getting that wrong is how a URL arrives on someone's phone with the sentence's
 * full stop welded onto it.
 */
object BodyText {

    data class Link(val start: Int, val end: Int, val url: String)

    /**
     * `www.` is matched as well as a full scheme, because senders type it that way; [normalise]
     * puts a scheme back on before the link is opened.
     */
    private val PATTERN = Regex(
        """(?i)\b(?:https?://|www\.)[^\s<>"']+""",
    )

    /**
     * Trailing punctuation is trimmed, since a sentence usually ends right after the link. Closing
     * brackets are kept only when the link opened one -- Wikipedia-style URLs really do contain
     * them, so a blanket trim would break exactly the links most likely to be pasted.
     */
    private const val TRAILING = ".,;:!?'\""

    fun links(text: String): List<Link> = PATTERN.findAll(text).mapNotNull { match ->
        var end = match.range.last + 1
        var candidate = text.substring(match.range.first, end)

        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            val trimmable = last in TRAILING ||
                (last == ')' && candidate.count { it == '(' } < candidate.count { it == ')' })
            if (!trimmable) break
            candidate = candidate.dropLast(1)
            end--
        }

        // A bare "www." or a scheme with nothing after it is not a link.
        if (candidate.length < 8 || candidate.endsWith("/") && candidate.count { it == '.' } == 0) {
            return@mapNotNull null
        }
        Link(start = match.range.first, end = end, url = candidate)
    }.toList()

    /** Adds a scheme to a `www.` link so it can be handed to a browser. */
    fun normalise(url: String): String =
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) url
        else "https://$url"

    fun hasLink(text: String): Boolean = links(text).isNotEmpty()
}
