package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.ui.LevelFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelFilterTest {

    @Test
    fun `maps each level name to its bucket, ignoring case and padding`() {
        assertEquals(LevelFilter.ERROR, LevelFilter.of("error"))
        assertEquals(LevelFilter.WARNING, LevelFilter.of("WARNING"))
        assertEquals(LevelFilter.SUCCESS, LevelFilter.of(" success "))
        assertEquals(LevelFilter.INFO, LevelFilter.of("Info"))
    }

    /** The push contract makes level optional, and senders predating it send none at all. */
    @Test
    fun `treats missing and unrecognised levels as other`() {
        assertEquals(LevelFilter.OTHER, LevelFilter.of(null))
        assertEquals(LevelFilter.OTHER, LevelFilter.of(""))
        assertEquals(LevelFilter.OTHER, LevelFilter.of("critical"))
    }

    @Test
    fun `every bucket is reachable, so no notification can be unfilterable`() {
        val reachable = listOf("error", "warning", "success", "info", null)
            .map { LevelFilter.of(it) }
            .toSet()
        assertEquals(LevelFilter.ALL, reachable)
    }

    @Test
    fun `joins label lists as prose`() {
        assertEquals("", LevelFilter.joinLabels(emptyList(), "and"))
        assertEquals("Error", LevelFilter.joinLabels(listOf("Error"), "and"))
        assertEquals("Error and Warning", LevelFilter.joinLabels(listOf("Error", "Warning"), "and"))
        assertEquals(
            "Error, Warning and Success",
            LevelFilter.joinLabels(listOf("Error", "Warning", "Success"), "and"),
        )
    }
}
