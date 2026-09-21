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
