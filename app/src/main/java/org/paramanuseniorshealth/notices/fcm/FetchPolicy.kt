package org.paramanuseniorshealth.notices.fcm

/**
 * What has been tried for a notice's attachment, and therefore what should be tried next.
 *
 * Stored as text in `notices.attachmentState`. Four values rather than five: **PRUNED is not
 * stored**, it is derived as [AttachmentState.FETCHED] with the file gone. Writing it would mean
 * giving the pruning code a database handle -- it runs inside NoticeImageStore from the FCM path
 * and has none -- to record something a one-line check at read time already answers.
 *
 * The user is never shown which of these applies. They exist so the app knows what to retry, not
 * so it can explain itself.
 */
enum class AttachmentState {
    /** The row exists and the worker has not run yet. */
    PENDING,

    /** The policy said no: metered and too large, or roaming. Not a failure, and never capped. */
    DEFERRED,

    /** A fetch was attempted and threw or returned nothing. Capped at [FetchPolicy.MAX_ATTEMPTS]. */
    FAILED,

    /** The file reached the phone. */
    FETCHED;

    companion object {
        /**
         * Anything unrecognised reads as [PENDING].
         *
         * Rows written before the migration carry NULL, and that must mean "never attempted" --
         * so every notice already on a tester's phone whose thumbnail never arrived repairs itself
         * on the next app open rather than staying blank forever.
         */
        fun parse(raw: String?): AttachmentState =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

/**
 * Whether to spend a user's data on an attachment, and whether to try again.
 *
 * Deliberately free of Android imports. Metering and roaming arrive as booleans from
 * [NetworkStatus], which is a thin wrapper with no decisions in it, so the whole of the judgement
 * is here and testable on the JVM.
 *
 * The numbers come from a real measurement: a dispensary circular runs to roughly 192 pages and
 * 5.6MB, and that is typical rather than exceptional. At ~400 devices, fetching one on every phone
 * is over 2GB off the website. Nothing here is a round number chosen for looks.
 */
object FetchPolicy {

    /**
     * Clears every link logo, sender photo and PDF thumbnail; clears no circular.
     *
     * The gap between a ~40KB thumbnail and a 5.6MB circular is wide enough that the exact
     * threshold hardly matters -- what matters is that it sits inside the gap.
     */
    const val METERED_MAX_BYTES = 2L * 1024 * 1024

    /**
     * A sanity cap, not a policy. On wifi the user is not paying by the megabyte; this exists so a
     * mispublished 400MB file cannot swallow the cache and push out every other notice.
     */
    const val UNMETERED_MAX_BYTES = 25L * 1024 * 1024

    /** After this many failures only a tap retries, so a permanently 404 URL stops costing data. */
    const val MAX_ATTEMPTS = 5

    /**
     * [sizeBytes] is null when the server sent no `Content-Length`, and is then treated as large:
     * guessing small is the mistake that costs a user money, guessing large costs them one tap.
     *
     * A user's tap does not come through here. Tapping is consent, and bypasses every tier.
     */
    fun shouldFetch(sizeBytes: Long?, metered: Boolean, roaming: Boolean): Boolean = when {
        // The one case where a picture nobody asked for can appear on a bill.
        roaming -> false
        metered -> sizeBytes != null && sizeBytes <= METERED_MAX_BYTES
        else -> sizeBytes == null || sizeBytes <= UNMETERED_MAX_BYTES
    }

    /**
     * [AttachmentState.DEFERRED] is uncapped on purpose. Deferring is the policy working, not a
     * failure, and a phone that sits on mobile data for a month must still fetch the moment it
     * reaches wifi.
     */
    fun shouldRetry(state: AttachmentState, attempts: Int): Boolean = when (state) {
        AttachmentState.PENDING, AttachmentState.DEFERRED -> true
        AttachmentState.FAILED -> attempts < MAX_ATTEMPTS
        AttachmentState.FETCHED -> false
    }

    /**
     * What one pass over a notice's attachments amounts to, given that a notice may carry several.
     *
     * **Deferral outranks failure.** A notice with a photo that 404s and a circular the policy
     * declined is DEFERRED, not FAILED: the circular is the part still worth waiting for wifi for,
     * and recording FAILED would both burn an attempt and -- after [MAX_ATTEMPTS] -- stop the
     * sweeps retrying a fetch that was never actually attempted.
     */
    fun outcome(deferred: Boolean, failed: Boolean): AttachmentState = when {
        deferred -> AttachmentState.DEFERRED
        failed -> AttachmentState.FAILED
        else -> AttachmentState.FETCHED
    }

    /**
     * The attempt count to store alongside [state], given [current].
     *
     * Only a failure increments. A deferral carries the count forward untouched -- deferring is the
     * policy working, and `updateAttachment` overwrites unconditionally, so passing anything else
     * would silently rewrite history. A success resets to zero, so a notice that failed four times
     * on a bad connection and then succeeded is not left one failure from the cap.
     */
    fun nextAttempts(state: AttachmentState, current: Int): Int = when (state) {
        AttachmentState.FAILED -> current + 1
        AttachmentState.FETCHED -> 0
        AttachmentState.PENDING, AttachmentState.DEFERRED -> current
    }

    /** Delivered once and since pruned. Shows the glyph, but never re-downloads by itself. */
    fun isPruned(state: AttachmentState, fileExists: Boolean): Boolean =
        state == AttachmentState.FETCHED && !fileExists
}
