package org.paramanuseniorshealth.notices

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.activation.ActivationState
import org.paramanuseniorshealth.notices.activation.CodeManagementResult
import org.paramanuseniorshealth.notices.activation.DispensaryConfig
import org.paramanuseniorshealth.notices.activation.PublicCode
import org.paramanuseniorshealth.notices.activation.VerifyPolicy

class VerifyPolicyTest {

    private val good = requireNotNull(ActivationCode.withCheckCharacter("ABCD123"))

    private fun check(result: CodeManagementResult): Pair<VerifyPolicy.Verdict, RecordingCodeManagementApi> {
        val api = RecordingCodeManagementApi(statusReturns = result)
        return runBlocking { VerifyPolicy.check(api, good) } to api
    }

    @Test
    fun `active is Active, persisted, and carries the dispensary id and node`() {
        val node = mapOf("name" to "Main", "info" to mapOf("heading" to "h"))
        val (v, api) = check(
            CodeManagementResult.Active(PublicCode(good, "main", false, 1L), node)
        )
        assertEquals(ActivationState.Active, v.state)
        assertTrue(v.persist)
        assertEquals("main", v.dispensaryId)
        assertSame(node, v.dispensaryNode)
        assertEquals(listOf(good), api.statusCalls)
    }

    @Test
    fun `revoked is Revoked and persisted`() {
        val (v, _) = check(CodeManagementResult.Revoked)
        assertEquals(ActivationState.Revoked, v.state)
        assertTrue(v.persist)
        assertNull(v.dispensaryNode)
    }

    @Test
    fun `inconclusive is Unknown and never Revoked, and is not persisted`() {
        val (v, _) = check(CodeManagementResult.Inconclusive)
        assertEquals(ActivationState.Unknown, v.state)
        assertFalse(v.persist)
        assertNull(v.dispensaryId)
        assertNull(v.dispensaryNode)
    }

    @Test
    fun `a refusal on status is a contract break and is inconclusive, not revoked`() {
        val (v, _) = check(CodeManagementResult.Refused("revoked"))
        assertEquals(ActivationState.Unknown, v.state)
        assertFalse(v.persist)
    }

    @Test
    fun `only the two definitive states are ever persisted`() {
        val results = listOf(
            CodeManagementResult.Active(PublicCode(good, null, false, null), null),
            CodeManagementResult.Revoked,
            CodeManagementResult.Refused("x"),
            CodeManagementResult.Inconclusive,
        )
        for (r in results) {
            val (v, _) = check(r)
            assertEquals(
                v.state == ActivationState.Active || v.state == ActivationState.Revoked,
                v.persist,
            )
        }
    }

    @Test
    fun `the banner is taken from the info child of the dispensary node`() {
        val info = mapOf("heading" to "h", "lines" to "l", "html" to "")
        assertSame(info, DispensaryConfig.infoOf(mapOf("name" to "Main", "info" to info)))
    }

    @Test
    fun `no info child or no node gives no banner`() {
        assertNull(DispensaryConfig.infoOf(mapOf("name" to "Main")))
        assertNull(DispensaryConfig.infoOf(null))
        assertNull(DispensaryConfig.infoOf(mapOf("info" to "not a map")))
    }
}
