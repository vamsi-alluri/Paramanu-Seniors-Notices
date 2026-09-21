package org.paramanuseniorshealth.notices.activation

/**
 * The dispensary a code belongs to, and the topics it offers.
 *
 * Which dispensary is decided by the code: the console writes `/codes/{CODE}/dispensary` when the
 * slip is generated, and the phone reads it. What that dispensary offers lives at
 * `/dispensaries/{id}`, so a dispensary can offer another topic, or rename one, without an app
 * release. Settings is drawn from [topics].
 *
 * This decides what a phone is *offered*, not what it could technically receive. FCM topics cannot
 * be locked down -- any install can subscribe to any topic name it knows -- which is the same
 * cooperative model as revocation, and adequate for the same reason: every notice is public.
 */
data class Dispensary(
    val id: String,
    val name: String,
    /** In the order Settings shows them. */
    val topics: List<DispensaryTopic>,
) {
    fun topic(name: String): DispensaryTopic? = topics.firstOrNull { it.topic == name }

    /**
     * The topic that must be forced back on, or null when there is nothing to force.
     *
     * A dispensary offering exactly one topic is not offering a choice, and Settings draws it as
     * plain text with no switch. A user who had already switched it off -- or who arrives at this
     * build with it off -- would then have no way back and no sign of what happened: the app would
     * simply go quiet. So the sole topic is re-subscribed rather than left as the user last set it.
     *
     * Returns null for two or more topics, where the switches are real and the user's answer stands.
     */
    fun soleTopicToForceOn(choices: Map<String, Boolean>): String? {
        val only = topics.singleOrNull() ?: return null
        val on = choices[only.topic] ?: only.defaultOn
        return only.topic.takeIf { !on }
    }
}

data class DispensaryTopic(
    /** The entry's key under `topics`. Stable across a rename of [label]. */
    val key: String,
    /** The FCM topic name, e.g. `notices-v1`. What the sender publishes to. */
    val topic: String,
    val label: String,
    val explainer: String,
    /** What a phone gets before anybody opens Settings. */
    val defaultOn: Boolean,
    val importance: Importance,
    val order: Int,
)

/**
 * How loudly a topic's notices arrive, and so which notification channel they use.
 *
 * Channels are per importance rather than per topic on purpose. Android lists every channel an app
 * has ever created in system settings and never forgets one, so a channel per topic would let data
 * in the database add permanent entries to four hundred people's notification settings.
 */
enum class Importance { HIGH, LOW }

/** Reading the database's shape, and caching it on the phone. Pure, so both can be tested. */
object DispensaryConfig {

    /**
     * Parses `/dispensaries/{id}` as Firebase hands it over: nested maps, numbers as Long.
     *
     * Returns null when there is no such dispensary, so a failed or empty read never replaces a
     * cached list with nothing. A topic entry without a usable topic name or label is skipped rather
     * than failing the whole list: one badly typed entry must not take the Notices switch with it.
     */
    fun parse(id: String, value: Any?): Dispensary? {
        val node = value as? Map<*, *> ?: return null
        val entries = node["topics"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        val topics = entries.mapNotNull { (key, raw) ->
            val entry = raw as? Map<*, *> ?: return@mapNotNull null
            val topic = (entry["topic"] as? String)?.trim().orEmpty()
            val label = (entry["label"] as? String)?.trim().orEmpty()
            if (label.isEmpty() || !TOPIC_NAME.matches(topic)) return@mapNotNull null

            DispensaryTopic(
                key = key.toString(),
                topic = topic,
                label = label,
                explainer = (entry["explainer"] as? String)?.trim().orEmpty(),
                // Off unless said otherwise. A topic added to the database without deciding this must
                // not start arriving on four hundred phones by default.
                defaultOn = entry["defaultOn"] as? Boolean ?: false,
                importance = if ((entry["importance"] as? String).equals("low", ignoreCase = true)) {
                    Importance.LOW
                } else {
                    Importance.HIGH
                },
                order = (entry["order"] as? Number)?.toInt() ?: Int.MAX_VALUE,
            )
        }.sortedWith(compareBy({ it.order }, { it.key }))

        return Dispensary(id = id, name = (node["name"] as? String)?.trim().orEmpty(), topics = topics)
    }

    /**
     * One line for the dispensary, then one line per topic, fields separated by tabs.
     *
     * Hand-rolled rather than JSON because org.json on the JVM test classpath is Android's stub and
     * throws, and this is small enough not to justify a serialisation library. Tabs and newlines in
     * the text are folded to spaces, which no label or explainer has any business containing.
     */
    fun encode(dispensary: Dispensary): String = buildList {
        add(listOf(dispensary.id, dispensary.name).joinToString(FIELD) { clean(it) })
        dispensary.topics.forEach { t ->
            add(
                listOf(
                    t.key, t.topic, t.label, t.explainer,
                    if (t.defaultOn) "1" else "0", t.importance.name, t.order.toString(),
                ).joinToString(FIELD) { clean(it) }
            )
        }
    }.joinToString(LINE)

    /** The reverse of [encode]. Null for nothing cached, or for anything it cannot read. */
    fun decode(text: String?): Dispensary? {
        if (text.isNullOrBlank()) return null
        val lines = text.split(LINE)
        val head = lines.first().split(FIELD)
        if (head.size != 2 || head[0].isBlank()) return null

        val topics = lines.drop(1).map { line ->
            val f = line.split(FIELD)
            if (f.size != 7) return null
            DispensaryTopic(
                key = f[0],
                topic = f[1],
                label = f[2],
                explainer = f[3],
                defaultOn = f[4] == "1",
                importance = runCatching { Importance.valueOf(f[5]) }.getOrNull() ?: return null,
                order = f[6].toIntOrNull() ?: return null,
            )
        }
        return Dispensary(id = head[0], name = head[1], topics = topics)
    }

    /** What FCM accepts as a topic name. */
    private val TOPIC_NAME = Regex("[a-zA-Z0-9_.~%-]{1,900}")

    private const val FIELD = "\t"
    private const val LINE = "\n"

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
