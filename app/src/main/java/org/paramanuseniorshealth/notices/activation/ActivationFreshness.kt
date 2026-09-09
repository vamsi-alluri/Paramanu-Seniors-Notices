package org.paramanuseniorshealth.notices.activation

/**
 * How old a verification may be before the device asks the server again.
 *
 * Pure and separate from the repository so it can be tested without Android.
 *
 * The window is a day. It used to be an hour, back when verification was how a revocation arrived
 * at all; now a revoke is pushed and lands within seconds, so this only has to catch a device that
 * was switched off when the broadcast went out. Narrowing it again would put ~400 simultaneous
 * database connections back on every broadcast, against a Spark cap of 100. See docs/decisions.md.
 */
object ActivationFreshness {

    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    fun isStale(lastVerifiedAt: Long, now: Long): Boolean {
        if (lastVerifiedAt <= 0L) return true
        // A timestamp in the future means the clock moved, not that the answer is fresh. Trusting
        // it would stop the device ever checking again -- and this check is the only thing that
        // ever notices a code has been restored.
        if (lastVerifiedAt > now) return true
        return now - lastVerifiedAt >= STALE_AFTER_MS
    }
}
