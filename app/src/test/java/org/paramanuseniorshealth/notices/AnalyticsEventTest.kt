package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent

class AnalyticsEventTest {

    @Test
    fun `fetch policy decision carries the network facts`() {
        val event = AnalyticsEvent.FetchPolicyDecision(
            kind = "pdf", metered = true, roaming = false, sizeBytes = 5_600_000, fetched = false,
        )
        assertEquals("fetch_policy_decision", event.name)
        assertEquals("pdf", event.params["kind"])
        assertEquals(1L, event.params["metered"])
        assertEquals(0L, event.params["roaming"])
        assertEquals(5_600_000L, event.params["size_bytes"])
        assertEquals("defer", event.params["decision"])
        assertEquals(1L, event.params["size_known"])
    }

    @Test
    fun `an unknown size is absent, never zero`() {
        // A server that sends no Content-Length must not be recorded as a zero-byte file: zero is
        // a measurement, and this is the absence of one.
        val event = AnalyticsEvent.FetchPolicyDecision(
            kind = "img", metered = true, roaming = false, sizeBytes = null, fetched = false,
        )
        assertFalse(event.params.containsKey("size_bytes"))
        assertEquals(0L, event.params["size_known"])
    }

    @Test
    fun `every event and parameter name is within GA4 limits`() {
        // Over-long names are dropped silently at runtime and surface as a column that is always
        // null -- a failure that only shows up days later in BigQuery.
        val all = listOf(
            AnalyticsEvent.FetchPolicyDecision("pdf", true, false, 1L, true),
            AnalyticsEvent.NetworkStateOnOpen(metered = true, roaming = false),
            AnalyticsEvent.AttachmentFetchStart("pdf", 1L, 30L, 1L, 2L, 0),
            AnalyticsEvent.AttachmentFetchEnd("pdf", 1L, 250L, "fetched", 200),
            AnalyticsEvent.NoticeReceived(hasImage = true, hasPdf = false, isNew = true, deliveryLagMs = 10L, msSinceProcessStart = 20L),
            AnalyticsEvent.NotificationTapped(msSinceShown = 5L, coldStart = true),
            AnalyticsEvent.AppOpen(source = "launcher"),
            AnalyticsEvent.NoticeCardVisible(noticeId = 1L, visibleMs = 900L, hasImage = true, hasPdf = false, position = 3),
            AnalyticsEvent.ImageViewerClosed(noticeId = 1L, durationMs = 400L, maxScale = 2.5f),
            AnalyticsEvent.PdfOpened(noticeId = 1L, sizeBytes = 1L, wasCached = true),
            AnalyticsEvent.ActivationShown(hasCode = false),
            AnalyticsEvent.ActivationSubmitted,
            AnalyticsEvent.ActivationResult(result = "offline", offlineCause = "timeout"),
        )
        for (event in all) {
            assertTrue("event name too long: ${event.name}", event.name.length <= 40)
            assertTrue("event name not snake_case: ${event.name}", event.name.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue("too many params on ${event.name}", event.params.size <= 25)
            for (key in event.params.keys) {
                assertTrue("param too long on ${event.name}: $key", key.length <= 40)
                assertTrue("param not snake_case on ${event.name}: $key", key.matches(Regex("[a-z][a-z0-9_]*")))
            }
        }
    }

    @Test
    fun `no event carries notice text`() {
        val event = AnalyticsEvent.NoticeCardVisible(
            noticeId = 7L, visibleMs = 100L, hasImage = false, hasPdf = false, position = 0,
        )
        assertEquals(7L, event.params["notice_id"])
        assertFalse(event.params.containsKey("title"))
        assertFalse(event.params.containsKey("body"))
    }
}
