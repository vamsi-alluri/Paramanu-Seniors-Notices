package org.paramanuseniorshealth.notices.analytics

/**
 * Where events go. One implementation ships; the tests supply their own.
 *
 * This interface is the seam that keeps [Analytics] and the whole event taxonomy on the JVM: the
 * Firebase SDK appears in exactly one file behind it.
 */
interface AnalyticsSink {
    fun log(name: String, params: Map<String, Any>)
    fun setCollectionEnabled(enabled: Boolean)
}
