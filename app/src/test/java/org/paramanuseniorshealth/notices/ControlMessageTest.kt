package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.ControlMessage

class ControlMessageTest {

    // ---- revoke

    @Test
    fun `reads a revoke with its stamp`() {
        assertEquals(
            ControlMessage.Revoke("A1B2C3D4", 1_789_000_000_000L),
            ControlMessage.parse(mapOf("type" to "revoke", "code" to "A1B2C3D4", "at" to "1789000000000")),
        )
    }

    @Test
    fun `the code is normalised the way a typed code is`() {
        // The sender should never send a dashed or lowercase code, but a mismatch here would fail
        // to reach the right device while looking like nothing happened at all.
        assertEquals(
            ControlMessage.Revoke("A1B2C3D4", 5L),
            ControlMessage.parse(mapOf("type" to "revoke", "code" to "a1b2-c3d4", "at" to "5")),
        )
    }

    @Test
    fun `a revoke or resume without a usable code is ignored`() {
        assertNull(ControlMessage.parse(mapOf("type" to "revoke", "at" to "5")))
        assertNull(ControlMessage.parse(mapOf("type" to "revoke", "code" to "   ", "at" to "5")))
        assertNull(ControlMessage.parse(mapOf("type" to "resume", "at" to "5")))
    }

    // ---- resume

    @Test
    fun `reads a resume`() {
        assertEquals(
            ControlMessage.Resume("A1B2C3D4", 7L),
            ControlMessage.parse(mapOf("type" to "resume", "code" to "A1B2C3D4", "at" to "7")),
        )
    }

    // ---- banner

    @Test
    fun `reads a banner`() {
        assertEquals(
            ControlMessage.Banner("barc-vashi", "<b>Closed Friday</b>", 9L),
            ControlMessage.parse(
                mapOf("type" to "banner", "dispensary" to "barc-vashi", "html" to "<b>Closed Friday</b>", "at" to "9")
            ),
        )
    }

    @Test
    fun `an empty banner is a removal, not a malformed message`() {
        assertEquals(
            ControlMessage.Banner("barc-vashi", "", 9L),
            ControlMessage.parse(mapOf("type" to "banner", "dispensary" to "barc-vashi", "html" to "", "at" to "9")),
        )
    }

    @Test
    fun `a banner with no html key at all is ignored`() {
        // Absent is not the same as empty: an absent key means the sender built the envelope wrong,
        // and wiping the header on the strength of that would be the worse mistake.
        assertNull(ControlMessage.parse(mapOf("type" to "banner", "dispensary" to "barc-vashi", "at" to "9")))
    }

    @Test
    fun `a banner that does not say whose it is is ignored`() {
        // Every dispensary's banner shares the control topic; one with no owner could land on anyone.
        assertNull(ControlMessage.parse(mapOf("type" to "banner", "html" to "x", "at" to "9")))
        assertNull(ControlMessage.parse(mapOf("type" to "banner", "dispensary" to " ", "html" to "x", "at" to "9")))
    }

    // ---- the stamp

    @Test
    fun `a message without a stamp is ignored`() {
        // Nothing to order it against, so it cannot safely be applied.
        assertNull(ControlMessage.parse(mapOf("type" to "revoke", "code" to "A1B2C3D4")))
        assertNull(ControlMessage.parse(mapOf("type" to "banner", "dispensary" to "barc-vashi", "html" to "x")))
    }

    @Test
    fun `an unreadable stamp is treated as no stamp`() {
        assertNull(
            ControlMessage.parse(mapOf("type" to "banner", "dispensary" to "barc-vashi", "html" to "x", "at" to "soon"))
        )
    }

    // ---- not control messages

    @Test
    fun `an ordinary notice is not a control message`() {
        assertNull(ControlMessage.parse(mapOf("title" to "Closed Friday", "logId" to "123")))
    }

    @Test
    fun `an empty payload is not a control message`() {
        assertNull(ControlMessage.parse(emptyMap()))
    }

    @Test
    fun `an unknown type is ignored`() {
        // A later sender may add types this build does not know. Ignoring them is what lets that
        // happen without breaking phones that have not updated.
        assertNull(ControlMessage.parse(mapOf("type" to "ping", "code" to "A1B2C3D4", "at" to "5")))
    }

    // ---- ordering

    @Test
    fun `a newer stamp is applied`() {
        assertTrue(ControlMessage.isNewer(at = 200L, lastApplied = 100L))
    }

    @Test
    fun `an older or repeated stamp is not`() {
        // FCM does not promise order: a Disable then Enable a few seconds apart can arrive the other
        // way round, and applying the late Disable would leave the phone off.
        assertFalse(ControlMessage.isNewer(at = 100L, lastApplied = 200L))
        assertFalse(ControlMessage.isNewer(at = 200L, lastApplied = 200L))
    }
}
