package org.paramanuseniorshealth.notices.fcm

/**
 * The topics control messages arrive on, one family per audience. Must match the prefixes in
 * functions/src/control.ts, which sends them from database triggers. See docs/decisions.md, "Topics
 * are split by audience".
 *
 * A version means one envelope and one meaning, so changing either is a new version, never an edit.
 * `control-v1`, which carried revoke, resume and banner to every phone at once, is retired, and a
 * phone leaves it at its next subscription sync. The function still copies each disable, enable and
 * banner there for older builds until the database rules close; a message that reaches this build
 * both ways carries the same stamp and is applied once.
 */
object ControlTopics {

    /**
     * `configStale`, sent by functions/src/config.ts on every Remote Config publish, so a kill or a
     * version floor reaches phones in minutes instead of at their next six-hourly fetch. Held by
     * every install, claimed or not, and kept while the app gate is closed: it is how the gate is
     * lifted too.
     */
    const val CONFIG = "control-v2"

    private const val DEVICE_PREFIX = "device-v1-"
    private const val BANNER_PREFIX = "banner-v1-"

    // FCM's topic grammar. A name outside it makes subscribeToTopic throw, so it is refused here
    // rather than retried on every launch.
    private val TOPIC_SAFE = Regex("[A-Za-z0-9_.~%-]{1,200}")

    /**
     * Revoke and resume for this install alone, addressed by the UID that holds the claim. The UID is
     * used exactly as Firebase issued it: topics are case-sensitive, and the function builds the name
     * from the same `usedBy` value. A reinstall has a new UID and so a new topic, which is what keeps
     * the next holder of an unlinked code from receiving the old phone's revoke.
     */
    fun device(uid: String): String? = (DEVICE_PREFIX + uid).takeIf { TOPIC_SAFE.matches(uid) }

    /** [dispensary]'s banner, for the phones whose code names that dispensary. */
    fun banner(dispensary: String): String? =
        (BANNER_PREFIX + dispensary).takeIf { TOPIC_SAFE.matches(dispensary) }
}
