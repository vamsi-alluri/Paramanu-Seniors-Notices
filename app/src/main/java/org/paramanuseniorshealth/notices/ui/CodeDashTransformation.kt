package org.paramanuseniorshealth.notices.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * Displays a code as `XXXX-XXXX` while the field's actual value stays unpunctuated.
 *
 * The slips and the console both print the grouped form, so the field has to look like the paper
 * the user is copying from -- otherwise they see a mismatch and assume they have made a mistake.
 *
 * Doing it as a visual transformation rather than by editing the text means the dash can never be
 * deleted, retyped, or land in the middle of a paste. The stored value is always exactly the eight
 * characters, which is what gets sent to the database.
 */
object CodeDashTransformation : VisualTransformation {

    private const val GROUP = 4

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= GROUP) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val grouped = raw.substring(0, GROUP) + "-" + raw.substring(GROUP)

        val mapping = object : OffsetMapping {
            // One character is inserted at index GROUP, so every position after it shifts by one.
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= GROUP) offset else (offset + 1).coerceAtMost(grouped.length)

            override fun transformedToOriginal(offset: Int): Int =
                if (offset <= GROUP) offset else (offset - 1).coerceAtMost(raw.length)
        }

        return TransformedText(AnnotatedString(grouped), mapping)
    }

    /**
     * Cleans anything the user or the clipboard offers down to storable characters.
     *
     * Typed dashes, spaces and the confusable letters are all folded here rather than rejected, so
     * pasting `MQCA-MGPC` from an email works exactly as typing it does.
     */
    fun clean(input: String): String =
        ActivationCode.normalise(input).take(ActivationCode.LENGTH)
}
