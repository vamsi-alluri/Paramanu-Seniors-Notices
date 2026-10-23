package org.paramanuseniorshealth.notices.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The only file in the app that imports Firebase Analytics.
 *
 * Everything that decides *what* to measure lives behind [AnalyticsSink] on the JVM side; this
 * does nothing but translate a map into a Bundle.
 *
 * Failures are logged here and go no further. [Analytics] has no Android imports, so it cannot log
 * and swallows silently; this is the layer that can see what went wrong.
 */
class FirebaseAnalyticsSink(context: Context) : AnalyticsSink {

    private val appContext = context.applicationContext

    // Resolved on first use rather than at construction, so a failure to obtain the instance is
    // caught by the same guard as every other Firebase call instead of escaping the constructor.
    private val firebase by lazy { FirebaseAnalytics.getInstance(appContext) }

    override fun log(name: String, params: Map<String, Any>) {
        guarded("log $name") {
            val bundle = Bundle(params.size)
            for ((key, value) in params) {
                when (value) {
                    is Long -> bundle.putLong(key, value)
                    is Double -> bundle.putDouble(key, value)
                    is String -> bundle.putString(key, value)
                    // The taxonomy only produces these three. Anything else is a programming
                    // error, and a string is the reading that loses least.
                    else -> bundle.putString(key, value.toString())
                }
            }
            firebase.logEvent(name, bundle)
        }
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        guarded("set collection $enabled") { firebase.setAnalyticsCollectionEnabled(enabled) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Analytics failed to $what: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "FirebaseAnalyticsSink"
    }
}
