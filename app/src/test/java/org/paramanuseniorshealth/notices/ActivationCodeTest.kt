package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationCode

class ActivationCodeTest {

    private fun code(payload: String): String =
        requireNotNull(ActivationCode.withCheckCharacter(payload))

    @Test
    fun `alphabet omits the characters people confuse`() {
        for (excluded in listOf('I', 'L', 'O', 'U')) {
            assertFalse("$excluded must not be in the alphabet", excluded in ActivationCode.ALPHABET)
        }
        assertEquals(32, ActivationCode.ALPHABET.length)
    }

    @Test
    fun `normalise folds confusables and strips separators`() {
        // A user reading a slip types the letter O for zero and lower-case l for one.
        assertEquals("0123456", ActivationCode.normalise("O123456"))
        assertEquals("1123456", ActivationCode.normalise("l123456"))
        assertEquals("1123456", ActivationCode.normalise("I123456"))
        assertEquals("ABCD1234", ActivationCode.normalise("abcd-1234"))
        assertEquals("ABCD1234", ActivationCode.normalise("ABCD 1234"))
    }

    @Test
    fun `a well formed code validates`() {
        assertTrue(ActivationCode.isValid(code("ABCD123")))
        assertTrue(ActivationCode.isValid(code("0000000")))
        assertTrue(ActivationCode.isValid(code("ZZZZZZZ")))
    }

    @Test
    fun `a code typed with confusable characters still validates`() {
        val valid = code("0123456")
        // Same code as a user would mistype it off paper.
        assertTrue(ActivationCode.isValid(valid.replaceFirst('0', 'O')))
    }

    @Test
    fun `a single wrong character is rejected`() {
        val valid = code("ABCD123")
        val payload = valid.substring(0, ActivationCode.PAYLOAD_LENGTH)
        // Change one payload character but keep the original check character.
        val corrupted = "X" + payload.substring(1) + valid.last()
        assertFalse(ActivationCode.isValid(corrupted))
    }

    @Test
    fun `transposing two adjacent characters is rejected`() {
        // Position weighting exists specifically to catch this; an unweighted sum would not.
        val valid = code("ABCD123")
        val chars = valid.toCharArray()
        val a = chars[0]
        chars[0] = chars[1]
        chars[1] = a
        assertFalse(ActivationCode.isValid(String(chars)))
    }

    @Test
    fun `wrong length is rejected`() {
        assertFalse(ActivationCode.isValid("ABC"))
        assertFalse(ActivationCode.isValid(code("ABCD123") + "X"))
        assertFalse(ActivationCode.isValid(""))
    }

    @Test
    fun `characters outside the alphabet are rejected rather than discarded`() {
        // Silently dropping them would turn one wrong code into a different wrong code.
        assertNull(ActivationCode.checkCharacter("ABCD12U"))
        assertFalse(ActivationCode.isValid("ABCD12U9"))
    }

    @Test
    fun `format groups the code for reading aloud`() {
        val valid = code("ABCD123")
        assertEquals("${valid.substring(0, 4)}-${valid.substring(4)}", ActivationCode.format(valid))
    }
}
