package org.paramanuseniorshealth.notices.data

import android.content.Context
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The standing information shown at the top of the notice list: dispensary and OPD hours, and
 * anything else that is true until somebody changes it.
 *
 * Stored with the dispensary, at `/dispensaries/{id}/info`, and cached locally. The cache is not an
 * optimisation, it is the point: this is exactly what somebody checks before setting out, and they
 * may well be checking it in a lift, on a bus, or somewhere with no signal. A stale answer is useful;
 * a spinner is not.
 *
 * Edited in the code console, never in the app. A change reaches the phone two ways: pushed with the
 * html inside the message ([applyPushed]), and read along with the rest of the dispensary
 * ([applySnapshot]) when the app comes to the foreground, for a phone that was switched off when the
 * push went out.
 */
class InfoRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _info = MutableStateFlow(readCached())

    /**
     * Last known value, available instantly and offline. Null before the first successful read.
     *
     * A stream rather than a one-off read because the banner can change under a screen that is
     * already showing it. A push is applied by the messaging service with no UI attached, and a copy
     * held by the view model caught up only when the view model was next created -- which, for a
     * phone left sitting in recents, took days.
     */
    val info: StateFlow<OfficeInfo?> = _info.asStateFlow()

    /** Applies `/dispensaries/{id}/info` as read by [DispensaryRepository]. */
    fun applySnapshot(snapshot: DataSnapshot) {
        val heading = snapshot.child("heading").getValue(String::class.java).orEmpty()
        val body = snapshot.child("lines").getValue(String::class.java).orEmpty()
        val html = snapshot.child("html").getValue(String::class.java).orEmpty()
        // The console stamps removal as well as saving, so a removed banner still carries a time and
        // is not mistaken for a dispensary whose banner was never filled in.
        val htmlAt = snapshot.child("htmlUpdated").getValue(Long::class.java) ?: 0L
        if (heading.isBlank() && body.isBlank() && html.isBlank() && htmlAt == 0L) return

        val editor = prefs.edit()
            .putString(KEY_HEADING, heading)
            .putString(KEY_LINES, body)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
        // A pushed banner newer than what this read returned is kept. The console writes the database
        // before it queues the push, so this happens only when a slow read began before the save and
        // finished after the push had already landed.
        if (htmlAt >= prefs.getLong(KEY_HTML_AT, 0L)) {
            editor.putString(KEY_HTML, html).putLong(KEY_HTML_AT, htmlAt)
        }
        editor.apply()

        _info.value = readCached()
    }

    /**
     * Applies a banner delivered by push, with no network at all.
     *
     * The html travels inside the message on purpose. Having four hundred phones fetch the banner the
     * moment a push lands would open four hundred database connections at once against a cap of 100
     * -- SYSTEM.md 5.14, a trap this app has already fallen into once.
     *
     * [at] is when the console saved it. A push older than the banner already held is ignored, so
     * two quick edits arriving out of order cannot leave the first one showing.
     */
    fun applyPushed(html: String, at: Long) {
        if (at <= prefs.getLong(KEY_HTML_AT, 0L)) return
        prefs.edit()
            .putString(KEY_HTML, html)
            .putLong(KEY_HTML_AT, at)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        _info.value = readCached()
    }

    private fun readCached(): OfficeInfo? {
        val html = prefs.getString(KEY_HTML, null)?.takeIf { it.isNotBlank() }
        // A banner alone is enough: the heading and lines may never have been set on a dispensary that
        // only ever carried the rich version.
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

    private companion object {
        private const val PREFS = "office_info"
        private const val KEY_HEADING = "heading"
        private const val KEY_LINES = "lines"
        private const val KEY_HTML = "html"
        private const val KEY_HTML_AT = "html_at"
        private const val KEY_UPDATED = "updated"
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
     * because they are the fallback if the banner is ever removed.
     */
    val html: String? = null,
)
