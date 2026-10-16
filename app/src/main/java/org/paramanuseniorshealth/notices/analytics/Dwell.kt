package org.paramanuseniorshealth.notices.analytics

/**
 * What the list knew about a notice at the moment its card came on screen.
 *
 * [position] is 1-based among notices and excludes the office-info header, so it is not a
 * `LazyColumn` index. It is captured when the card appears and not revised: a notice arriving
 * above a visible card makes it stale, deliberately, since it is where the reader met the card.
 */
data class CardFacts(val position: Int, val hasImage: Boolean, val hasPdf: Boolean)

/** One continuous stretch a card was on screen. */
data class CardStretch(
    val noticeId: Long,
    val visibleMs: Long,
    val hasImage: Boolean,
    val hasPdf: Boolean,
    val position: Int,
)

/**
 * Turns successive sets of visible list keys into completed stretches.
 *
 * Plain arithmetic with no Android or Compose types, so the sequences that decide the numbers --
 * appear, leave, reappear, flush -- are testable on the JVM. The caller supplies `now` from a
 * monotonic clock; this class never reads one.
 *
 * Only `Long` keys are notices; the office-info header's key is a String and is ignored. A
 * stretch has no upper bound beyond the caller ending it with [flush], so a phone left
 * foregrounded on the list can accrue hours; queries should cap.
 */
class CardDwellTracker {

    private class Entry(val shownAt: Long, val facts: CardFacts)

    private val shown = mutableMapOf<Long, Entry>()

    /**
     * Applies the keys now visible and returns the stretches that ended.
     *
     * [facts] answers for an id still in the list and null for one that is not; an id it does not
     * know is not tracked, and one already tracked that it no longer knows is ended.
     */
    fun update(visibleKeys: List<Any?>, now: Long, facts: (Long) -> CardFacts?): List<CardStretch> {
        val visible = LinkedHashMap<Long, CardFacts>()
        for (key in visibleKeys) {
            val id = key as? Long ?: continue
            facts(id)?.let { visible[id] = it }
        }
        val ended = shown.keys.filter { it !in visible }.map { finish(it, now) }
        for ((id, card) in visible) {
            if (id !in shown) shown[id] = Entry(now, card)
        }
        return ended
    }

    /** Ends every stretch still open and returns them. A second call returns nothing. */
    fun flush(now: Long): List<CardStretch> = shown.keys.toList().map { finish(it, now) }

    private fun finish(id: Long, now: Long): CardStretch {
        val entry = shown.remove(id)!!
        return CardStretch(
            noticeId = id,
            visibleMs = now - entry.shownAt,
            hasImage = entry.facts.hasImage,
            hasPdf = entry.facts.hasPdf,
            position = entry.facts.position,
        )
    }
}

/**
 * Time the image viewer was open *and* the activity started.
 *
 * Call [started] and [stopped] from the lifecycle and [finish] when the viewer is left. Time
 * outside a started segment is not counted, so backgrounding does not read as reading. [clock]
 * should be monotonic. Like [CardDwellTracker] it has no upper bound of its own beyond the
 * started gate, so queries should cap.
 */
class ViewerDwell(private val clock: () -> Long) {

    private var startedAt: Long? = null
    private var total = 0L

    /** Begins a segment. Ignored when one is already running. */
    fun started() {
        if (startedAt == null) startedAt = clock()
    }

    /** Ends the running segment. Ignored when none is running. */
    fun stopped() {
        startedAt?.let { total += clock() - it }
        startedAt = null
    }

    /** The total, including a segment still running. */
    fun finish(): Long {
        stopped()
        return total
    }
}
