package org.paramanuseniorshealth.notices

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig

class AnalyticsConfigCacheTest {

    @Test
    fun `a phone that has never reached the node reads off`() {
        // The whole of "collection defaults to off" on a first launch rests on this default.
        assertFalse(AnalyticsConfig(InMemoryPrefs()).cached())
    }

    @Test
    fun `a stored true is read back as on`() {
        val config = AnalyticsConfig(InMemoryPrefs())
        config.store(true)
        assertTrue(config.cached())
    }

    @Test
    fun `a stored false turns it off again`() {
        val config = AnalyticsConfig(InMemoryPrefs())
        config.store(true)
        config.store(false)
        assertFalse(config.cached())
    }
}

/**
 * Supports only what [AnalyticsConfig] calls. Everything else throws, so a test can never pass
 * because the fake answered something nobody designed it to answer.
 */
private class InMemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Boolean>()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Boolean>()

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun apply() { values.putAll(pending) }
        override fun commit(): Boolean { values.putAll(pending); return true }

        override fun putString(key: String, value: String?) = unsupported()
        override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
        override fun putInt(key: String, value: Int) = unsupported()
        override fun putLong(key: String, value: Long) = unsupported()
        override fun putFloat(key: String, value: Float) = unsupported()
        override fun remove(key: String) = unsupported()
        override fun clear() = unsupported()
    }

    override fun getAll(): MutableMap<String, *> = unsupported()
    override fun getString(key: String, defValue: String?) = unsupported()
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = unsupported()
    override fun getInt(key: String, defValue: Int) = unsupported()
    override fun getLong(key: String, defValue: Long) = unsupported()
    override fun getFloat(key: String, defValue: Float) = unsupported()
    override fun contains(key: String) = unsupported()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = unsupported()
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
