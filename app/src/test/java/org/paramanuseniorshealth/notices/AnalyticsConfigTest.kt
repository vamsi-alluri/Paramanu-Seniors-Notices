package org.paramanuseniorshealth.notices

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig
import kotlin.coroutines.cancellation.CancellationException

class AnalyticsConfigTest {

    @Test
    fun `a phone that has never fetched reads off`() {
        // The whole of "collection defaults to off" on a first launch rests on this. An unfetched
        // Remote Config returns the false default, which is the promise the privacy policy makes.
        //
        // The constant is asserted directly, and not only through the switch: it is the default
        // handed to setDefaultsAsync, so it IS the promise, and a test that only read it back
        // through a double would pass just as happily with the promise reversed.
        assertFalse(
            "collection defaults to off; the published privacy policy depends on it",
            AnalyticsConfig.DEFAULT,
        )
        assertFalse(AnalyticsConfig(RecordingSwitch()).cached())
    }

    @Test
    fun `refresh returns and applies the fetched value`() = runBlocking {
        val switch = RecordingSwitch(initiallyEnabled = false, fetchReturns = true)
        val config = AnalyticsConfig(switch)

        assertTrue(config.refresh())
        assertTrue(config.cached())
        assertEquals(1, switch.refreshCalls)
    }

    @Test
    fun `a failed refresh leaves the value in force standing`() = runBlocking {
        // A phone that is offline, or whose fetch is throttled, keeps behaving as it last knew --
        // it does not fall open, and it does not fall closed on someone who opted in.
        val switch = RecordingSwitch(initiallyEnabled = true, failWith = IllegalStateException("no network"))
        val config = AnalyticsConfig(switch)

        assertTrue(config.refresh())
        assertTrue(config.cached())
        assertEquals(1, switch.refreshCalls)
    }

    @Test
    fun `cancellation propagates rather than reading as a failed refresh`() {
        // A caller that gave up on this should stop, not be handed the value in force as though
        // the fetch had been tried and failed.
        val config = AnalyticsConfig(RecordingSwitch(failWith = CancellationException("gave up")))

        try {
            runBlocking { config.refresh() }
            throw AssertionError("expected the cancellation to propagate")
        } catch (expected: CancellationException) {
            assertEquals("gave up", expected.message)
        }
    }
}
