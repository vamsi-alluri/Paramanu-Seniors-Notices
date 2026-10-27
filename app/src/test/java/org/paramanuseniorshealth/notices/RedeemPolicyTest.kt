package org.paramanuseniorshealth.notices

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.activation.ClaimOutcome
import org.paramanuseniorshealth.notices.activation.CodeManagementConfig
import org.paramanuseniorshealth.notices.activation.CodeManagementResult
import org.paramanuseniorshealth.notices.activation.HttpCodeManagementApi
import org.paramanuseniorshealth.notices.activation.PublicCode
import org.paramanuseniorshealth.notices.activation.RedeemPolicy
import org.paramanuseniorshealth.notices.activation.RedeemResult
import org.paramanuseniorshealth.notices.activation.ResolvingCodeManagementApi
import org.paramanuseniorshealth.notices.analytics.Analytics

class RedeemPolicyTest {

    private val good = requireNotNull(ActivationCode.withCheckCharacter("ABCD123"))

    private fun claim(api: RecordingCodeManagementApi) = runBlocking { RedeemPolicy.claim(api, good) }

    @Test
    fun `active is claimed and carries the dispensary and its node`() {
        val node = mapOf("label" to "Main", "topics" to mapOf("general" to "General"))
        val api = RecordingCodeManagementApi(
            CodeManagementResult.Active(PublicCode(good, "main", false, 1L), node)
        )
        assertEquals(ClaimOutcome.Claimed("main", node), claim(api))
        assertEquals(listOf(good), api.activateCalls)
    }

    @Test
    fun `active without a dispensary is still claimed`() {
        val api = RecordingCodeManagementApi(
            CodeManagementResult.Active(PublicCode(good, null, false, null), null)
        )
        assertEquals(ClaimOutcome.Claimed(null, null), claim(api))
    }

    @Test
    fun `refused is rejected, not a transient failure`() {
        val api = RecordingCodeManagementApi(CodeManagementResult.Refused("already_claimed"))
        assertEquals(ClaimOutcome.Rejected, claim(api))
    }

    @Test
    fun `revoked from activate is a contract break and resolves as unreachable`() {
        assertEquals(ClaimOutcome.Unreachable, claim(RecordingCodeManagementApi(CodeManagementResult.Revoked)))
    }

    /** The safety-critical one: a network blip must never tell a patient their slip was refused. */
    @Test
    fun `inconclusive is unreachable, never rejected`() {
        assertEquals(
            ClaimOutcome.Unreachable,
            claim(RecordingCodeManagementApi(CodeManagementResult.Inconclusive)),
        )
    }

    /** The state the app is really in when this ships: no base URL published yet. */
    @Test
    fun `a blank base url makes no call and ends as unreachable`() {
        var transportCalls = 0
        val api = HttpCodeManagementApi(
            baseUrl = CodeManagementConfig.DEFAULT,
            analytics = Analytics(RecordingSink()),
            tokenProvider = { "token" },
            transport = { _, _, _ -> transportCalls++; error("must not be called") },
        )
        val outcome = runBlocking { RedeemPolicy.claim(api, good) }
        assertEquals(ClaimOutcome.Unreachable, outcome)
        assertEquals(0, transportCalls)
    }

    @Test
    fun `a mistyped code is answered locally`() {
        assertEquals(RedeemResult.Mistyped, RedeemPolicy.localAnswer("not a code", null))
    }

    @Test
    fun `retyping the stored code is answered locally, however it is written`() {
        assertEquals(RedeemResult.SameCode, RedeemPolicy.localAnswer(good.lowercase(), good))
    }

    @Test
    fun `a revoked phone's own code goes to the server`() {
        assertNull(RedeemPolicy.localAnswer(good, good, revoked = true))
        assertEquals(RedeemResult.Mistyped, RedeemPolicy.localAnswer("not a code", good, revoked = true))
    }

