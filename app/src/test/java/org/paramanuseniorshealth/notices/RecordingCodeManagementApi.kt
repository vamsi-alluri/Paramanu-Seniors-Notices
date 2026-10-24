package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.activation.CodeManagementApi
import org.paramanuseniorshealth.notices.activation.CodeManagementResult

/** A [CodeManagementApi] that answers what the test scripted and records every call made to it. */
class RecordingCodeManagementApi(
    private val activateReturns: CodeManagementResult = CodeManagementResult.Inconclusive,
    private val statusReturns: CodeManagementResult = CodeManagementResult.Inconclusive,
) : CodeManagementApi {

    val activateCalls = mutableListOf<String>()
    val statusCalls = mutableListOf<String>()

    override suspend fun status(code: String): CodeManagementResult {
        statusCalls += code
        return statusReturns
    }

    override suspend fun activate(code: String): CodeManagementResult {
        activateCalls += code
        return activateReturns
    }
}
