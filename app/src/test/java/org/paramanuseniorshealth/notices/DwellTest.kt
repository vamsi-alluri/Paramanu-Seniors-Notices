package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.CardDwellTracker
import org.paramanuseniorshealth.notices.analytics.CardFacts
import org.paramanuseniorshealth.notices.analytics.ViewerDwell

class DwellTest {

    // Notices 100, 200, 300 in list order. The header key is a String, as in the real list.
    // Cards are tracked by rowid, which is what the list keys on, but a stretch reports the
    // sender's log id -- so the keys here are Longs and the assertions are on Strings.
    private val order = listOf(100L, 200L, 300L)
    private val facts: (Long) -> CardFacts? = { id ->
        order.indexOf(id).takeIf { it >= 0 }
            ?.let {
                CardFacts(
                    logId = "log-$id",
                    position = it + 1,
                    hasImage = id == 100L,
                    hasPdf = id == 200L,
                )
            }
    }
    private val header = "office-info"

    @Test
    fun `a card appearing then disappearing yields one stretch of the right length`() {
        val tracker = CardDwellTracker()
        assertTrue(tracker.update(listOf(header, 100L), 1_000, facts).isEmpty())
        val done = tracker.update(listOf(header), 1_750, facts)
        assertEquals(1, done.size)
        assertEquals("log-100", done[0].logId)
        assertEquals(750L, done[0].visibleMs)
        assertEquals(true, done[0].hasImage)
        assertEquals(false, done[0].hasPdf)
    }

    @Test
    fun `appearing, disappearing and reappearing yields two stretches`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(100L), 0, facts)
        val first = tracker.update(emptyList(), 100, facts)
        tracker.update(listOf(100L), 500, facts)
        val second = tracker.update(emptyList(), 900, facts)
        assertEquals(listOf(100L), first.map { it.visibleMs })
        assertEquals(listOf(400L), second.map { it.visibleMs })
    }

    @Test
    fun `a card staying visible across updates is not reported or restarted`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(100L), 0, facts)
        assertTrue(tracker.update(listOf(100L, 200L), 300, facts).isEmpty())
        val done = tracker.update(listOf(200L), 1_000, facts)
        assertEquals(listOf(1_000L), done.map { it.visibleMs })
    }

    @Test
    fun `a card visible at the end is flushed exactly once`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(100L, 200L), 0, facts)
        val flushed = tracker.flush(2_000)
        assertEquals(setOf("log-100", "log-200"), flushed.map { it.logId }.toSet())
        assertEquals(listOf(2_000L, 2_000L), flushed.map { it.visibleMs })
        assertTrue(tracker.flush(9_000).isEmpty())
    }

    @Test
    fun `re-entry after a flush starts from zero`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(100L), 0, facts)
        tracker.flush(5_000)
        tracker.update(listOf(100L), 60_000, facts)
        val done = tracker.update(emptyList(), 60_250, facts)
        assertEquals(listOf(250L), done.map { it.visibleMs })
    }

    @Test
    fun `a card that never appears yields nothing`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(100L), 0, facts)
        val done = tracker.flush(1_000)
        assertEquals(listOf("log-100"), done.map { it.logId }) // 200 and 300 were never visible
        assertTrue(CardDwellTracker().flush(1_000).isEmpty())
        assertTrue(CardDwellTracker().update(emptyList(), 5, facts).isEmpty())
    }

    @Test
    fun `the string header is ignored and long keys are tracked`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(header, 300L), 0, facts)
        val done = tracker.update(emptyList(), 10, facts)
        assertEquals(listOf("log-300"), done.map { it.logId })
    }

    @Test
    fun `an id no longer in the list is not tracked, and is reported if it vanishes while tracked`() {
        val tracker = CardDwellTracker()
        assertTrue(tracker.update(listOf(999L), 0, facts).isEmpty())
        assertTrue(tracker.flush(100).isEmpty())

        tracker.update(listOf(100L), 0, facts)
        val gone = tracker.update(listOf(100L), 400) { null }
        assertEquals(listOf(400L), gone.map { it.visibleMs })
    }

    @Test
    fun `position is one-based among notices with the header excluded`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(header, 100L, 200L, 300L), 0, facts)
        val done = tracker.flush(1).associate { it.logId to it.position }
        assertEquals(mapOf("log-100" to 1, "log-200" to 2, "log-300" to 3), done)
    }

    @Test
    fun `position is the one captured when the card appeared`() {
        val tracker = CardDwellTracker()
        tracker.update(listOf(200L), 0, facts)
        // A notice arrived above it; the lookup now says 2 -> 3.
        val shifted: (Long) -> CardFacts? =
            { CardFacts(logId = "log-200", position = 3, hasImage = false, hasPdf = true) }
        val done = tracker.update(emptyList(), 50, shifted)
        assertEquals(2, done[0].position)
    }

    // ---- viewer

    private class FakeClock(var now: Long = 0) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun `viewer counts only started time`() {
        val clock = FakeClock()
        val dwell = ViewerDwell(clock)
        clock.now = 100; dwell.started()
        clock.now = 400; dwell.stopped()
        clock.now = 50_000 // backgrounded
        clock.now = 60_000; dwell.started()
        clock.now = 60_200
        assertEquals(500L, dwell.finish())
    }

    @Test
    fun `viewer disposed while started includes the open segment`() {
        val clock = FakeClock()
        val dwell = ViewerDwell(clock)
        clock.now = 10; dwell.started()
        clock.now = 110
        assertEquals(100L, dwell.finish())
    }

    @Test
    fun `viewer disposed while stopped adds nothing more`() {
        val clock = FakeClock()
        val dwell = ViewerDwell(clock)
        clock.now = 0; dwell.started()
        clock.now = 300; dwell.stopped()
        clock.now = 99_999
        assertEquals(300L, dwell.finish())
    }

    @Test
    fun `viewer repeated start or stop does not double count`() {
        val clock = FakeClock()
        val dwell = ViewerDwell(clock)
        dwell.stopped() // stop before any start
        clock.now = 0; dwell.started()
        clock.now = 100; dwell.started() // repeated start must not restart the segment
        clock.now = 200; dwell.stopped()
        clock.now = 300; dwell.stopped()
        assertEquals(200L, dwell.finish())
    }

    @Test
    fun `viewer never started reports zero`() {
        assertEquals(0L, ViewerDwell(FakeClock(123)).finish())
    }
}
