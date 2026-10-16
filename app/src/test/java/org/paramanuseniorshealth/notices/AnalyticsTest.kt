package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent

class AnalyticsTest {

    @Test
    fun `collects nothing until it has been switched on`() {
        // The default must be off: a failed or not-yet-finished config read must not collect.
        val sink = RecordingSink()
        Analytics(sink).log(AnalyticsEvent.ActivationSubmitted)
        assertTrue(sink.logged.isEmpty())
    }

    @Test
    fun `logs once switched on`() {
        val sink = RecordingSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationShown(hasCode = false))
        assertEquals(1, sink.logged.size)
        assertEquals("activation_shown", sink.logged.single().first)
        assertEquals(0L, sink.logged.single().second["has_code"])
    }

    @Test
    fun `switching off stops the SDK too, not only our own calls`() {
        // Guarding only our wrapper would leave GA4's automatic events flowing, which would make
        // "off" a lie.
        val sink = RecordingSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        analytics.setEnabled(false)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
        assertTrue(sink.logged.isEmpty())
        assertEquals(listOf(true, false), sink.collectionCalls)
    }

    @Test
    fun `a sink that throws never reaches the caller`() {
        // Analytics must not be able to break the thing it is measuring.
        val sink = RecordingSink(throwOnLog = true)
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
        assertEquals(1, sink.logged.size) // the call did reach the sink, so the throw was real
    }

    @Test
    fun `a sink that fails to switch the SDK still moves our own gate`() {
        // The dangerous direction is failing to turn collection off: if the SDK call throws, our
        // own events must stop anyway. The same holds for switching on, so the gate and the call
        // are never coupled.
        val sink = RecordingSink(throwOnSetCollection = true)
        val analytics = Analytics(sink)

        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
        assertEquals(1, sink.logged.size)

        analytics.setEnabled(false)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
        assertEquals(1, sink.logged.size)
        assertEquals(listOf(true, false), sink.collectionCalls)
    }

    @Test
    fun `a late duplicate event is logged, not suppressed`() {
        // NoticeImageStore.download cannot be interrupted by withTimeoutOrNull, so a fetch that
        // the caller already gave up on can still complete and report. Both events are real and
        // both are kept; de-duplication belongs in the query, not here, because the app cannot
        // know which one the analysis wants.
        val sink = RecordingSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        val event = AnalyticsEvent.AttachmentFetchEnd(
            kind = "pdf", sizeBytes = 100L, durationMs = 50L, outcome = "failed", httpStatus = null,
        )
        analytics.log(event)
        analytics.log(event)
        assertEquals(2, sink.logged.size)
    }
}