    @Test
    fun `a new valid code has no local answer`() {
        assertNull(RedeemPolicy.localAnswer(good, null))
        assertNull(RedeemPolicy.localAnswer(good, "SOMETHING"))
    }
}

class RedeemResultMappingTest {

    @Test
    fun `every outcome maps to the result the patient sees`() {
        assertEquals(RedeemResult.Success, RedeemPolicy.resultFor(ClaimOutcome.Claimed("main", null)))
        assertEquals(RedeemResult.NotAccepted, RedeemPolicy.resultFor(ClaimOutcome.Rejected))
        assertEquals(RedeemResult.Offline, RedeemPolicy.resultFor(ClaimOutcome.Unreachable))
    }

    @Test
    fun `a refusal of a revoked phone's own code says it is the stopped code`() {
        assertEquals(RedeemResult.SameCode, RedeemPolicy.resultFor(ClaimOutcome.Rejected, ownCode = true))
        // Only a refusal: taking the code back, or a network failure, reads as for any code.
        assertEquals(RedeemResult.Success, RedeemPolicy.resultFor(ClaimOutcome.Claimed(null, null), ownCode = true))
        assertEquals(RedeemResult.Offline, RedeemPolicy.resultFor(ClaimOutcome.Unreachable, ownCode = true))
    }

    @Test
    fun `only an unreachable outcome has an offline cause`() {
        assertEquals("transport", RedeemPolicy.offlineCauseFor(ClaimOutcome.Unreachable))
        assertNull(RedeemPolicy.offlineCauseFor(ClaimOutcome.Rejected))
        assertNull(RedeemPolicy.offlineCauseFor(ClaimOutcome.Claimed(null, null)))
    }

    /** The funnel is queried by these exact strings. */
    @Test
    fun `the offline cause strings are pinned`() {
        assertEquals("no_uid", RedeemPolicy.CAUSE_NO_UID)
        assertEquals("transport", RedeemPolicy.CAUSE_TRANSPORT)
        assertEquals("timeout", RedeemPolicy.CAUSE_TIMEOUT)
    }

    /** Each server answer, end to end through claim and resultFor. */
    @Test
    fun `server answers reach the patient correctly`() {
        fun seen(r: CodeManagementResult) = runBlocking {
            RedeemPolicy.resultFor(RedeemPolicy.claim(RecordingCodeManagementApi(r), "ABCD1230"))
        }
        assertEquals(RedeemResult.Success, seen(CodeManagementResult.Active(PublicCode("X", "main", false, 1L), null)))
        assertEquals(RedeemResult.NotAccepted, seen(CodeManagementResult.Refused("revoked")))
        assertEquals(RedeemResult.Offline, seen(CodeManagementResult.Inconclusive))
        assertEquals(RedeemResult.Offline, seen(CodeManagementResult.Revoked))
    }
}

class ResolvingCodeManagementApiTest {

    @Test
    fun `the url is re-read on every call`() {
        var url = ""
        val built = mutableListOf<String>()
        val api = ResolvingCodeManagementApi({ url }) { u -> built += u; RecordingCodeManagementApi() }
        runBlocking {
            api.activate("A")
            url = "https://example.test"
            api.status("A")
        }
        assertEquals(listOf("", "https://example.test"), built)
    }

    @Test
    fun `a throwing source falls back to the default`() {
        val built = mutableListOf<String>()
        val api = ResolvingCodeManagementApi({ error("boom") }) { u -> built += u; RecordingCodeManagementApi() }
        runBlocking { api.activate("A") }
        assertEquals(listOf(CodeManagementConfig.DEFAULT), built)
    }

    @Test
    fun `calls are delegated to the built client`() {
        val inner = RecordingCodeManagementApi()
        val api = ResolvingCodeManagementApi({ "u" }) { inner }
        runBlocking { api.activate("A"); api.status("B") }
        assertEquals(listOf("A"), inner.activateCalls)
        assertEquals(listOf("B"), inner.statusCalls)
    }
}
