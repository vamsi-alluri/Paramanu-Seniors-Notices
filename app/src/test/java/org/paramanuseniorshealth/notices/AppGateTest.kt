package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Test
import org.paramanuseniorshealth.notices.update.AppGate

class AppGateTest {

    @Test
    fun `the defaults leave every build open`() {
        // A phone that has never fetched must never be locked out by a missing value.
        assertEquals(AppGate.Open, AppGate.decide(1L, AppGate.DEFAULT_MIN_VERSION, AppGate.DEFAULT_KILLED))
    }

    @Test
    fun `a build below the floor is unsupported`() {
        assertEquals(AppGate.Unsupported, AppGate.decide(installedVersion = 19L, minVersion = 20L, killed = false))
    }

    @Test
    fun `a build at the floor is open`() {
        assertEquals(AppGate.Open, AppGate.decide(installedVersion = 20L, minVersion = 20L, killed = false))
    }

    @Test
    fun `killed wins over every version`() {
        assertEquals(AppGate.Killed, AppGate.decide(installedVersion = 99L, minVersion = 0L, killed = true))
        assertEquals(AppGate.Killed, AppGate.decide(installedVersion = 1L, minVersion = 20L, killed = true))
    }
}
