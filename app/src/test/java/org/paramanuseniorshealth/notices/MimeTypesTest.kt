package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.paramanuseniorshealth.notices.fcm.MimeTypes
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `reads a content type with parameters and odd casing`() {
        assertEquals("image/jpeg", MimeTypes.normalise("IMAGE/JPEG; charset=binary"))
        assertEquals("application/pdf", MimeTypes.normalise(" application/pdf "))
        assertEquals("", MimeTypes.normalise(null))
    }

    @Test
    fun `rejects ICO, which BitmapFactory cannot decode`() {
        // The whole reason this is a list and not a startsWith("image/") check. A favicon served as
        // ICO looks like a working image URL and renders nothing at all.
        assertFalse(MimeTypes.isDecodableImage("image/vnd.microsoft.icon"))
        assertFalse(MimeTypes.isDecodableImage("image/x-icon"))
        assertTrue(MimeTypes.isDecodableImage("image/png"))
        assertTrue(MimeTypes.isDecodableImage("image/jpeg"))
    }

    @Test
    fun `takes the extension from the content type first`() {
        assertEquals("png", MimeTypes.imageExtension("image/png", "https://x.test/a.jpg"))
        assertEquals("webp", MimeTypes.imageExtension("image/webp", null))
    }

    @Test
    fun `falls back to the URL when the server says octet-stream`() {
        // Common on static file hosts, which is exactly where notice attachments live.
        assertEquals("png", MimeTypes.imageExtension("application/octet-stream", "https://x.test/a.PNG"))
        assertEquals("jpg", MimeTypes.imageExtension(null, "https://x.test/poster.jpg?v=2"))
    }

    @Test
    fun `falls back to jpg when nothing is known`() {
        assertEquals("jpg", MimeTypes.imageExtension(null, null))
        assertEquals("jpg", MimeTypes.imageExtension("application/octet-stream", "https://x.test/poster"))
    }

    @Test
    fun `maps an extension back to a type for the Intent`() {
        assertEquals("application/pdf", MimeTypes.forExtension("pdf"))
        assertEquals("image/png", MimeTypes.forExtension(".PNG"))
        assertEquals("image/jpeg", MimeTypes.forExtension("jpg"))
    }

    @Test
    fun `keeps the sender's filename so a saved circular is findable later`() {
        assertEquals(
            "list-of-holidays-2026.pdf",
            MimeTypes.fileName("https://x.test/files/list-of-holidays-2026.pdf", "notice", "pdf"),
        )
        assertEquals(
            "sbeba-kochi-consultation-2026-09-11.jpg",
            MimeTypes.fileName(
                "https://x.test/files/sbeba-kochi-consultation-2026-09-11.jpeg?v=3",
                "notice",
                "jpg",
            ),
        )
    }

    @Test
    fun `invents a name when the URL has none`() {
        assertEquals("notice.pdf", MimeTypes.fileName("https://x.test/", "notice", "pdf"))
        assertEquals("notice.jpg", MimeTypes.fileName(null, "notice", "jpg"))
    }

    @Test
    fun `strips characters a filesystem would refuse`() {
        val name = MimeTypes.fileName("https://x.test/a%20b/notice:2026*.pdf", "fallback", "pdf")

        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertTrue(name.endsWith(".pdf"))
    }
}
