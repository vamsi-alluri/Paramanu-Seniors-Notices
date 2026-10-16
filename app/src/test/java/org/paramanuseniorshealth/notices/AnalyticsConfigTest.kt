package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig

class AnalyticsConfigTest {

    @Test
    fun `the path is the agreed one`() {
        // Must match the rule added to database.rules.json in this task.
        assertEquals("config/analytics/enabled", AnalyticsConfig.PATH)
    }

    @Test
    fun `a value that is not a boolean reads as off`() {
        // The node is hand-edited. A typo, a string, or a deleted node must all mean "do not
        // collect" -- never "collect".
        assertFalse(AnalyticsConfig.parse(null))
        assertFalse(AnalyticsConfig.parse("true"))
        assertFalse(AnalyticsConfig.parse(1L))
        assertFalse(AnalyticsConfig.parse(false))
        assertTrue(AnalyticsConfig.parse(true))
    }
}
