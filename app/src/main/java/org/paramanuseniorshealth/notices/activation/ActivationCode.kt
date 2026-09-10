package org.paramanuseniorshealth.notices.activation

/**
 * Activation codes in Crockford Base32.
 *
 * The alphabet omits `I`, `L`, `O` and `U`, and normalisation folds the characters people actually
 * confuse -- `O` to zero, `I`/`L` to one -- before validating. That matters more than usual here:
 * the codes are read off a paper slip at a dispensary counter and typed by people in their
 * eighties and nineties, so "is this a zero or a letter O" must never be a question the user has
 * to answer correctly.
 *
 * A code is [PAYLOAD_LENGTH] payload characters plus one check character. The check character is
 * what lets the app say "that code is mistyped" locally and instantly, instead of making a network
 * round trip and coming back with the far more alarming "that code is not valid".
 */
object ActivationCode {

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PAYLOAD_LENGTH = 7
    const val LENGTH = PAYLOAD_LENGTH + 1
    const val GROUP = 4

    /**
     * Uppercases, drops separators, and folds the confusable characters onto the digits they are
     * mistaken for. Anything still outside the alphabet is left in place so [isValid] can reject
     * it -- silently discarding it would turn a wrong code into a different wrong code.
     */
    fun normalise(raw: String): String = buildString {
        for (ch in raw.uppercase()) {
            when (ch) {
                ' ', '-', '\t', '\n', '\r', '.', '_' -> Unit
                'O' -> append('0')
                'I', 'L' -> append('1')
                else -> append(ch)
            }
        }
    }

    /** The check character for a payload, or null when the payload is not well formed. */
    fun checkCharacter(payload: String): Char? {
        if (payload.length != PAYLOAD_LENGTH) return null
        var sum = 0
        for ((index, ch) in payload.withIndex()) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            // Position-weighted so that transposing two adjacent characters -- the most common
            // typing error after a plain substitution -- changes the sum.
            sum += value * (index + 1)
        }
        return ALPHABET[sum % ALPHABET.length]
    }

    /** Builds a full code from a payload. Used by the sender console, and by the tests. */
    fun withCheckCharacter(payload: String): String? =
        checkCharacter(payload)?.let { payload + it }

    /**
     * True when [raw] normalises to a structurally valid code. This says nothing about whether the
     * code was ever issued or has already been used -- only the database can answer that.
     */
    fun isValid(raw: String): Boolean {
        val code = normalise(raw)
        if (code.length != LENGTH) return false
        val payload = code.substring(0, PAYLOAD_LENGTH)
        return checkCharacter(payload) == code[PAYLOAD_LENGTH]
    }

    /** Groups a code for display as `XXXX-XXXX`, which is markedly easier to read back aloud. */
    fun format(raw: String): String {
        val code = normalise(raw)
        return if (code.length >= GROUP) "${code.substring(0, GROUP)}-${code.substring(GROUP)}" else code
    }
}
