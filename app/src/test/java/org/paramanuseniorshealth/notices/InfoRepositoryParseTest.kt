package org.paramanuseniorshealth.notices

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.DispensaryConfig
import org.paramanuseniorshealth.notices.data.InfoRepository

class InfoRepositoryParseTest {

    private fun repo() = InfoRepository(MemoryPrefs())

    @Test
    fun `a map produces the office info the snapshot path produced`() {
        val repo = repo()
        repo.apply(
            mapOf(
                "heading" to "Dispensary timings",
                "lines" to "Mon-Fri 9-5\n\nSat 9-1",
                "html" to "<p>Closed Friday</p>",
                "htmlUpdated" to 1_000L,
            )
        )
        val info = repo.info.value!!
        assertEquals("Dispensary timings", info.heading)
        assertEquals(listOf("Mon-Fri 9-5", "Sat 9-1"), info.lines)
        assertEquals("<p>Closed Friday</p>", info.html)
    }

    @Test
    fun `an all-blank map with no htmlUpdated is ignored`() {
        val repo = repo()
        repo.apply(mapOf("heading" to " ", "lines" to "", "html" to ""))
        assertNull(repo.info.value)
        repo.apply(null)
        assertNull(repo.info.value)
    }

    @Test
    fun `a removed banner that still carries a time is applied`() {
        val repo = repo()
        repo.apply(mapOf("heading" to "H", "html" to "<p>x</p>", "htmlUpdated" to 1_000L))
        repo.apply(mapOf("html" to "", "htmlUpdated" to 2_000L))
        assertNull(repo.info.value!!.html)
    }

    @Test
    fun `a pushed banner newer than the map's htmlUpdated is kept`() {
        val repo = repo()
        repo.applyPushed("<p>pushed</p>", 5_000L)
        repo.apply(
            mapOf(
                "heading" to "H",
                "html" to "<p>stale read</p>",
                "htmlUpdated" to 4_000L,
            )
        )
        val info = repo.info.value!!
        assertEquals("<p>pushed</p>", info.html)
        assertEquals("H", info.heading)
    }

    @Test
    fun `a map newer than the held banner replaces it`() {
        val repo = repo()
        repo.applyPushed("<p>pushed</p>", 5_000L)
        repo.apply(mapOf("html" to "<p>newer</p>", "htmlUpdated" to 6_000L))
        assertEquals("<p>newer</p>", repo.info.value!!.html)
    }

    /** A repository already holding a banner at html_at 3000, plus its prefs for inspection. */
    private fun seeded(): Pair<InfoRepository, MemoryPrefs> {
        val prefs = MemoryPrefs()
        val repo = InfoRepository(prefs)
        repo.apply(
            mapOf(
                "heading" to "Cached heading",
                "lines" to "Cached line",
                "html" to "<p>cached</p>",
                "htmlUpdated" to 3_000L,
            )
        )
        return repo to prefs
    }

    private fun assertCacheIntact(repo: InfoRepository, prefs: MemoryPrefs) {
        assertEquals("Cached heading", prefs.getString("heading", null))
        assertEquals("Cached line", prefs.getString("lines", null))
        assertEquals("<p>cached</p>", prefs.getString("html", null))
        assertEquals(3_000L, prefs.getLong("html_at", -1L))
        val info = repo.info.value!!
        assertEquals("Cached heading", info.heading)
        assertEquals(listOf("Cached line"), info.lines)
        assertEquals("<p>cached</p>", info.html)
    }

    @Test
    fun `a non-String html leaves the cache untouched`() {
        val (repo, prefs) = seeded()
        repo.apply(mapOf("html" to 123, "htmlUpdated" to 9_000L))
        assertCacheIntact(repo, prefs)
        repo.apply(mapOf("html" to emptyMap<String, Any>(), "htmlUpdated" to 9_000L))
        assertCacheIntact(repo, prefs)
    }

    @Test
    fun `a non-String heading leaves the cache untouched`() {
        val (repo, prefs) = seeded()
        repo.apply(mapOf("heading" to 5L, "html" to "<p>new</p>", "htmlUpdated" to 9_000L))
        assertCacheIntact(repo, prefs)
    }

    @Test
    fun `a String htmlUpdated leaves the cache untouched`() {
        val (repo, prefs) = seeded()
        repo.apply(mapOf("heading" to "New", "html" to "<p>new</p>", "htmlUpdated" to "9000"))
        assertCacheIntact(repo, prefs)
    }

    @Test
    fun `a fractional htmlUpdated leaves the cache untouched`() {
        val (repo, prefs) = seeded()
        repo.apply(mapOf("heading" to "New", "html" to "<p>new</p>", "htmlUpdated" to 9_000.5))
        assertCacheIntact(repo, prefs)
    }

    @Test
    fun `whole-valued Int and Double htmlUpdated still apply`() {
        val (repo, prefs) = seeded()
        repo.apply(mapOf("html" to "<p>int</p>", "htmlUpdated" to 4_000))
        assertEquals(4_000L, prefs.getLong("html_at", -1L))
        assertEquals("<p>int</p>", repo.info.value!!.html)
        repo.apply(mapOf("html" to "<p>double</p>", "htmlUpdated" to 5_000.0))
        assertEquals(5_000L, prefs.getLong("html_at", -1L))
        assertEquals("<p>double</p>", repo.info.value!!.html)
    }

    @Test
    fun `a map stamped exactly at the stored time is applied`() {
        val (repo, _) = seeded()
        repo.apply(mapOf("html" to "<p>tie</p>", "htmlUpdated" to 3_000L))
        assertEquals("<p>tie</p>", repo.info.value!!.html)
    }

    @Test
    fun `a dispensary map with no topics yields an empty topic list, not null`() {
        // Regression pin for DispensaryConfig.parse, which already behaves this way.
        val d = DispensaryConfig.parse("barc-vashi", mapOf("name" to "BARC Vashi"))!!
        assertTrue(d.topics.isEmpty())
    }
}

/** An in-memory [SharedPreferences], so the repository can run without an Android runtime. */
internal class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        values[key] as? MutableSet<String> ?: defValues

    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value }

        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
