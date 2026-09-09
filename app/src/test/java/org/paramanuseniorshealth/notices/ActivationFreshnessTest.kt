package org.paramanuseniorshealth.notices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationFreshness

class ActivationFreshnessTest {

    @Test
    fun `never verified is stale`() {
        assertTrue(ActivationFreshness.isStale(lastVerifiedAt = 0L, now = 1_000L))
    }

    @Test
    fun `just verified is fresh`() {
        val now = 1_000_000_000L
        assertFalse(ActivationFreshness.isStale(lastVerifiedAt = now, now = now))
    }

    @Test
    fun `fresh up to the boundary`() {
        val now = 1_000_000_000L
        val edge = now - ActivationFreshness.STALE_AFTER_MS + 1
        assertFalse(ActivationFreshness.isStale(lastVerifiedAt = edge, now = now))
    }

    @Test
    fun `stale once the window has passed`() {
        val now = 1_000_000_000L
        assertTrue(ActivationFreshness.isStale(now - ActivationFreshness.STALE_AFTER_MS, now))
    }

    @Test
    fun `a clock that has gone backwards counts as stale`() {
        // Trusting a timestamp from the future would stop the device ever checking again, which is
        // the one failure this has to avoid: the check is what notices a restore.
        assertTrue(ActivationFreshness.isStale(lastVerifiedAt = 5_000L, now = 1_000L))
    }

    @Test
    fun `the window is a day`() {
        // Pinned deliberately. It widened from an hour once revocation started being pushed, and a
        // silent narrowing would put the connection load back on every broadcast.
        assertTrue(ActivationFreshness.STALE_AFTER_MS == 24L * 60 * 60 * 1000)
    }
}
