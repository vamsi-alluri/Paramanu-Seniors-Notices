package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.analytics.AnalyticsSink

/**
 * An [AnalyticsSink] that remembers what it was told, shared by every test that needs to see what
 * reached the SDK boundary. It lives in its own file so those tests do not each grow a private copy
 * that drifts from the others.
 *
 * Each method can be told to throw, independently, after recording the call -- so a test can check
 * both that the call arrived and that the caller survived it.
 */
class RecordingSink(
    private val throwOnLog: Boolean = false,
    private val throwOnSetCollection: Boolean = false,
) : AnalyticsSink {
    val logged = mutableListOf<Pair<String, Map<String, Any>>>()
    val collectionCalls = mutableListOf<Boolean>()

    override fun log(name: String, params: Map<String, Any>) {
        logged += name to params
        if (throwOnLog) error("sink log is broken")
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        collectionCalls += enabled
        if (throwOnSetCollection) error("sink setCollectionEnabled is broken")
    }
}
