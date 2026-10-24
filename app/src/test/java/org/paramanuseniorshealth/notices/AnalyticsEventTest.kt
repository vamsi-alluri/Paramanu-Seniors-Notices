package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.CodeManagementResult
import org.paramanuseniorshealth.notices.activation.PublicCode
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.analytics.analyticsName

class AnalyticsEventTest {

    /**
     * One of every variant, every optional parameter present. sealedSubclasses would need
     * kotlin-reflect, so the list is hand-written: a fourteenth variant must be added here
     * deliberately, and the size check below is what makes forgetting it fail.
     */
    private val allEvents: List<AnalyticsEvent> = listOf(
        AnalyticsEvent.FetchPolicyDecision("pdf", true, false, 1L, "reachable", true),
        AnalyticsEvent.NetworkStateOnOpen(metered = true, roaming = false),
        AnalyticsEvent.AttachmentFetchStart("pdf", 1L, 30L, 1L, 2L, 0),
        AnalyticsEvent.AttachmentFetchEnd("pdf", 1L, 250L, "fetched", 200),
        AnalyticsEvent.NoticeReceived(logId = "1792473610441", hasImage = true, hasPdf = false, isNew = true, deliveryLagMs = 10L, msSinceProcessStart = 20L),
        AnalyticsEvent.NotificationTapped(msSinceShown = 5L, coldStart = true, msSinceProcessStart = 30L),
        AnalyticsEvent.AppOpen(source = "launcher"),
        AnalyticsEvent.NoticeCardVisible(logId = "1792473610441", visibleMs = 900L, hasImage = true, hasPdf = false, position = 3),
        AnalyticsEvent.ImageViewerClosed(logId = "1792473610441", durationMs = 400L, maxScale = 2.5f),
        AnalyticsEvent.PdfOpened(logId = "1792473610441", sizeBytes = 1L, wasCached = true),
        AnalyticsEvent.ActivationShown(hasCode = false),
        AnalyticsEvent.ActivationSubmitted,
        AnalyticsEvent.ActivationResult(result = "offline", offlineCause = "timeout"),
        AnalyticsEvent.CodeManagementCall(operation = "status", outcome = "active", durationMs = 120L, httpStatus = 200),
    )

    @Test
    fun `the variant list covers all fourteen events`() {
        assertEquals(14, allEvents.size)
        assertEquals(14, allEvents.map { it.name }.toSet().size)
    }

    @Test
    fun `a code management call reports operation outcome duration and status`() {
        val event = AnalyticsEvent.CodeManagementCall("activate", "refused", 340L, 409)
        assertEquals("code_management_call", event.name)
        assertEquals("activate", event.params["operation"])
        assertEquals("refused", event.params["outcome"])
        assertEquals(340L, event.params["duration_ms"])
        assertEquals(409L, event.params["http_status"])
        assertEquals(setOf("operation", "outcome", "duration_ms", "http_status"), event.params.keys)
    }

    @Test
    fun `a code management call with no response omits http_status`() {
        val event = AnalyticsEvent.CodeManagementCall("status", "inconclusive", 6_000L, null)
        assertFalse(event.params.containsKey("http_status"))
        assertEquals(setOf("operation", "outcome", "duration_ms"), event.params.keys)
    }

    @Test
    fun `each code management result reports its published name`() {
        assertEquals("active", CodeManagementResult.Active(PublicCode("A", null, false, null), null).analyticsName())
        assertEquals("revoked", CodeManagementResult.Revoked.analyticsName())
        assertEquals("refused", CodeManagementResult.Refused("x").analyticsName())
        assertEquals("inconclusive", CodeManagementResult.Inconclusive.analyticsName())
    }

    @Test
    fun `fetch policy decision carries the network facts`() {
        val event = AnalyticsEvent.FetchPolicyDecision(
            kind = "pdf", metered = true, roaming = false, sizeBytes = 5_600_000,
            probe = "reachable", fetched = false,
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
            kind = "img", metered = true, roaming = false, sizeBytes = null,
            probe = "reachable", fetched = false,
        )
        assertFalse(event.params.containsKey("size_bytes"))
        assertEquals(0L, event.params["size_known"])
    }

