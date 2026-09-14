package org.paramanuseniorshealth.notices.data

/**
 * When a notice was sent, as opposed to when this phone happened to receive it.
 *
 * The sender's `logId` is epoch millis taken at send time, so it is the same instant on every phone.
 * Arrival time is not: a phone that was switched off overnight, or sat on a weak signal, stamps the
 * notice hours late, and two people comparing the same notice saw two different times. Both the list
 * and the tray notification use this, so they agree with each other and with everyone else's phone.
 *
 * Anything unparseable -- a `local-` id written on the phone itself, a UUID, an ISO string -- falls
 * back to [now], because there is no sender clock to prefer.
 */
object NoticeTime {

    fun sentAt(logId: String?, now: Long): Long {
        val numeric = logId?.trim()?.toLongOrNull() ?: return now
        return when {
            numeric > 100_000_000_000L -> numeric          // already millis
            numeric > 100_000_000L -> numeric * 1000L      // seconds
            else -> now
        }
    }
}
