package org.paramanuseniorshealth.notices.activation

/**
 * The decisions [ActivationRepository.verify] makes, pulled out of a class that needs an Android
 * Context so a JVM test can reach every branch.
 */
object VerifyPolicy {

    /**
     * What the endpoint's answer comes to. [persist] is true only for a definitive answer: an
     * inconclusive one must leave the stored verification exactly as it was, because
     * [ActivationState.Unknown] permits delivery and overwriting a stored Revoked with it would
     * start showing notices again after one bad network call.
     */
    data class Verdict(
        val state: ActivationState,
        val persist: Boolean,
        val dispensaryId: String? = null,
        val dispensaryNode: Map<*, *>? = null,
    )

    private val INDETERMINATE = Verdict(ActivationState.Unknown, persist = false)

    suspend fun check(api: CodeManagementApi, code: String): Verdict = when (val r = api.status(code)) {
        is CodeManagementResult.Active ->
            Verdict(ActivationState.Active, persist = true, r.code.dispensary, r.dispensary)
        CodeManagementResult.Revoked -> Verdict(ActivationState.Revoked, persist = true)
        // /status never refuses; if it did it would be a contract break, resolved in neither direction.
        is CodeManagementResult.Refused -> INDETERMINATE
        CodeManagementResult.Inconclusive -> INDETERMINATE
    }
}
