package org.paramanuseniorshealth.notices.activation

/** What the `code_management` function says about a code. No `note` field, by design. */
data class PublicCode(
    val code: String,
    val dispensary: String?,
    val revoked: Boolean,
    val activatedAt: Long?,
)

sealed interface CodeManagementResult {
    /** The claim stands. [dispensary] is the server's dispensary node, absent when there is none. */
    data class Active(val code: PublicCode, val dispensary: Map<*, *>?) : CodeManagementResult

    /** The server said, unambiguously, that the code is revoked. */
    data object Revoked : CodeManagementResult

    /** The server refused an activation, with its reason. */
    data class Refused(val reason: String) : CodeManagementResult

    /**
     * Anything not confidently understood: a 401, a 500, a timeout, a bad body, no user, no base
     * URL. This is the default, and it must never be read as [Revoked] -- a token bug must not lock
     * a patient out of clinic notices.
     */
    data object Inconclusive : CodeManagementResult
}

interface CodeManagementApi {
    suspend fun status(code: String): CodeManagementResult
    suspend fun activate(code: String): CodeManagementResult
}

/** The server-controlled base URL, resolved through Remote Config by a later task. */
object CodeManagementConfig {
    const val KEY = "code_management_base_url"

    /** Empty until the function is deployed; empty means "make no call". */
    const val DEFAULT = ""

    /** Non-blank and https: the request carries a Bearer token, which must never go out in cleartext. */
    fun isUsable(baseUrl: String): Boolean {
        val url = baseUrl.trim()
        return url.length > HTTPS.length && url.startsWith(HTTPS, ignoreCase = true)
    }

    private const val HTTPS = "https://"
}

/**
 * The whole mapping policy, pure so a JVM test reaches every branch. Only an explicit, well-formed
 * answer maps to anything but [CodeManagementResult.Inconclusive].
 */
object CodeManagementResponse {

    fun parse(httpStatus: Int, body: Map<String, Any?>?): CodeManagementResult {
        if (body == null) return CodeManagementResult.Inconclusive
        return when (httpStatus) {
            200 -> when (body["status"]) {
                "active" -> active(body)
                "revoked" -> CodeManagementResult.Revoked
                else -> CodeManagementResult.Inconclusive
            }
            409 -> {
                val reason = body["reason"]
                if (body["status"] == "refused" && reason is String && reason.isNotBlank()) {
                    CodeManagementResult.Refused(reason)
                } else {
                    CodeManagementResult.Inconclusive
                }
            }
            else -> CodeManagementResult.Inconclusive
        }
    }

    private fun active(body: Map<String, Any?>): CodeManagementResult {
        val raw = body["code"] as? Map<*, *> ?: return CodeManagementResult.Inconclusive
        val code = raw["code"] as? String ?: return CodeManagementResult.Inconclusive
        val revoked = raw["revoked"] as? Boolean ?: return CodeManagementResult.Inconclusive
        // "active" and "revoked" at once is a contradiction the client cannot interpret, so it is
        // resolved in neither direction. The genuine revoke path is status=revoked above.
        if (revoked) return CodeManagementResult.Inconclusive
        val activatedAt = (raw["activatedAt"] as? Number)?.toLong()
        return CodeManagementResult.Active(
            PublicCode(code, raw["dispensary"] as? String, revoked, activatedAt),
            body["dispensary"] as? Map<*, *>,
        )
    }
}
