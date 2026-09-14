package org.paramanuseniorshealth.notices.data

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.paramanuseniorshealth.notices.activation.Dispensary
import org.paramanuseniorshealth.notices.activation.DispensaryConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The dispensary this phone's code belongs to: its name, the topics it offers, and its banner.
 *
 * One read of `/dispensaries/{id}` brings all three, so the banner is handed on to [InfoRepository]
 * rather than fetched a second time. Cached locally for the same reason the banner always was: the
 * notice list and Settings must draw instantly and offline.
 *
 * Read on activation, when the app comes to the foreground, and at the daily check -- never from the
 * message path, where four hundred phones reading at once would hit the connection cap
 * (SYSTEM.md 5.14).
 */
class DispensaryRepository(context: Context, private val info: InfoRepository) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _dispensary = MutableStateFlow(DispensaryConfig.decode(prefs.getString(KEY_CONFIG, null)))

    /** The last known dispensary, as a stream for Settings. Null before the first successful read. */
    val dispensary: StateFlow<Dispensary?> = _dispensary.asStateFlow()

    val current: Dispensary? get() = _dispensary.value

    /**
     * Reads the dispensary and its banner. Returns what was read, or null when nothing could be --
     * in which case the cached list and banner stay exactly as they were.
     */
    suspend fun refresh(id: String): Dispensary? = withTimeoutOrNull(TIMEOUT_MS) {
        try {
            val snapshot = suspendCancellableCoroutine<DataSnapshot> { continuation ->
                FirebaseDatabase.getInstance().reference.child(NODE).child(id).get()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) continuation.resume(task.result)
                        else continuation.resumeWithException(
                            task.exception ?: IllegalStateException("dispensary fetch failed")
                        )
                    }
            }

            val parsed = DispensaryConfig.parse(id, snapshot.value) ?: run {
                Log.w(TAG, "No dispensary at /$NODE/$id; keeping what is cached")
                return@withTimeoutOrNull null
            }
            prefs.edit().putString(KEY_CONFIG, DispensaryConfig.encode(parsed)).apply()
            _dispensary.value = parsed
            info.applySnapshot(snapshot.child("info"))
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh dispensary $id", e)
            null
        }
    }

    private companion object {
        private const val TAG = "DispensaryRepo"
        private const val PREFS = "dispensary"
        private const val KEY_CONFIG = "config"
        private const val NODE = "dispensaries"
        private const val TIMEOUT_MS = 6_000L
    }
}
