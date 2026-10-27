package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Test
import org.paramanuseniorshealth.notices.update.UpdatePrompt
import org.paramanuseniorshealth.notices.update.UpdatePrompt.Kind

class UpdatePromptTest {

    private val now = 10L * UpdatePrompt.REPROMPT_MS

    private fun decide(
        priority: Int,
        flexibleAllowed: Boolean = true,
        immediateAllowed: Boolean = true,
        dismissedVersion: Long = 0L,
        lastPromptAt: Long = 0L,
    ) = UpdatePrompt.decide(
        priority = priority,
        availableVersion = 21L,
        flexibleAllowed = flexibleAllowed,
        immediateAllowed = immediateAllowed,
        dismissedVersion = dismissedVersion,
        lastPromptAt = lastPromptAt,
        now = now,
    )

    // ---- 0 to 2: flexible, once per version

    @Test
    fun `a low priority asks flexibly`() {
        assertEquals(Kind.FLEXIBLE, decide(priority = 0))
        assertEquals(Kind.FLEXIBLE, decide(priority = 2))
    }

    @Test
    fun `a low priority is not asked again once that version was dismissed`() {
        assertEquals(Kind.NONE, decide(priority = 2, dismissedVersion = 21L))
    }

    @Test
    fun `a newer version is asked about though an older one was dismissed`() {
        assertEquals(Kind.FLEXIBLE, decide(priority = 0, dismissedVersion = 20L))
    }

    // ---- 3 and 4: flexible, every open

    @Test
    fun `a middle priority asks even after a dismissal`() {
        assertEquals(Kind.FLEXIBLE, decide(priority = 3, dismissedVersion = 21L))
        assertEquals(Kind.FLEXIBLE, decide(priority = 4, dismissedVersion = 21L))
    }

    // ---- 5: immediate

    @Test
    fun `the top priority asks immediately`() {
        assertEquals(Kind.IMMEDIATE, decide(priority = 5, dismissedVersion = 21L))
    }

    @Test
    fun `the top priority falls back to flexible when Play will not run immediate`() {
        assertEquals(Kind.FLEXIBLE, decide(priority = 5, immediateAllowed = false))
    }

    // ---- throttling and refusals

    @Test
    fun `nobody is asked twice inside the hour, whatever the priority`() {
        // Coming back from the external PDF viewer is a resume, and must not raise the prompt again.
        val justNow = now - UpdatePrompt.REPROMPT_MS + 1
        assertEquals(Kind.NONE, decide(priority = 5, lastPromptAt = justNow))
        assertEquals(Kind.NONE, decide(priority = 0, lastPromptAt = justNow))
    }

    @Test
    fun `an hour later they are asked again`() {
        assertEquals(Kind.FLEXIBLE, decide(priority = 3, lastPromptAt = now - UpdatePrompt.REPROMPT_MS))
    }

    @Test
    fun `a prompt stamped in the future does not silence prompts forever`() {
        // A wall-clock change can put the last prompt ahead of now.
        assertEquals(Kind.FLEXIBLE, decide(priority = 3, lastPromptAt = now + 1))
    }

    @Test
    fun `nothing is asked when Play allows neither type`() {
        assertEquals(Kind.NONE, decide(priority = 5, flexibleAllowed = false, immediateAllowed = false))
        assertEquals(Kind.NONE, decide(priority = 0, flexibleAllowed = false))
    }
}
