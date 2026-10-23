package org.paramanuseniorshealth.notices.analytics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gate every measurement passes through.
 *
 * **Off is the default and the safe failure.** A missing config node, an unreadable one, or simply
 * the moment before the first read completes all mean no collection -- the opposite default would
 * make a network failure into a privacy failure.
 *
 * [setEnabled] drives the Firebase SDK's own master switch as well as this gate. Guarding only our
 * own calls would leave GA4's automatic events (sessions, screen views) flowing while the app
 * reported itself as not collecting.
 *
 * Failures from the sink are swallowed without a log line. `android.util.Log` is not on the JVM
 * unit-test classpath and would itself throw there, and this class is deliberately Android-free.
 * Reporting those failures is the sink implementation's job: it may import Android, this class
 * may not.
 */
class Analytics(private val sink: AnalyticsSink) {

    private val enabled = AtomicBoolean(false)

    /**
     * Never throws. The gate flips even when the SDK call fails: the dangerous direction is failing
     * to turn collection off, and our own events must stop regardless.
     *
     * Synchronized so two writers (the cached value applied at startup and the live one arriving
     * later) cannot interleave and leave the gate and the SDK flag disagreeing.
     */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled.set(value)
        runCatching { sink.setCollectionEnabled(value) }
    }

    /**
     * Never throws. Analytics must not be able to break the thing it is measuring, and every call
     * site here sits on a path -- message receipt, a fetch, a tap -- where an exception would be a
     * real failure for the user.
     */
    fun log(event: AnalyticsEvent) {
        if (!enabled.get()) return
        runCatching { sink.log(event.name, event.params) }
    }
}
