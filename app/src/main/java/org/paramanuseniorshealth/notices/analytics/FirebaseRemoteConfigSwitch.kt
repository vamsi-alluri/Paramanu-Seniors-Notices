package org.paramanuseniorshealth.notices.analytics

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.paramanuseniorshealth.notices.BuildConfig
import org.paramanuseniorshealth.notices.activation.CodeManagementBaseUrl
import org.paramanuseniorshealth.notices.activation.CodeManagementConfig
import org.paramanuseniorshealth.notices.data.awaitResult
import org.paramanuseniorshealth.notices.update.AppGate
import org.paramanuseniorshealth.notices.update.AppGateSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only file in the app that imports Remote Config.
 *
 * It replaces an RTDB read of `config/analytics/enabled`. That read opened the realtime socket on
 * every foreground and the SDK then held the connection for about a minute of idleness, against a
 * Spark plan that allows a hundred at once across every handset -- a capacity cost for a value that
 * changes a few times a year. Remote Config fetches over HTTPS with no connection ceiling, and
 * answers [enabled] from local storage with no network at all.
 */
class FirebaseRemoteConfigSwitch(context: Context) : AnalyticsSwitch, CodeManagementBaseUrl, AppGateSource {

    // Resolved on first use rather than at construction, so a failure to obtain the instance is
    // caught by the same guard as every other call instead of escaping the constructor.
    private val remoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The version floor, from local storage. Zero on any failure to read, so a broken read never
     * locks anyone out.
     */
    override fun minVersionSupported(): Long = try {
        remoteConfig.getLong(AppGate.KEY_MIN_VERSION)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the version floor: ${e.message}")
        AppGate.DEFAULT_MIN_VERSION
    }

    /** The whole-app kill, from local storage. False on any failure to read. */
    override fun killed(): Boolean = try {
        remoteConfig.getBoolean(AppGate.KEY_KILLED)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the kill switch: ${e.message}")
        AppGate.DEFAULT_KILLED
    }

    override fun markStale() {
        prefs.edit().putBoolean(KEY_STALE, true).apply()
    }

    /** Whether a fetch is still owed after a stale mark: the config refresh worker retries on it. */
    val isStale: Boolean get() = prefs.getBoolean(KEY_STALE, false)

    /**
     * The activated value, held by Remote Config in local storage. Unparseable or absent reads as
     * false, which is the same answer [AnalyticsConfig.DEFAULT] gives.
     */
    override fun enabled(): Boolean = try {
        remoteConfig.getBoolean(AnalyticsConfig.KEY)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the switch, treating it as off: ${e.message}")
        AnalyticsConfig.DEFAULT
    }

    /**
     * The `code_management` base URL, from the same local storage and the same fetch as the switch.
     * Kept in this file because it is the only one allowed to import Remote Config; the key rides
     * the fetch [refresh] already makes, so it costs no extra request. Blank until published, and
     * blank on any failure to read, both of which mean "make no call".
     */
    override fun baseUrl(): String = try {
        remoteConfig.getString(CodeManagementConfig.KEY)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the code_management base URL: ${e.message}")
        CodeManagementConfig.DEFAULT
    }

    /**
     * Fetches, activates, and answers with what is now in force.
     *
     * A failure is logged and rethrown: this is the only layer with an Android import, so it is
     * the only one that can say what went wrong, while the rule about what a failure *means* --
     * the standing value stands -- belongs to [AnalyticsConfig.refresh] and is tested there.
     * Swallowing it here would make that catch unreachable.
     *
     * The settings and the default are applied on the way in rather than at construction. They are
     * local writes, and applying them here is what guarantees the fetch interval is in force before
     * the first fetch of the process -- on a debug build that interval is zero, and a fetch that
     * raced ahead of it would be throttled for hours instead.
     *
     * After [markStale] the fetch ignores the interval, once: the stale mark is cleared only when
     * that fetch succeeds, so a failed one is owed again on the next call.
     */
    override suspend fun refresh(): Boolean = try {
        remoteConfig.setConfigSettingsAsync(SETTINGS).awaitResult()
        remoteConfig.setDefaultsAsync(DEFAULTS).awaitResult()
        if (isStale) {
            remoteConfig.fetch(0).awaitResult()
            remoteConfig.activate().awaitResult()
            prefs.edit().putBoolean(KEY_STALE, false).apply()
        } else {
            remoteConfig.fetchAndActivate().awaitResult()
        }
        enabled()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Could not fetch the analytics switch: ${e.message}")
        throw e
    }

    private companion object {
        const val TAG = "RemoteConfigSwitch"
        const val PREFS = "remote_config"
        const val KEY_STALE = "stale"

        /** Every key this file reads, so a device that has never fetched gets a value, not a missing key. */
        val DEFAULTS: Map<String, Any> = mapOf(
            AnalyticsConfig.KEY to AnalyticsConfig.DEFAULT,
            CodeManagementConfig.KEY to CodeManagementConfig.DEFAULT,
            AppGate.KEY_MIN_VERSION to AppGate.DEFAULT_MIN_VERSION,
            AppGate.KEY_KILLED to AppGate.DEFAULT_KILLED,
        )

        /**
         * Six hours in release: the owner's tolerance for a switch change reaching devices is
         * hours, not milliseconds, and inside the window `fetchAndActivate` answers from local
         * storage with no network -- which is what makes re-reading the switch on every foreground
         * free, and why no throttling of our own is needed at the call site.
         *
         * Zero on a debug build, because the on-device verification walk has to see a published
         * change immediately rather than six hours later.
         */
        val FETCH_INTERVAL_SECONDS: Long = if (BuildConfig.DEBUG) 0 else 6 * 60 * 60

        val SETTINGS: FirebaseRemoteConfigSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
            .build()
    }
}
