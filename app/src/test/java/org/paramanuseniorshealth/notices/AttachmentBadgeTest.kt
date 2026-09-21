package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.ui.AttachmentBadge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AttachmentBadgeTest {

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `pdf with pages and size`() {
        assertEquals(
            "PDF · 192 pages · 5.6 MB",
            AttachmentBadge.label(isPdf = true, pages = 192, bytes = 5_872_345),
        )
    }

    @Test
    fun `pdf with one page is singular`() {
        assertEquals(
            "PDF · 1 page · 300 kB",
            AttachmentBadge.label(isPdf = true, pages = 1, bytes = 307_200),
        )
    }

    @Test
    fun `pdf with nothing known yet`() {
        assertEquals("PDF", AttachmentBadge.label(isPdf = true, pages = null, bytes = null))
    }

    @Test
    fun `pdf with pages but no size`() {
        assertEquals(
            "PDF · 192 pages",
            AttachmentBadge.label(isPdf = true, pages = 192, bytes = null),
        )
    }

    @Test
    fun `pdf with size but no pages`() {
        assertEquals(
            "PDF · 5.6 MB",
            AttachmentBadge.label(isPdf = true, pages = null, bytes = 5_872_345),
        )
    }

    @Test
    fun `image with size`() {
        assertEquals(
            "Image · 1.2 MB",
            AttachmentBadge.label(isPdf = false, pages = null, bytes = 1_258_291),
        )
    }

    @Test
    fun `image with nothing known`() {
        assertEquals("Image", AttachmentBadge.label(isPdf = false, pages = null, bytes = null))
    }

    @Test
    fun `zero pages is bad data, treated as absent`() {
        assertEquals("PDF", AttachmentBadge.label(isPdf = true, pages = 0, bytes = null))
    }

    @Test
    fun `negative pages is bad data, treated as absent`() {
        assertEquals("PDF", AttachmentBadge.label(isPdf = true, pages = -3, bytes = null))
    }

    @Test
    fun `formatSize of zero`() {
        assertEquals("0 kB", AttachmentBadge.formatSize(0))
    }

    @Test
    fun `formatSize never rounds a real file down to zero`() {
        assertEquals("1 kB", AttachmentBadge.formatSize(1_023))
    }

    @Test
    fun `formatSize at the megabyte boundary`() {
        assertEquals("1.0 MB", AttachmentBadge.formatSize(1_048_576))
    }

    @Test
    fun `formatSize of null is null`() {
        assertNull(AttachmentBadge.formatSize(null))
    }

    /**
     * A default locale whose numbering system is Devanagari must not change the MB figure --
     * otherwise it reads in Devanagari digits next to a page count that is plain ASCII
     * `Int.toString`, mixing two digit systems in one badge.
     */
    @Test
    fun `formatSize is stable under a non-ASCII-digit default locale`() {
        Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"))
        assertEquals("5.6 MB", AttachmentBadge.formatSize(5_872_345))
    }
}
