package org.paramanuseniorshealth.notices.analytics

/**
 * Where the kill switch's value comes from. One implementation ships; the tests supply their own.
 *
 * The same seam [AnalyticsSink] draws for the event taxonomy, drawn again for the switch: the
 * Remote Config SDK appears in exactly one file behind this, so [AnalyticsConfig] and the
 * "off unless it says on" guarantee stay on the JVM where a test can reach them.
 */
interface AnalyticsSwitch {

    /**
     * The value in force, read locally. No network, no suspension -- so the gate can be set from a
     * deterministic value at process start rather than racing a read.
     */
    fun enabled(): Boolean

    /** Fetches and activates the live value, and returns what is now in force. */
    suspend fun refresh(): Boolean
}
