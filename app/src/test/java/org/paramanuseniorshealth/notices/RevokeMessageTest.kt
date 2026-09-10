package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.RevokeMessage

class RevokeMessageTest {

    @Test
    fun `reads the code from a revoke envelope`() {
        assertEquals("A1B2C3D4", RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "A1B2C3D4")))
    }

    @Test
    fun `an ordinary notice is not a revoke`() {
        assertNull(RevokeMessage.codeIn(mapOf("title" to "Closed Friday", "logId" to "123")))
    }

    @Test
    fun `an empty payload is not a revoke`() {
        assertNull(RevokeMessage.codeIn(emptyMap()))
    }

    @Test
    fun `a revoke without a usable code is ignored`() {
        assertNull(RevokeMessage.codeIn(mapOf("type" to "revoke")))
        assertNull(RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "   ")))
    }

    @Test
    fun `the code is normalised the way a typed code is`() {
        // The sender should never send a dashed or lowercase code, but a mismatch here would fail
        // to revoke the right device while looking like nothing happened at all.
        assertEquals("A1B2C3D4", RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "a1b2-c3d4")))
    }

    @Test
    fun `another message type is not a revoke`() {
        assertNull(RevokeMessage.codeIn(mapOf("type" to "ping", "code" to "A1B2C3D4")))
    }
}
