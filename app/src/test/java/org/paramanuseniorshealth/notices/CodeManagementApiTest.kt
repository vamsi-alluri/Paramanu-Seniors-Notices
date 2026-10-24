package org.paramanuseniorshealth.notices

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.paramanuseniorshealth.notices.activation.HttpCodeManagementApi
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.CodeManagementConfig
import org.paramanuseniorshealth.notices.activation.CodeManagementResponse
import org.paramanuseniorshealth.notices.activation.CodeManagementResult
import org.paramanuseniorshealth.notices.activation.PublicCode
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsSink

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

    @Test
    fun `a cleartext or schemeless base url is rejected because it would carry a token`() {
        assertFalse(CodeManagementConfig.isUsable("http://example.test/code"))
        assertFalse(CodeManagementConfig.isUsable("example.test/code"))
        assertFalse(CodeManagementConfig.isUsable("https://"))
    }

    @Test
    fun `200 active that is also revoked is inconclusive, resolved in neither direction`() {
        val body = mapOf(
            "status" to "active",
            "code" to mapOf("code" to "ABC123", "dispensary" to null, "revoked" to true, "activatedAt" to 1L),
        )
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, body))
    }

    @Test
    fun `200 revoked with junk fields alongside is still revoked`() {
        val body = mapOf("status" to "revoked", "code" to listOf(1, 2), "x" to null)
        assertEquals(CodeManagementResult.Revoked, CodeManagementResponse.parse(200, body))
    }

    @Test
    fun `active with a string revoked flag is inconclusive`() {
        val body = mapOf("status" to "active", "code" to mapOf("code" to "A", "revoked" to "false"))
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, body))
    }

    @Test
    fun `active with a list for code is inconclusive`() {
        val body = mapOf("status" to "active", "code" to listOf("A"))
        assertEquals(CodeManagementResult.Inconclusive, CodeManagementResponse.parse(200, body))
    }

    @Test
    fun `409 with a blank or non-string reason is inconclusive`() {
        for (reason in listOf<Any?>("", "  ", 7, null, listOf("x"))) {
            assertEquals(
                CodeManagementResult.Inconclusive,
                CodeManagementResponse.parse(409, mapOf("status" to "refused", "reason" to reason)),
            )
        }
    }

    @Test
    fun `409 whose status is not refused is inconclusive`() {
        assertEquals(
            CodeManagementResult.Inconclusive,
            CodeManagementResponse.parse(409, mapOf("status" to "revoked", "reason" to "revoked")),
        )
    }

    @Test
    fun `403 carrying refused is inconclusive`() {
        assertEquals(
            CodeManagementResult.Inconclusive,
            CodeManagementResponse.parse(403, mapOf("status" to "refused", "reason" to "revoked")),
        )
    }

    // ---- HttpCodeManagementApi: only the paths that never reach the socket.

    private val https = "https://example.test/code"

    private class FakeSink : AnalyticsSink {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        override fun log(name: String, params: Map<String, Any>) { events += name to params }
        override fun setCollectionEnabled(enabled: Boolean) = Unit
    }

    private val sink = FakeSink()
    private val analytics = Analytics(sink).also { it.setEnabled(true) }

    private fun api(
        url: String = https,
        timeoutMs: Long = 6_000,
        tokenProvider: suspend () -> String?,
    ) = HttpCodeManagementApi(url, analytics, tokenProvider, timeoutMs)

    /** The single event a call must have produced, with the keys it must and must not carry. */
    private fun assertLogged(operation: String, outcome: String) {
        assertEquals(1, sink.events.size)
        val (name, params) = sink.events.single()
        assertEquals("code_management_call", name)
        assertEquals(operation, params["operation"])
        assertEquals(outcome, params["outcome"])
        assertTrue(params["duration_ms"] is Long)
        assertTrue((params["duration_ms"] as Long) >= 0L)
        assertFalse(params.containsKey("http_status"))
        assertEquals(setOf("operation", "outcome", "duration_ms"), params.keys)
    }

    @Test
    fun `a blank or cleartext base url never asks for a token`() = runBlocking {
        var asked = false
        for (url in listOf("", "   ", "http://example.test")) {
            val api = api(url) { asked = true; "t" }
            assertEquals(CodeManagementResult.Inconclusive, api.status("A"))
            assertEquals(CodeManagementResult.Inconclusive, api.activate("A"))
        }
        assertFalse(asked)
    }

    @Test
    fun `a blank base url still reports the call`() = runBlocking {
        api("") { "t" }.status("A")
        assertLogged("status", "inconclusive")
    }

    @Test
    fun `a cleartext base url still reports the call`() = runBlocking {
        api("http://example.test") { "t" }.activate("A")
        assertLogged("activate", "inconclusive")
    }

    @Test
    fun `no signed-in user is inconclusive and reported`() = runBlocking {
        assertEquals(CodeManagementResult.Inconclusive, api { null }.status("A"))
        assertLogged("status", "inconclusive")
    }

    @Test
    fun `a token provider that throws is inconclusive, does not throw, and is reported`() = runBlocking {
        val api = api { throw IllegalStateException("boom") }
        assertEquals(CodeManagementResult.Inconclusive, api.status("A"))
        assertEquals(CodeManagementResult.Inconclusive, api.activate("A"))
        assertEquals(2, sink.events.size)
        assertEquals(listOf("status", "activate"), sink.events.map { it.second["operation"] })
        assertTrue(sink.events.all { !it.second.containsKey("http_status") })
    }

    @Test
    fun `a token that never arrives is inconclusive after the timeout and reported`() = runBlocking {
        val api = api(timeoutMs = 50) { awaitCancellation() }
        assertEquals(CodeManagementResult.Inconclusive, api.status("A"))
        assertLogged("status", "inconclusive")
        // The duration is what the caller waited, so it reflects the timeout.
        assertTrue((sink.events.single().second["duration_ms"] as Long) >= 40L)
    }

    @Test
    fun `nothing sensitive is in the reported event`() = runBlocking {
        api { "SECRET-TOKEN" }.status("SECRET-CODE")
        val text = sink.events.single().second.toString()
        assertFalse(text.contains("SECRET"))
    }

    @Test
    fun `cancellation from the token provider propagates`() {
        val api = api { throw CancellationException("stop") }
        try {
            runBlocking { api.status("A") }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
