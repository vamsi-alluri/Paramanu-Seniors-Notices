package org.paramanuseniorshealth.notices.fcm

import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * The revoke envelope: `{"type": "revoke", "code": "..."}` -- data-only, no title and no body.
 *
 * It is a topic broadcast, because this app is addressed only by topic: `onNewToken` is
 * deliberately not overridden and there is no per-device addressing anywhere. So every subscribed
 * phone receives every revoke and compares the code against its own. See docs/sender-contract.md.
 *
 * Kept as a pure function of the data map so it can be tested without FCM. The messaging service
 * has to check this *before* it looks for a title, since a payload with no title is otherwise
 * dropped as malformed -- and a revoke has no title on purpose.
 */
object RevokeMessage {

    private const val TYPE = "revoke"

    /** The revoked code when this payload is a revoke, otherwise null. */
    fun codeIn(data: Map<String, String>): String? {
        if (data["type"] != TYPE) return null
        val raw = data["code"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // Normalised the same way a typed code is, so a comparison against the stored code cannot
        // fail on formatting the sender never intended to matter.
        return ActivationCode.normalise(raw).takeIf { it.isNotEmpty() }
    }
}
