package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.ControlTopics

/** The names must match what functions/src/control.ts sends to, character for character. */
class ControlTopicsTest {

    @Test
    fun `the device topic keeps the UID's case`() {
        assertEquals("device-v1-AbCdEf0123456789xyzUVW012345", ControlTopics.device("AbCdEf0123456789xyzUVW012345"))
    }

    @Test
    fun `the banner topic names the dispensary`() {
        assertEquals("banner-v1-barc-vashi", ControlTopics.banner("barc-vashi"))
    }

    @Test
    fun `a name FCM would refuse is not subscribed at all`() {
        assertNull(ControlTopics.device(""))
        assertNull(ControlTopics.device("a/b"))
        assertNull(ControlTopics.banner("has space"))
    }
}
