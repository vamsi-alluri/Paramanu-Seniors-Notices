package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.CodeManagementConfig
import org.paramanuseniorshealth.notices.activation.CodeManagementResponse
import org.paramanuseniorshealth.notices.activation.CodeManagementResult
import org.paramanuseniorshealth.notices.activation.PublicCode

class CodeManagementApiTest {

    private val dispensary = mapOf("label" to "Paramanu", "topic" to "notices")

    private fun active(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "status" to "active",
        "code" to mapOf(
            "code" to "ABC123",
            "dispensary" to "main",
            "revoked" to false,
            "activatedAt" to 1_700_000_000_000L,
        ),
    ) + extra

    @Test
    fun `200 active carries the code and the dispensary map`() {
        val r = CodeManagementResponse.parse(200, active(mapOf("dispensary" to dispensary)))
        assertEquals(
            CodeManagementResult.Active(PublicCode("ABC123", "main", false, 1_700_000_000_000L), dispensary),
            r,
        )
    }

    @Test
    fun `200 active without a dispensary node has a null dispensary`() {
        val r = CodeManagementResponse.parse(200, active()) as CodeManagementResult.Active
        assertEquals(null, r.dispensary)
    }

    @Test
    fun `200 active with a null activatedAt and dispensary parses`() {
        val body = mapOf(
            "status" to "active",
            "code" to mapOf("code" to "ABC123", "dispensary" to null, "revoked" to false, "activatedAt" to null),
        )
        val r = CodeManagementResponse.parse(200, body) as CodeManagementResult.Active
        assertEquals(PublicCode("ABC123", null, false, null), r.code)
    }

    @Test
    fun `200 active with no code object is inconclusive`() {
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, mapOf("status" to "active")))
    }

    @Test
    fun `200 revoked is revoked`() {
        assertEquals(CodeManagementResult.Revoked, CodeManagementResponse.parse(200, mapOf("status" to "revoked")))
    }

    @Test
    fun `409 with a reason is refused with that reason`() {
        val r = CodeManagementResponse.parse(409, mapOf("status" to "refused", "reason" to "already_claimed"))
        assertEquals(CodeManagementResult.Refused("already_claimed"), r)
    }

    @Test
    fun `409 without a reason is inconclusive`() {
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(409, mapOf("status" to "refused")))
    }

    @Test
    fun `401 is inconclusive and never revoked`() {
        val r = CodeManagementResponse.parse(401, mapOf("error" to "unauthenticated"))
        assertEquals(CodeManagementResult.Inconclusive, r)
    }

    @Test
    fun `a 401 or 403 carrying a revoked status is still inconclusive`() {
        for (code in listOf(401, 403)) {
            assertEquals(
                CodeManagementResult.Inconclusive,
                CodeManagementResponse.parse(code, mapOf("status" to "revoked")),
            )
        }
    }

    @Test
    fun `500 and other statuses are inconclusive`() {
        for (code in listOf(400, 404, 500, 502, 503, 0, -1)) {
            assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(code, mapOf("error" to "x")))
        }
    }

    @Test
    fun `a null body is inconclusive`() {
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, null))
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(409, null))
    }

    @Test
    fun `200 with an unrecognised status is inconclusive`() {
        for (s in listOf<Any?>("banana", "", "ACTIVE", null, 5)) {
            assertEquals(
                CodeManagementResult.Inconclusive,
                CodeManagementResponse.parse(200, mapOf("status" to s)),
            )
        }
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, emptyMap()))
    }

    @Test
    fun `the base url defaults to empty so nothing is called until it is configured`() {
        assertEquals("code_management_base_url", CodeManagementConfig.KEY)
        assertEquals("", CodeManagementConfig.DEFAULT)
        assertTrue(CodeManagementConfig.isUsable(CodeManagementConfig.DEFAULT).not())
        assertTrue(CodeManagementConfig.isUsable("   ").not())
        assertTrue(CodeManagementConfig.isUsable("https://example.test/code"))
    }
}
