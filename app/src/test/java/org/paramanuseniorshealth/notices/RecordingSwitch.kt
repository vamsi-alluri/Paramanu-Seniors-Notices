package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.analytics.AnalyticsSwitch

/**
 * An [AnalyticsSwitch] that stands in for Remote Config: it answers with whatever the test
 * scripted and remembers how often it was asked to fetch.
 *
 * In its own file rather than private to one test class, for the same reason as [RecordingSink]:
 * more than one test class uses it, and a private copy in each would drift.
 *
 * [failWith] is thrown after the call is recorded, so a test can check both that the fetch was
 * attempted and what the caller was left holding. The exception is supplied rather than fixed
 * because a real fetch can fail with anything, and a cancellation has to travel differently from a
 * failure.
 */
class RecordingSwitch(
    initiallyEnabled: Boolean = false,
    private val fetchReturns: Boolean = true,
    private val failWith: Exception? = null,
) : AnalyticsSwitch {

    /** The activated value, which a successful [refresh] replaces -- as Remote Config's does. */
    private var activated: Boolean = initiallyEnabled

    var refreshCalls: Int = 0
        private set

    override fun enabled(): Boolean = activated

    override suspend fun refresh(): Boolean {
        refreshCalls++
        failWith?.let { throw it }
        activated = fetchReturns
        return activated
    }
}
