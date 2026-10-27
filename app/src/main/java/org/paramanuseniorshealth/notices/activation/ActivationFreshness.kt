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

    /**
     * The least time between two status calls from the foreground path.
     *
     * The owner's ceiling is 2M function executions a month, about 165 calls per device per day over
     * ~400 devices, and he does not want to pay. A quarter hour caps a foreground at 96 a day.
     */
    const val CALL_FLOOR_MS: Long = 15L * 60 * 1000

    /**
     * Whether the foreground may make a status call now. A rate limit on the call, nothing more: a
     * refused call leaves whatever was last verified standing.
     *
     * Wall-clock time, like [isStale], so the stamp survives a reboot (an uptime clock restarts at
     * zero and would misjudge a stamp from before it). Neither awkward case can wedge it: a stamp
     * in the future means the clock moved back, so the call is allowed and re-stamps with the
     * current time; a clock jumped forward opens the gate for one call, after which the new stamp
     * holds it again.
     */
    fun shouldCall(lastCalledAt: Long, now: Long): Boolean {
        if (lastCalledAt <= 0L) return true
        if (lastCalledAt > now) return true
        return now - lastCalledAt >= CALL_FLOOR_MS
    }

    fun isStale(lastVerifiedAt: Long, now: Long): Boolean {
        if (lastVerifiedAt <= 0L) return true
        // A timestamp in the future means the clock moved, not that the answer is fresh. Trusting
        // it would stop the device ever checking again -- and this check is the only thing that
        // ever notices a code has been restored.
        if (lastVerifiedAt > now) return true
        return now - lastVerifiedAt >= STALE_AFTER_MS
    }
}
