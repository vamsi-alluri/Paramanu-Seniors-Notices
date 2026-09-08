package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.ui.BodyText

class BodyTextTest {

    private fun urls(text: String) = BodyText.links(text).map { it.url }

    @Test
    fun `finds a plain https link`() {
        assertEquals(
            listOf("https://paramanuseniorshealth.org/notices"),
            urls("See https://paramanuseniorshealth.org/notices for details"),
        )
    }

    @Test
    fun `finds a www link without a scheme`() {
        assertEquals(listOf("www.paramanuseniorshealth.org"), urls("Visit www.paramanuseniorshealth.org"))
    }

    @Test
    fun `a trailing full stop is not part of the link`() {
        // The single most common way a pasted link arrives broken.
        assertEquals(
            listOf("https://paramanuseniorshealth.org"),
            urls("Details are at https://paramanuseniorshealth.org."),
        )
    }

    @Test
    fun `other trailing punctuation is trimmed too`() {
        assertEquals(listOf("https://example.org"), urls("Is it https://example.org?"))
        assertEquals(listOf("https://example.org"), urls("Go to https://example.org!"))
        assertEquals(listOf("https://example.org"), urls("https://example.org, then call"))
    }

    @Test
    fun `a closing bracket the link opened is kept`() {
        assertEquals(
            listOf("https://example.org/a_(b)"),
            urls("See https://example.org/a_(b) here"),
        )
    }

    @Test
    fun `a closing bracket the link did not open is dropped`() {
        assertEquals(listOf("https://example.org/a"), urls("(see https://example.org/a)"))
    }

    @Test
    fun `finds several links in one body`() {
        assertEquals(
            listOf("https://a.org", "www.b.org"),
            urls("First https://a.org and then www.b.org please"),
        )
    }

    @Test
    fun `plain text has no links`() {
        assertFalse(BodyText.hasLink("The dispensary is closed on Thursday."))
        assertTrue(urls("No links here at all").isEmpty())
    }

    @Test
    fun `offsets point at the link inside the original text`() {
        val text = "Go to https://example.org now"
        val link = BodyText.links(text).single()
        assertEquals("https://example.org", text.substring(link.start, link.end))
    }

    @Test
    fun `normalise adds a scheme only when missing`() {
        assertEquals("https://www.example.org", BodyText.normalise("www.example.org"))
        assertEquals("https://example.org", BodyText.normalise("https://example.org"))
        assertEquals("http://example.org", BodyText.normalise("http://example.org"))
    }

    @Test
    fun `a pdf link in a body survives intact`() {
        // Exactly the shape the NGO posts, including the hyphens and the extension.
        val url = "https://paramanuseniorshealth.org/files/dispensaries-and-opds-revised.pdf"
        assertEquals(listOf(url), urls("Full list: $url"))
    }
}
