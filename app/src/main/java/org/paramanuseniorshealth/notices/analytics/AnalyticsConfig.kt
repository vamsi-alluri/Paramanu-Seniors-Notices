package org.paramanuseniorshealth.notices.analytics

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.database.FirebaseDatabase
import org.paramanuseniorshealth.notices.data.awaitResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * The kill switch, read from RTDB and cached so it survives being offline.
 *
 * Its own node rather than a field on the dispensary record: it is not dispensary-scoped, and
 * putting it there would force the future multi-dispensary seam to carry it.
 *
 * Only a literal `true` enables collection. The node is hand-edited in the RTDB console, where a
 * string "true", a 1, or a deleted node are all realistic, and every one of them must mean off.
 */
class AnalyticsConfig(private val prefs: SharedPreferences) {

    fun cached(): Boolean = prefs.getBoolean(KEY, false)

    fun store(enabled: Boolean) = prefs.edit { putBoolean(KEY, enabled) }

    /**
     * Reads the live value, stores it, and returns it. On any failure the cached value stands --
     * which on a first launch is false, so a phone that has never reached the node collects
     * nothing.
     *
     * Cancellation is passed on rather than treated as a failed read: a caller that gave up on
     * this should stop, not be handed a stale answer as though the read had been tried.
     */
    suspend fun refresh(database: FirebaseDatabase): Boolean = try {
        val snapshot = database.getReference(PATH).get().awaitResult()
        parse(snapshot.value).also { store(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Could not read the analytics switch: ${e.message}")
        cached()
    }

    companion object {
        /**
         * Must sit under the `config/analytics` rule in `database.rules.json`, which is the only
         * world-readable grant. It is scoped to `analytics` on purpose: RTDB read access cascades
         * to every child, so anything added beneath `config/analytics` is public too, while a new
         * sibling under `config` is not covered and stays private. Do not widen the grant to
         * `config`, and do not put anything private under `config/analytics`.
         */
        const val PATH = "config/analytics/enabled"
        private const val KEY = "analytics_enabled"
        private const val TAG = "AnalyticsConfig"

        /** Anything that is not a literal boolean `true` is off. */
        fun parse(raw: Any?): Boolean = raw as? Boolean == true
    }
}
