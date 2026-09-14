package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Test
import org.paramanuseniorshealth.notices.data.NoticeTime

class NoticeTimeTest {

    private val now = 1_789_000_000_000L

    @Test
    fun `a millisecond logId is the time the sender sent it`() {
        assertEquals(1_788_248_869_397L, NoticeTime.sentAt("1788248869397", now))
    }

    @Test
    fun `a seconds logId is scaled to milliseconds`() {
        assertEquals(1_788_248_869_000L, NoticeTime.sentAt("1788248869", now))
    }

    @Test
    fun `surrounding whitespace does not lose the sent time`() {
        assertEquals(1_788_248_869_397L, NoticeTime.sentAt(" 1788248869397 ", now))
    }

    @Test
    fun `a locally written notice falls back to now`() {
        // The welcome, revocation and test notices are written on the phone, so "now" is when they
        // were made -- there is no sender clock to prefer.
        assertEquals(now, NoticeTime.sentAt("local-welcome", now))
    }

    @Test
    fun `a missing or unusable logId falls back to now`() {
        assertEquals(now, NoticeTime.sentAt(null, now))
        assertEquals(now, NoticeTime.sentAt("", now))
        assertEquals(now, NoticeTime.sentAt("b3f1c2a4-uuid", now))
        assertEquals(now, NoticeTime.sentAt("42", now))
    }
}
