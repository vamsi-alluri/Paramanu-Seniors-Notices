package org.paramanuseniorshealth.notices.analytics

/**
 * Every measurement the app can report, as a closed set.
 *
 * Deliberately free of Android and Firebase imports, like FetchPolicy: the whole of the event
 * taxonomy is testable on the JVM, and a parameter-name typo is a compile error here rather than
 * a BigQuery column that is silently always null.
 *
 * Booleans travel as 1/0 longs because GA4 has no boolean parameter type, and a null is recorded
 * by *omitting* the key -- an unknown size is not a zero-byte file.
 */
sealed interface AnalyticsEvent {

    val name: String
    val params: Map<String, Any>

    data class FetchPolicyDecision(
        val kind: String,
        val metered: Boolean,
        val roaming: Boolean,
        val sizeBytes: Long?,
        val probe: String,
        val fetched: Boolean,
    ) : AnalyticsEvent {
        override val name = "fetch_policy_decision"
        override val params = buildMap {
            put("kind", kind)
            // Without this, a null size_bytes cannot tell a server that withheld Content-Length
            // from a probe that never got an answer -- and the latter would be counted as a
            // policy deferral, so the deferral ratio would rise with network flakiness.
            put("probe", probe)
            put("metered", metered.asParam())
            put("roaming", roaming.asParam())
            put("decision", if (fetched) "fetch" else "defer")
            // Whether a size was available at all, nothing more: it does not say where the size
            // came from. `probe` does that (sender-supplied versus a real HEAD).
            put("size_known", (sizeBytes != null).asParam())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class NetworkStateOnOpen(val metered: Boolean, val roaming: Boolean) : AnalyticsEvent {
        override val name = "network_state_on_open"
        override val params = mapOf<String, Any>(
            "metered" to metered.asParam(),
            "roaming" to roaming.asParam(),
        )
    }

    data class AttachmentFetchStart(
        val kind: String,
        val sizeBytes: Long?,
        val jitterSeconds: Long,
        val enqueuedAtMs: Long,
        val startedAtMs: Long,
        val attempt: Int,
    ) : AnalyticsEvent {
        override val name = "attachment_fetch_start"
        override val params = buildMap {
            put("kind", kind)
            put("jitter_seconds", jitterSeconds)
            put("enqueued_at_ms", enqueuedAtMs)
            put("started_at_ms", startedAtMs)
            put("attempt", attempt.toLong())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class AttachmentFetchEnd(
        val kind: String,
        val sizeBytes: Long?,
        val durationMs: Long,
        val outcome: String,
        val httpStatus: Int?,
    ) : AnalyticsEvent {
        override val name = "attachment_fetch_end"
        override val params = buildMap {
            put("kind", kind)
            put("duration_ms", durationMs)
            put("outcome", outcome)
            sizeBytes?.let { put("size_bytes", it) }
            httpStatus?.let { put("http_status", it.toLong()) }
        }
    }

    data class NoticeReceived(
        val hasImage: Boolean,
        val hasPdf: Boolean,
        val isNew: Boolean,
        val deliveryLagMs: Long?,
        val msSinceProcessStart: Long,
    ) : AnalyticsEvent {
        override val name = "notice_received"
        override val params = buildMap {
            put("has_image", hasImage.asParam())
            put("has_pdf", hasPdf.asParam())
            put("is_new", isNew.asParam())
            put("ms_since_process_start", msSinceProcessStart)
            deliveryLagMs?.let { put("delivery_lag_ms", it) }
        }
    }

    data class NotificationTapped(val msSinceShown: Long?, val coldStart: Boolean) : AnalyticsEvent {
        override val name = "notification_tapped"
        override val params = buildMap {
            put("cold_start", coldStart.asParam())
            msSinceShown?.let { put("ms_since_shown", it) }
        }
    }

    data class AppOpen(val source: String) : AnalyticsEvent {
        override val name = "app_open_source"
        override val params = mapOf<String, Any>("source" to source)
    }

    data class NoticeCardVisible(
        val noticeId: Long,
        val visibleMs: Long,
        val hasImage: Boolean,
        val hasPdf: Boolean,
        val position: Int,
    ) : AnalyticsEvent {
        override val name = "notice_card_visible"
        override val params = mapOf<String, Any>(
            "notice_id" to noticeId,
            "visible_ms" to visibleMs,
            "has_image" to hasImage.asParam(),
            "has_pdf" to hasPdf.asParam(),
            "position" to position.toLong(),
        )
    }

    data class ImageViewerClosed(
        val noticeId: Long,
        val durationMs: Long,
        val maxScale: Float,
    ) : AnalyticsEvent {
        override val name = "image_viewer_closed"
        override val params = mapOf<String, Any>(
            "notice_id" to noticeId,
            "duration_ms" to durationMs,
            "max_scale" to maxScale.toDouble(),
        )
    }

    data class PdfOpened(val noticeId: Long, val sizeBytes: Long?, val wasCached: Boolean) : AnalyticsEvent {
        override val name = "pdf_opened"
        override val params = buildMap {
            put("notice_id", noticeId)
            put("was_cached", wasCached.asParam())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class ActivationShown(val hasCode: Boolean) : AnalyticsEvent {
        override val name = "activation_shown"
        override val params = mapOf<String, Any>("has_code" to hasCode.asParam())
    }

    data object ActivationSubmitted : AnalyticsEvent {
        override val name = "activation_submitted"
        override val params = emptyMap<String, Any>()
    }

    /**
     * [offlineCause] exists because three unrelated failures all return RedeemResult.Offline --
     * no uid from anonymous sign-in, a transport exception, and the 20-second timeout elapsing --
     * and telling them apart is most of the value of the funnel.
     */
    data class ActivationResult(val result: String, val offlineCause: String?) : AnalyticsEvent {
        override val name = "activation_result"
        override val params = buildMap {
            put("result", result)
            offlineCause?.let { put("offline_cause", it) }
        }
    }
}

/** GA4 has no boolean parameter type, and a string "true" cannot be aggregated arithmetically. */
private fun Boolean.asParam(): Long = if (this) 1L else 0L
