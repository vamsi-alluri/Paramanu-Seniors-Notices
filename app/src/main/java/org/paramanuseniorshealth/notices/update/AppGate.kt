package org.paramanuseniorshealth.notices.update

/**
 * Whether this build may run at all. Decided from two Remote Config values and nothing else.
 *
 * - [Killed]: the whole app is switched off, every version. A screen with no way past it, no
 *   notifications, and no notice topics. For the day the service has to stop -- a compromised
 *   build, the programme ending -- and not for a bug, which [Unsupported] covers.
 * - [Unsupported]: this version is below the floor. A screen with no way past it that points at
 *   the Play listing, and no notifications. For a build broken badly enough that it must not run.
 * - [Open]: everything else. How hard to nudge an update that is merely available is Play's
 *   per-release priority, not this gate; see [UpdatePrompt].
 *
 * Both closed states lift themselves when the value is corrected: nothing is remembered on the
 * phone beyond Remote Config's own activated values, which a later fetch replaces.
 */
sealed interface AppGate {
    data object Open : AppGate
    data object Unsupported : AppGate
    data object Killed : AppGate

    companion object {
        /**
         * Number-typed in the console: a versionCode, never a versionName, which as text sorts
         * 0.8.10 below 0.8.9. Builds below it are [Unsupported].
         */
        const val KEY_MIN_VERSION = "min_version_supported"

        /**
         * Boolean-typed in the console, and it must stay so. `getBoolean` coerces a String-typed
         * value, so `yes`, `on` or `1` typed into a String parameter would kill every phone.
         */
        const val KEY_KILLED = "app_killed"

        /** A phone that has never fetched, or cannot, is never locked out by a missing value. */
        const val DEFAULT_MIN_VERSION = 0L
        const val DEFAULT_KILLED = false

        /** Killed wins: a dead service is dead whatever the version. */
        fun decide(installedVersion: Long, minVersion: Long, killed: Boolean): AppGate = when {
            killed -> Killed
            installedVersion < minVersion -> Unsupported
            else -> Open
        }
    }
}

/** Where the gate's two values are read from. Remote Config in the app; a fake in the tests. */
interface AppGateSource {
    /** The activated values, read locally: no network, safe from the message path. */
    fun minVersionSupported(): Long
    fun killed(): Boolean

    /**
     * Asks that the next fetch ignore the minimum fetch interval. Set by a `configStale` push and by
     * a closed gate, so a change -- including the one that lifts a gate -- is not held back six hours.
     */
    fun markStale()
}
