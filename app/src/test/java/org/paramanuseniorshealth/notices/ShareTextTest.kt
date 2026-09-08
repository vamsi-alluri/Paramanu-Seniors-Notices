package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.ui.ShareText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ShareTextTest {

    private val utc = ZoneId.of("UTC")

    private fun entity(id: Long, title: String, body: String, at: Long) = NoticeEntity(
        id = id,
        logId = at.toString(),
        title = title,
        body = body,
        receivedAt = at,
    )

    /** 2026-08-27T18:49:00Z */
    private val noon = 1787856540000L

    @Test
    fun `single entry carries title, body and a zoned timestamp`() {
        val text = ShareText.build(listOf(entity(1, "running hot", "pkg=91C", noon)), utc)
        assertEquals("running hot\npkg=91C\n27 Aug 2026, 6:49 PM UTC", text)
    }

    @Test
    fun `entries are separated by two blank lines`() {
        val text = ShareText.build(
            listOf(entity(1, "first", "one", noon), entity(2, "second", "two", noon)),
            utc,
        )
        assertEquals(
            "first\none\n27 Aug 2026, 6:49 PM UTC\n\n\nsecond\ntwo\n27 Aug 2026, 6:49 PM UTC",
            text,
        )
    }

    /** The push contract requires only a title, so a blank body must not leave a dangling line. */
    @Test
    fun `omits the body line when there is no body`() {
        val text = ShareText.build(listOf(entity(1, "link RESTORED", "", noon)), utc)
        assertEquals("link RESTORED\n27 Aug 2026, 6:49 PM UTC", text)
    }

    @Test
    fun `renders the timestamp in the given zone`() {
        val text = ShareText.build(listOf(entity(1, "t", "b", noon)), ZoneId.of("Asia/Kolkata"))
        assertEquals("t\nb\n28 Aug 2026, 12:19 AM IST", text)
    }

    @Test
    fun `empty selection yields empty text`() {
        assertEquals("", ShareText.build(emptyList(), utc))
    }
}