    @Test
    fun `every event and parameter name is within GA4 limits`() {
        // Over-long names are dropped silently at runtime and surface as a column that is always
        // null -- a failure that only shows up days later in BigQuery.
        //
        // The reserved prefixes are dropped just as silently; app_open is reserved too, which is
        // why AppOpen is named app_open_source.
        for (event in allEvents) {
            assertTrue("event name too long: ${event.name}", event.name.length <= 40)
            for (prefix in listOf("firebase_", "google_", "ga_")) {
                assertFalse("event name uses reserved prefix $prefix: ${event.name}", event.name.startsWith(prefix))
            }
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
        // Notice text could only ever arrive as a free-form String, so the strong guard is on
        // value types: only the named keys may hold a String, and only from a closed vocabulary.
        // The key-name check is the cheap second line of defence. Widening a vocabulary below is
        // a privacy-relevant change and should be made deliberately.
        //
        // The sets are the contract later call sites are measured against, not a record of what
        // this test constructs. Each comment names the producer to check before widening a set.
        val stringVocabulary = mapOf(
            // NoticeImageStore's existing vocabulary; FetchPolicyDecision, AttachmentFetchStart
            // and AttachmentFetchEnd.
            "kind" to setOf("img", "link", "pdf_thumb", "pdf"),
            // FetchPolicyDecision itself: `fetched` true -> "fetch", false -> "defer".
            "decision" to setOf("fetch", "defer"),
            // FetchPolicyDecision, set by AttachmentWorker.decide: the roaming short-circuit
            // attempts no probe (skipped); a payload-supplied size makes no request (sender);
            // otherwise the real probe's own Reachable / Unreachable.
            "probe" to setOf("skipped", "sender", "reachable", "unreachable"),
            // AppOpen, derived at launch: a logId extra present -> notification, else action
            // ACTION_VIEW -> link, else launcher.
            "source" to setOf("notification", "link", "launcher"),
            // AttachmentFetchEnd, at the end of the attachment worker's fetch.
            // CodeManagementCall, from CodeManagementResult.analyticsName().
            "outcome" to setOf("fetched", "failed", "active", "revoked", "refused", "inconclusive"),
            // CodeManagementCall: the two routes HttpCodeManagementApi posts to.
            "operation" to setOf("status", "activate"),
            // ActivationResult: the strings RedeemResult.analyticsName() reports. This pins the
            // strings; the exhaustive `when` in analyticsName() pins the coverage, failing to
            // compile when a variant is added. A new variant therefore needs an arm there first,
            // and then its string added here.
            "result" to setOf("success", "mistyped", "notaccepted", "samecode", "offline"),
            // ActivationResult: set just before each of the three RedeemResult.Offline returns
            // (no uid from anonymous sign-in, transport exception, 20-second timeout).
            "offline_cause" to setOf("no_uid", "transport", "timeout"),
        )

        // The one string whose value cannot be a closed set: log_id is the sender's own id for a
        // notice, and a new one is minted per send. It is pinned by shape instead -- the epoch
        // millis nextLogId_() produces, digits only. That is what keeps the guard meaningful: a
        // title or a body put here would not be digits, and this test would fail. Widening this
        // pattern is as privacy-relevant as widening a vocabulary above.
        val patternVocabulary = mapOf("log_id" to Regex("""^\d{10,20}$"""))

        val forbiddenInKey = listOf("title", "body", "text", "message")
        for (event in allEvents) {
            for ((key, value) in event.params) {
                for (word in forbiddenInKey) {
                    assertFalse("${event.name} has a parameter that may carry notice text: $key", key.contains(word))
                }
                when (value) {
                    is Long, is Double -> Unit
                    is String -> {
                        val pattern = patternVocabulary[key]
                        if (pattern != null) {
                            assertTrue(
                                "${event.name} '$key' = \"$value\" does not match its shape $pattern",
                                pattern.matches(value),
                            )
                        } else {
                            val allowed = stringVocabulary[key]
                            assertTrue(
                                "${event.name} carries a String in '$key' -- only ${stringVocabulary.keys + patternVocabulary.keys} may",
                                allowed != null,
                            )
                            assertTrue("${event.name} '$key' = \"$value\" is outside its vocabulary $allowed", value in allowed!!)
                        }
                    }
                    else -> throw AssertionError("${event.name} '$key' has unsupported type ${value::class.simpleName}")
                }
            }
        }
    }

    @Test
    fun `every nullable parameter is absent when null, never zero`() {
        // Each pair is a variant built with its nullable parameters null, and the keys that must
        // then be missing -- not present as 0, and not present as null.
        val cases = listOf(
            AnalyticsEvent.FetchPolicyDecision("pdf", true, false, null, "reachable", false) to listOf("size_bytes"),
            AnalyticsEvent.AttachmentFetchStart("pdf", null, null, null, 2L, 0) to listOf("size_bytes", "jitter_seconds", "enqueued_at_ms"),
            AnalyticsEvent.AttachmentFetchEnd("pdf", null, 250L, "fetched", null) to listOf("size_bytes", "http_status"),
            AnalyticsEvent.NoticeReceived(logId = "1792473610441", hasImage = true, hasPdf = false, isNew = true, deliveryLagMs = null, msSinceProcessStart = 20L) to listOf("delivery_lag_ms"),
            AnalyticsEvent.NotificationTapped(msSinceShown = null, coldStart = true, msSinceProcessStart = 30L) to listOf("ms_since_shown"),
            AnalyticsEvent.PdfOpened(logId = "1792473610441", sizeBytes = null, wasCached = true) to listOf("size_bytes"),
            // A notice the sender gave no logId reports no identifier at all. It must not fall
            // back to the Room rowid, which names a different notice on every handset.
            AnalyticsEvent.NoticeReceived(logId = null, hasImage = false, hasPdf = false, isNew = true, deliveryLagMs = 1L, msSinceProcessStart = 2L) to listOf("log_id", "notice_id"),
            AnalyticsEvent.NoticeCardVisible(logId = null, visibleMs = 900L, hasImage = false, hasPdf = false, position = 1) to listOf("log_id", "notice_id"),
            AnalyticsEvent.ImageViewerClosed(logId = null, durationMs = 400L, maxScale = 1f) to listOf("log_id", "notice_id"),
            AnalyticsEvent.ActivationResult(result = "offline", offlineCause = null) to listOf("offline_cause"),
            AnalyticsEvent.CodeManagementCall("status", "inconclusive", 10L, null) to listOf("http_status"),
        )
        for ((event, keys) in cases) {
            for (key in keys) {
                assertFalse("${event.name} records an unknown '$key' instead of omitting it", event.params.containsKey(key))
            }
        }
    }

    @Test
    fun `a real zero is a measurement and stays present`() {
        // The converse: absence means unknown, so a measured zero must not collapse into it.
        val size = AnalyticsEvent.PdfOpened(logId = "1792473610441", sizeBytes = 0L, wasCached = true)
        assertEquals(0L, size.params["size_bytes"])
        val lag = AnalyticsEvent.NoticeReceived(logId = "1792473610441", hasImage = false, hasPdf = false, isNew = true, deliveryLagMs = 0L, msSinceProcessStart = 0L)
        assertEquals(0L, lag.params["delivery_lag_ms"])
        val start = AnalyticsEvent.AttachmentFetchStart("pdf", null, 0L, 5L, 6L, 0)
        assertEquals(0L, start.params["jitter_seconds"]) // rolled zero, unlike a sweep's absence
        val status = AnalyticsEvent.AttachmentFetchEnd("pdf", 0L, 1L, "fetched", 0)
        assertEquals(0L, status.params["size_bytes"])
        assertEquals(0L, status.params["http_status"])
    }
}
