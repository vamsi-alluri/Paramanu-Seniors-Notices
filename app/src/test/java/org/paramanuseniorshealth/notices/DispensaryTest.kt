package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.Dispensary
import org.paramanuseniorshealth.notices.activation.DispensaryConfig
import org.paramanuseniorshealth.notices.activation.DispensaryTopic
import org.paramanuseniorshealth.notices.activation.Importance

class DispensaryTest {

    /** Shaped as Firebase hands it over: nested maps, numbers as Long, booleans as Boolean. */
    private val barcVashi = mapOf(
        "name" to "BARC Vashi Dispensary",
        "topics" to mapOf(
            "notices" to mapOf(
                "topic" to "notices-v1",
                "label" to "Notices",
                "explainer" to "Closures and circulars.",
                "defaultOn" to true,
                "importance" to "high",
                "order" to 1L,
            ),
            "reminders" to mapOf(
                "topic" to "reminders-v1",
                "label" to "Reminders",
                "importance" to "low",
                "order" to 0L,
            ),
        ),
        "info" to mapOf("heading" to "Dispensary timings"),
    )

    @Test
    fun `reads the name and the topics in order`() {
        val d = DispensaryConfig.parse("barc-vashi", barcVashi)!!
        assertEquals("barc-vashi", d.id)
        assertEquals("BARC Vashi Dispensary", d.name)
        assertEquals(listOf("reminders-v1", "notices-v1"), d.topics.map { it.topic })
    }

    @Test
    fun `reads each field of a topic`() {
        val notices = DispensaryConfig.parse("barc-vashi", barcVashi)!!.topic("notices-v1")!!
        assertEquals("notices", notices.key)
        assertEquals("Notices", notices.label)
        assertEquals("Closures and circulars.", notices.explainer)
        assertTrue(notices.defaultOn)
        assertEquals(Importance.HIGH, notices.importance)
        assertEquals(1, notices.order)
    }

    @Test
    fun `a topic that does not say is off by default, and low only when it says so`() {
        // A topic added to the database without deciding must not arrive on every phone.
        val reminders = DispensaryConfig.parse("barc-vashi", barcVashi)!!.topic("reminders-v1")!!
        assertFalse(reminders.defaultOn)
        assertEquals(Importance.LOW, reminders.importance)
        assertEquals("", reminders.explainer)
    }

    @Test
    fun `a badly typed entry is skipped rather than failing the whole list`() {
        val d = DispensaryConfig.parse(
            "x",
            mapOf(
                "topics" to mapOf(
                    "good" to mapOf("topic" to "notices-v1", "label" to "Notices"),
                    "noLabel" to mapOf("topic" to "a-v1"),
                    "noTopic" to mapOf("label" to "Nothing"),
                    "badName" to mapOf("topic" to "has spaces", "label" to "Bad"),
                    "notAMap" to "notices-v1",
                )
            ),
        )!!
        assertEquals(listOf("notices-v1"), d.topics.map { it.topic })
    }

    @Test
    fun `a topic with no order goes last`() {
        val d = DispensaryConfig.parse(
            "x",
            mapOf(
                "topics" to mapOf(
                    "later" to mapOf("topic" to "b-v1", "label" to "B"),
                    "first" to mapOf("topic" to "a-v1", "label" to "A", "order" to 5L),
                )
            ),
        )!!
        assertEquals(listOf("a-v1", "b-v1"), d.topics.map { it.topic })
    }

    @Test
    fun `no dispensary is null, so a failed read never empties the cached list`() {
        assertNull(DispensaryConfig.parse("x", null))
        assertNull(DispensaryConfig.parse("x", "not a map"))
    }

    @Test
    fun `a dispensary with no topics is still a dispensary`() {
        val d = DispensaryConfig.parse("x", mapOf("name" to "Empty"))!!
        assertTrue(d.topics.isEmpty())
    }

    @Test
    fun `the cache reads back exactly what was written`() {
        val d = DispensaryConfig.parse("barc-vashi", barcVashi)!!
        assertEquals(d, DispensaryConfig.decode(DispensaryConfig.encode(d)))
    }

    @Test
    fun `tabs and newlines in text cannot break the cache`() {
        val d = Dispensary(
            id = "x",
            name = "Two\tparts",
            topics = listOf(
                DispensaryTopic("k", "a-v1", "Line\none", "Tab\there", true, Importance.LOW, 2)
            ),
        )
        val back = DispensaryConfig.decode(DispensaryConfig.encode(d))!!
        assertEquals("Two parts", back.name)
        assertEquals("Line one", back.topics.single().label)
        assertEquals("Tab here", back.topics.single().explainer)
        assertEquals(Importance.LOW, back.topics.single().importance)
    }

    @Test
    fun `nothing cached or something unreadable decodes to null`() {
        assertNull(DispensaryConfig.decode(null))
        assertNull(DispensaryConfig.decode(""))
        assertNull(DispensaryConfig.decode("x\tname\nonly\tthree\tfields"))
    }

    private fun dispensaryOf(vararg topics: DispensaryTopic) =
        Dispensary(id = "x", name = "X", topics = topics.toList())

    private fun topic(name: String, defaultOn: Boolean = true) =
        DispensaryTopic(name, name, name, "", defaultOn, Importance.HIGH, 0)

    @Test
    fun `a sole topic that is off must be forced back on`() {
        val d = dispensaryOf(topic("notices-v1"))
        assertEquals("notices-v1", d.soleTopicToForceOn(mapOf("notices-v1" to false)))
    }

    @Test
    fun `a sole topic that is already on needs no forcing`() {
        val d = dispensaryOf(topic("notices-v1"))
        assertNull(d.soleTopicToForceOn(mapOf("notices-v1" to true)))
    }

    /** No stored choice means the default applies, and a default of on is already on. */
    @Test
    fun `a sole topic with no stored choice falls back to its default`() {
        assertNull(dispensaryOf(topic("a-v1", defaultOn = true)).soleTopicToForceOn(emptyMap()))
        assertEquals(
            "a-v1",
            dispensaryOf(topic("a-v1", defaultOn = false)).soleTopicToForceOn(emptyMap()),
        )
    }

    /** With a real choice on offer, the user's answer stands -- including "all of them off". */
    @Test
    fun `two topics are never forced`() {
        val d = dispensaryOf(topic("a-v1"), topic("b-v1"))
        assertNull(d.soleTopicToForceOn(mapOf("a-v1" to false, "b-v1" to false)))
    }

    @Test
    fun `a dispensary offering no topics forces nothing`() {
        assertNull(dispensaryOf().soleTopicToForceOn(emptyMap()))
    }
}
