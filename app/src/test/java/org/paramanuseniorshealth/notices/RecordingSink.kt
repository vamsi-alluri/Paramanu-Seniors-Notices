package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.analytics.AnalyticsSink

/**
 * An [AnalyticsSink] that remembers what it was told, shared by every test that needs to see what
 * reached the SDK boundary. It lives in its own file so those tests do not each grow a private copy
 * that drifts from the others.
 */
class RecordingSink : AnalyticsSink {
    val logged = mutableListOf<Pair<String, Map<String, Any>>>()
    val collectionCalls = mutableListOf<Boolean>()
    override fun log(name: String, params: Map<String, Any>) { logged += name to params }
    override fun setCollectionEnabled(enabled: Boolean) { collectionCalls += enabled }
}
