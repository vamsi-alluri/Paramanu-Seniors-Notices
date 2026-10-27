package org.paramanuseniorshealth.notices.update

/**
 * How hard to ask for an update Play says is available, from the release's own in-app update
 * priority (0 to 5). The priority is set per release through the Play Developer API when the
 * release is created, and cannot be changed afterwards; see the runbook. Pure, so every branch is
 * reachable from a JVM test.
 *
 * | priority | asks with                         | how often                          |
 * |----------|-----------------------------------|------------------------------------|
 * | 0-2      | Play's flexible prompt            | once per available version         |
 * | 3-4      | Play's flexible prompt            | every open                         |
 * | 5        | Play's immediate, full-screen flow| every open; still cancellable      |
 *
 * "Every open" is at most once in [REPROMPT_MS]: the app hands circulars to an external PDF viewer,
 * and coming back from one must not raise the prompt again.
 *
 * Forcing an update is not this table's job. That is [AppGate.Unsupported], which Play cannot
 * express and which also silences notifications.
 */
object UpdatePrompt {

    enum class Kind { NONE, FLEXIBLE, IMMEDIATE }

    const val REPROMPT_MS = 60L * 60 * 1000

    fun decide(
        priority: Int,
        availableVersion: Long,
        flexibleAllowed: Boolean,
        immediateAllowed: Boolean,
        dismissedVersion: Long,
        lastPromptAt: Long,
        now: Long,
    ): Kind {
        val recentlyAsked = lastPromptAt in 1..now && now - lastPromptAt < REPROMPT_MS
        return when {
            recentlyAsked -> Kind.NONE
            priority >= 5 && immediateAllowed -> Kind.IMMEDIATE
            // A top-priority release that Play will not run immediately still gets the softer ask.
            priority >= 3 && flexibleAllowed -> Kind.FLEXIBLE
            dismissedVersion == availableVersion -> Kind.NONE
            flexibleAllowed -> Kind.FLEXIBLE
            else -> Kind.NONE
        }
    }
}
