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
import org.paramanuseniorshealth.notices.analytics.Analytics

class RedeemPolicyTest {

    private val good = requireNotNull(ActivationCode.withCheckCharacter("ABCD123"))

    private fun claim(api: RecordingCodeManagementApi) = runBlocking { RedeemPolicy.claim(api, good) }

    @Test
    fun `active is claimed and carries the dispensary`() {
        val api = RecordingCodeManagementApi(
            CodeManagementResult.Active(PublicCode(good, "main", false, 1L), null)
        )
        assertEquals(ClaimOutcome.Claimed("main"), claim(api))
        assertEquals(listOf(good), api.activateCalls)
    }

    @Test
    fun `active without a dispensary is still claimed`() {
        val api = RecordingCodeManagementApi(
            CodeManagementResult.Active(PublicCode(good, null, false, null), null)
        )
        assertEquals(ClaimOutcome.Claimed(null), claim(api))
    }

    @Test
    fun `refused is rejected, not a transient failure`() {
        val api = RecordingCodeManagementApi(CodeManagementResult.Refused("already_claimed"))
        assertEquals(ClaimOutcome.Rejected, claim(api))
    }

    @Test
    fun `revoked is rejected`() {
        assertEquals(ClaimOutcome.Rejected, claim(RecordingCodeManagementApi(CodeManagementResult.Revoked)))
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
    fun `a new valid code has no local answer`() {
        assertNull(RedeemPolicy.localAnswer(good, null))
        assertNull(RedeemPolicy.localAnswer(good, "SOMETHING"))
    }
}
