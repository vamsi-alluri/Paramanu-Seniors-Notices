package org.paramanuseniorshealth.notices.data

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The standing information shown at the top of the notice list: dispensary and OPD hours, and
 * anything else that is true until somebody changes it.
 *
 * Read from `/info` in Realtime Database and cached locally. The cache is not an optimisation, it
 * is the point: this is exactly what somebody checks before setting out, and they may well be
 * checking it in a lift, on a bus, or somewhere with no signal. A stale answer is useful; a spinner
 * is not.
 *
 * Edited in the code console, never in the app.
 */
class InfoRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last known value, available instantly and offline. Null before the first successful fetch. */
    val cached: OfficeInfo?
        get() {
            val html = prefs.getString(KEY_HTML, null)?.takeIf { it.isNotBlank() }
            // A banner alone is enough: the heading and lines may never have been set on a
            // database that only ever carried the rich version.
            val heading = prefs.getString(KEY_HEADING, null)
            if (heading == null && html == null) return null
            return OfficeInfo(
                heading = heading.orEmpty(),
                lines = prefs.getString(KEY_LINES, "").orEmpty()
                    .split("\n")
                    .filter { it.isNotBlank() },
                updatedAt = prefs.getLong(KEY_UPDATED, 0L),
                html = html,
            )
        }

    /**
     * Refreshes from the database. Returns the new value, or null when it could not be fetched --
     * callers keep showing [cached] in that case rather than emptying the header.
     */
    suspend fun refresh(): OfficeInfo? = withTimeoutOrNull(TIMEOUT_MS) {
        try {
            val snapshot = suspendCancellableCoroutine { continuation ->
                FirebaseDatabase.getInstance().reference.child(NODE).get()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) continuation.resume(task.result)
                        else continuation.resumeWithException(
                            task.exception ?: IllegalStateException("info fetch failed")
                        )
                    }
            }

            val heading = snapshot.child("heading").getValue(String::class.java).orEmpty()
            val body = snapshot.child("lines").getValue(String::class.java).orEmpty()
            val html = snapshot.child("html").getValue(String::class.java).orEmpty()
            if (heading.isBlank() && body.isBlank() && html.isBlank()) return@withTimeoutOrNull null

            prefs.edit()
                .putString(KEY_HEADING, heading)
                .putString(KEY_LINES, body)
                .putString(KEY_HTML, html)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply()

            OfficeInfo(
                heading = heading,
                lines = body.split("\n").filter { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
                html = html.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            // Expected once per install: init runs before the code is redeemed, and /info is
            // readable only to an authenticated user. NoticeViewModel refetches after redemption.
            Log.w(TAG, "Could not refresh office info", e)
            null
        }
    }

    private companion object {
        private const val TAG = "InfoRepository"
        private const val PREFS = "office_info"
        private const val NODE = "info"
        private const val KEY_HEADING = "heading"
        private const val KEY_LINES = "lines"
        private const val KEY_HTML = "html"
        private const val KEY_UPDATED = "updated"
        private const val TIMEOUT_MS = 6_000L
    }
}

data class OfficeInfo(
    val heading: String,
    val lines: List<String>,
    val updatedAt: Long,
    /**
     * The rich banner, when the console has one stored.
     *
     * When present it replaces [heading] and [lines] entirely. Those are kept rather than removed
     * because they are what any app version predating the banner still shows, and because they are
     * the fallback if the banner is ever removed.
     */
    val html: String? = null,
)
