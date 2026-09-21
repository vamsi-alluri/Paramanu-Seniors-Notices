package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.AttachmentState
import org.paramanuseniorshealth.notices.fcm.FetchPolicy

class FetchPolicyTest {

    private val small = 40L * 1024
    private val circular = 5_872_345L

    @Test
    fun `roaming fetches nothing, at any size`() {
        assertFalse(FetchPolicy.shouldFetch(small, metered = true, roaming = true))
        assertFalse(FetchPolicy.shouldFetch(small, metered = false, roaming = true))
        assertFalse(FetchPolicy.shouldFetch(circular, metered = false, roaming = true))
    }

    @Test
    fun `metered fetches up to two megabytes`() {
        assertTrue(FetchPolicy.shouldFetch(small, metered = true, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(FetchPolicy.METERED_MAX_BYTES, metered = true, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(FetchPolicy.METERED_MAX_BYTES + 1, metered = true, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(circular, metered = true, roaming = false))
    }

    @Test
    fun `unmetered fetches a circular but not an absurd file`() {
        assertTrue(FetchPolicy.shouldFetch(circular, metered = false, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(FetchPolicy.UNMETERED_MAX_BYTES, metered = false, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(FetchPolicy.UNMETERED_MAX_BYTES + 1, metered = false, roaming = false))
    }

    /** No Content-Length. Treated as large, because assuming small is the expensive mistake. */
    @Test
    fun `unknown size is deferred on metered and allowed on unmetered`() {
        assertFalse(FetchPolicy.shouldFetch(null, metered = true, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(null, metered = false, roaming = false))
    }

    @Test
    fun `pending and deferred always retry, regardless of attempts`() {
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.PENDING, 0))
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.DEFERRED, 99))
    }

    @Test
    fun `failed retries up to the cap and then stops`() {
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.FAILED, FetchPolicy.MAX_ATTEMPTS - 1))
        assertFalse(FetchPolicy.shouldRetry(AttachmentState.FAILED, FetchPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `a fetched attachment never retries`() {
        assertFalse(FetchPolicy.shouldRetry(AttachmentState.FETCHED, 0))
    }

    /** A notice can carry several attachments, and the worst-but-recoverable answer must win. */
    @Test
    fun `deferral outranks failure`() {
        assertEquals(
            AttachmentState.DEFERRED,
            FetchPolicy.outcome(deferred = true, failed = true),
        )
        assertEquals(
            AttachmentState.FAILED,
            FetchPolicy.outcome(deferred = false, failed = true),
        )
        assertEquals(
            AttachmentState.FETCHED,
            FetchPolicy.outcome(deferred = false, failed = false),
        )
    }

    /**
     * A notice already DEFERRED is uncapped and retried by the standing wifi sweep forever. A tap
     * that then fails must not overwrite that with FAILED -- doing so, combined with an attempt
     * count already at the cap from earlier automatic failures, would permanently kill the wifi
     * sweep for a notice an unlucky tap touched. A non-tap (automatic) failure is not protected:
     * only a tap gets this exemption, since only a tap can fail without ever having deferred.
     */
    @Test
    fun `a failed tap does not downgrade an existing deferral`() {
        assertEquals(
            AttachmentState.DEFERRED,
            FetchPolicy.outcome(
                deferred = false,
                failed = true,
                previous = AttachmentState.DEFERRED,
                tapInitiated = true,
            ),
        )
        // Same inputs, but not from a tap: the automatic path is not exempted.
        assertEquals(
            AttachmentState.FAILED,
            FetchPolicy.outcome(
                deferred = false,
                failed = true,
                previous = AttachmentState.DEFERRED,
                tapInitiated = false,
            ),
        )
        // A tap failing on a notice that was never deferred still records FAILED normally.
        assertEquals(
            AttachmentState.FAILED,
            FetchPolicy.outcome(
                deferred = false,
                failed = true,
                previous = AttachmentState.FAILED,
                tapInitiated = true,
            ),
        )
    }

    @Test
    fun `only a failure burns an attempt`() {
        assertEquals(4, FetchPolicy.nextAttempts(AttachmentState.FAILED, 3))
        assertEquals(3, FetchPolicy.nextAttempts(AttachmentState.DEFERRED, 3))
        assertEquals(3, FetchPolicy.nextAttempts(AttachmentState.PENDING, 3))
    }

    /**
     * A tap that fails must not spend the automatic-retry budget: five unlucky taps must not end
     * the standing wifi sweep for a notice the user only ever failed to reach manually.
     */
    @Test
    fun `a tap-initiated failure does not burn an attempt`() {
        assertEquals(3, FetchPolicy.nextAttempts(AttachmentState.FAILED, 3, tapInitiated = true))
        assertEquals(4, FetchPolicy.nextAttempts(AttachmentState.FAILED, 3, tapInitiated = false))
        assertEquals(4, FetchPolicy.nextAttempts(AttachmentState.FAILED, 3))
    }

    /** Otherwise a notice that finally succeeded would sit one failure from the cap forever. */
    @Test
    fun `a success resets the attempt count`() {
        assertEquals(0, FetchPolicy.nextAttempts(AttachmentState.FETCHED, FetchPolicy.MAX_ATTEMPTS))
    }

    /**
     * A deferral must leave a notice retryable no matter how long it has been on mobile data, and
     * that only holds because the count it carries forward never reaches the cap by deferring.
     */
    @Test
    fun `repeated deferrals never reach the cap`() {
        var attempts = 0
        repeat(FetchPolicy.MAX_ATTEMPTS * 10) {
            attempts = FetchPolicy.nextAttempts(AttachmentState.DEFERRED, attempts)
            assertTrue(FetchPolicy.shouldRetry(AttachmentState.DEFERRED, attempts))
        }
        assertEquals(0, attempts)
    }

    /** Pruned is derived, not stored: it was delivered once, so it waits for a tap. */
    @Test
    fun `pruned is fetched with the file gone`() {
        assertTrue(FetchPolicy.isPruned(AttachmentState.FETCHED, fileExists = false))
        assertFalse(FetchPolicy.isPruned(AttachmentState.FETCHED, fileExists = true))
        assertFalse(FetchPolicy.isPruned(AttachmentState.DEFERRED, fileExists = false))
    }

    /** Rows predating the migration carry NULL, and must read as never-attempted. */
    @Test
    fun `an unknown or absent stored state reads as pending`() {
        assertEquals(AttachmentState.PENDING, AttachmentState.parse(null))
        assertEquals(AttachmentState.PENDING, AttachmentState.parse(""))
        assertEquals(AttachmentState.PENDING, AttachmentState.parse("NONSENSE"))
        assertEquals(AttachmentState.FETCHED, AttachmentState.parse("FETCHED"))
    }
}
