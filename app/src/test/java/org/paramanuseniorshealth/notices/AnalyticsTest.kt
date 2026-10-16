package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.analytics.AnalyticsSink

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
        val throwing = object : AnalyticsSink {
            override fun log(name: String, params: Map<String, Any>) = error("sink is broken")
            override fun setCollectionEnabled(enabled: Boolean) = Unit
        }
        val analytics = Analytics(throwing)
        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
    }
}
