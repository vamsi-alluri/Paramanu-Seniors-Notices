package org.paramanuseniorshealth.notices.analytics

import kotlin.coroutines.cancellation.CancellationException

/**
 * The kill switch, owned here: the key's default, and the guarantee that nothing is collected
 * unless the switch says to.
 *
 * It reads through [AnalyticsSwitch] rather than touching a store itself. Behind that seam the
 * value lives in Firebase Remote Config, which persists the activated value and answers from local
 * storage, so there is nothing here to cache -- being offline is already handled by the thing
 * holding the value.
 *
 * It is not dispensary-scoped and never was, which is why it is its own parameter rather than a
 * field on the dispensary record: putting it there would force the future multi-dispensary seam to
 * carry it.
 */
class AnalyticsConfig(private val switch: AnalyticsSwitch) {

    /**
     * The value in force. Local and synchronous, so the gate at process start is set from a
     * deterministic value -- on a phone that has never fetched, the `false` default.
     */
    fun cached(): Boolean = switch.enabled()

    /**
     * Fetches the live value and returns what is now in force. On any failure the value already in
     * force stands -- which on a first launch is false, so a phone that has never reached Remote
     * Config collects nothing.
     *
     * Cancellation is passed on rather than treated as a failed read: a caller that gave up on
     * this should stop, not be handed the standing answer as though the fetch had been tried.
     *
     * The failure is swallowed without a word here, for the reason [Analytics] swallows the sink's:
     * this class has no Android imports, so it cannot log -- and keeping it that way is what lets a
     * JVM test reach this branch at all. [FirebaseRemoteConfigSwitch] is the layer that can see
     * what went wrong, and says so there.
     */
    suspend fun refresh(): Boolean = try {
        switch.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        cached()
    }

    companion object {
        /**
         * The Remote Config parameter, Boolean-typed in the console.
         *
         * Its predecessor was a hand-edited RTDB node, where a string "true", a 1 or a deleted node
         * were all realistic and every one of them had to mean off -- which is what the old
         * `parse()` existed for.
         *
         * **Keep the parameter Boolean.** The hazard is not gone, it has moved to the type of the
         * console field, and it has changed direction: `getBoolean` is false for a missing or
         * unparseable value, but it *coerces* -- `1`, `t`, `true`, `y`, `yes` and `on` all read as
         * true. So where a stray string in the old RTDB node failed closed, a String-typed
         * parameter holding `yes` would fail **open**. A Boolean parameter cannot hold any of
         * those; that, and not the SDK's parsing, is what makes the hazard unreachable.
         */
        const val KEY = "analytics_enabled"

        /** Off unless the switch says on, on a phone that has never fetched and after a failure. */
        const val DEFAULT = false
    }
}
