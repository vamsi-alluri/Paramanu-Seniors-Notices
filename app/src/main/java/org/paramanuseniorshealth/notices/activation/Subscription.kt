package org.paramanuseniorshealth.notices.activation

/**
 * The kinds of message a phone can be signed up for.
 *
 * Each is its own FCM topic, so a user who wants one and not the other genuinely stops receiving
 * the other rather than receiving it and having the app hide it. Each is also its own notification
 * channel, which matters more than it looks: channel importance is fixed at creation and belongs to
 * the user afterwards, so putting the daily status on a separate channel is what lets someone
 * silence the routine chatter from Android's own settings without losing an urgent closure notice.
 */
enum class Subscription(
    val topic: String,
    val channelId: String,
    /** What a phone gets if nobody ever opens Settings. */
    val defaultEnabled: Boolean,
    val preferenceKey: String,
) {
    /**
     * Closures, circulars, timing changes. The reason the app exists, so it is on by default and a
     * user has to deliberately turn it off.
     */
    NOTICES(
        topic = "notices-v1",
        channelId = "notices_default",
        defaultEnabled = true,
        // Named for the original single on/off switch, so phones that already have a value keep it
        // instead of silently reverting to the default on upgrade.
        preferenceKey = "notices_enabled",
    ),

    /**
     * The daily "we are open" / "we are closed" messages, sent by scanning a QR at the counter.
     *
     * Off by default and on a low-importance channel. At roughly sixty messages a month this is the
     * one that would otherwise train people to ignore the app, and being ignored is the failure
     * mode that matters -- an ignored app does not deliver the closure notice either.
     */
    STATUS(
        topic = "status-v1",
        channelId = "notices_status",
        defaultEnabled = false,
        preferenceKey = "status_enabled",
    ),

    /**
     * Delivery checks against real devices in production.
     *
     * Off by default and, unlike the other two, its notification channel is not created until
     * somebody switches it on. Android shows every channel an app has ever created in system
     * settings, so creating this one eagerly would put a "Testing" entry in four hundred people's
     * notification settings for a feature that will never concern them.
     */
    TESTING(
        topic = "testing-v1",
        channelId = "notices_testing",
        defaultEnabled = false,
        preferenceKey = "testing_enabled",
    );

    /** True for subscriptions every user should see in Settings. */
    val isPublic: Boolean get() = this != TESTING

    companion object {
        /** Resolves the `category` field on an incoming message. Unknown values fall back to notices. */
        fun fromCategory(category: String?): Subscription =
            entries.firstOrNull { it.name.equals(category, ignoreCase = true) } ?: NOTICES
    }
}
