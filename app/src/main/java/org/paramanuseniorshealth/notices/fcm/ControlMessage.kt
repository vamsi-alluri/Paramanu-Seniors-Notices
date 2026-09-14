package org.paramanuseniorshealth.notices.fcm

import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * Messages that manage the app rather than inform its user: data-only, no title, never drawn.
 *
 * They arrive on the control topic, which a phone holds for as long as it holds a claim -- revoked
 * included. That is what lets a restore reach a revoked phone at once instead of waiting for its
 * daily check. See docs/sender-contract.md.
 *
 * Kept as a pure function of the data map so it can be tested without FCM. The messaging service
 * checks this *before* it looks for a title, since a payload with no title is otherwise dropped as
 * malformed -- and every one of these has no title on purpose.
 */
sealed interface ControlMessage {

    /** When staff acted, in epoch millis. Orders messages that FCM may deliver out of order. */
    val at: Long

    /** Stop delivery to the phone holding [code]. */
    data class Revoke(val code: String, override val at: Long) : ControlMessage

    /** Start delivery again to the phone holding [code]. */
    data class Resume(val code: String, override val at: Long) : ControlMessage

    /**
     * [dispensary]'s banner changed. An empty [html] means it was removed.
     *
     * Every dispensary's banner goes out on the one control topic, so the phone applies only its own.
     */
    data class Banner(val dispensary: String, val html: String, override val at: Long) : ControlMessage

    companion object {

        fun parse(data: Map<String, String>): ControlMessage? {
            val type = data["type"] ?: return null
            // Every control message is stamped. One without a stamp cannot be ordered against the
            // others, so it is refused rather than guessed at.
            val at = data["at"]?.trim()?.toLongOrNull() ?: return null
            return when (type) {
                "revoke" -> codeIn(data)?.let { Revoke(it, at) }
                "resume" -> codeIn(data)?.let { Resume(it, at) }
                // An absent key is a malformed envelope; an empty one is a removal. Wiping the header
                // on the strength of a malformed message would be the worse mistake.
                "banner" -> {
                    val dispensary = data["dispensary"]?.trim().orEmpty()
                    if (dispensary.isEmpty()) null else data["html"]?.let { Banner(dispensary, it, at) }
                }
                // Unknown types are ignored, so a later sender can add one without breaking phones
                // that have not updated.
                else -> null
            }
        }

        /**
         * Whether a message stamped [at] should be applied after one stamped [lastApplied].
         *
         * FCM does not promise order, so a Disable and an Enable sent seconds apart can land the other
         * way round, and applying the late one would leave the phone in the wrong state until its
         * daily check. A repeat of the same stamp is a redelivery and is not applied twice.
         */
        fun isNewer(at: Long, lastApplied: Long): Boolean = at > lastApplied

        private fun codeIn(data: Map<String, String>): String? {
            val raw = data["code"]?.trim().orEmpty()
            if (raw.isEmpty()) return null
            // Normalised the same way a typed code is, so a comparison against the stored code cannot
            // fail on formatting the sender never intended to matter.
            return ActivationCode.normalise(raw).takeIf { it.isNotEmpty() }
        }
    }
}
