package org.paramanuseniorshealth.notices.activation

/** What claiming a code through [CodeManagementApi] came to, before any of it is stored. */
sealed interface ClaimOutcome {
    /** The server says the claim is this phone's. [dispensary] is the code's dispensary id, if any. */
    data class Claimed(val dispensary: String?) : ClaimOutcome

    /** A definitive no. Maps to [RedeemResult.NotAccepted]. */
    data object Rejected : ClaimOutcome

    /** No definitive answer. Maps to [RedeemResult.Offline]: the patient is told to try again. */
    data object Unreachable : ClaimOutcome
}

/**
 * The decisions [ActivationRepository.redeem] makes, pulled out of a class that needs an Android
 * Context so a JVM test can reach every branch.
 */
object RedeemPolicy {

    // The values of ActivationRepository.lastOfflineCause: an analytics contract, queried by these
    // exact strings, so a test pins them.
    const val CAUSE_NO_UID = "no_uid"
    const val CAUSE_TRANSPORT = "transport"
    const val CAUSE_TIMEOUT = "timeout"

    /**
     * What the patient is told. Only [ClaimOutcome.Rejected] can send them back to the counter;
     * an uncertain answer is [RedeemResult.Offline], "try again".
     */
    fun resultFor(outcome: ClaimOutcome): RedeemResult = when (outcome) {
        is ClaimOutcome.Claimed -> RedeemResult.Success
        ClaimOutcome.Rejected -> RedeemResult.NotAccepted
        ClaimOutcome.Unreachable -> RedeemResult.Offline
    }

    /** Why the result is Offline, or null when it is not. */
    fun offlineCauseFor(outcome: ClaimOutcome): String? =
        if (outcome == ClaimOutcome.Unreachable) CAUSE_TRANSPORT else null

    /**
     * The answers that need no server. Null means the code is worth sending.
     *
     * Retyping the code this phone already holds is answered here rather than by the server, which
     * would call it already used -- to somebody holding the very slip it is printed on.
     */
    fun localAnswer(rawCode: String, storedCode: String?): RedeemResult? {
        if (!ActivationCode.isValid(rawCode)) return RedeemResult.Mistyped
        if (ActivationCode.normalise(rawCode) == storedCode) return RedeemResult.SameCode
        return null
    }

    /**
     * Claims [code]. The distinction that matters most is here: only an explicit refusal (or a
     * revoked code) is [ClaimOutcome.Rejected]; every other uncertainty is [ClaimOutcome.Unreachable].
     * A patient whose slip is fine but whose network blipped must not be told the code was rejected.
     */
    suspend fun claim(api: CodeManagementApi, code: String): ClaimOutcome = when (val r = api.activate(code)) {
        is CodeManagementResult.Active -> ClaimOutcome.Claimed(r.code.dispensary)
        is CodeManagementResult.Refused -> ClaimOutcome.Rejected
        // /activate never answers Revoked (a revoked code arrives as Refused("revoked")), so this
        // would be a contract break. Resolved in neither direction, like any unexpected shape.
        CodeManagementResult.Revoked -> ClaimOutcome.Unreachable
        CodeManagementResult.Inconclusive -> ClaimOutcome.Unreachable
    }
}
