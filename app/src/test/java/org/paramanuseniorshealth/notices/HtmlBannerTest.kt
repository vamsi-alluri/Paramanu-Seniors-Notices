package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.ui.HtmlBanner

class HtmlBannerTest {

    /** The banner the NGO actually sent, pasted out of Gmail, trimmed to the interesting parts. */
    private val realBanner = """
        <p><b><span style="font-size:14pt;line-height:120%;color:#3f51b5">BARC Vashi Dispensary:</span></b>
        Plot No 2, Sect <a href="https://www.google.com/maps/search/9A,+Vashi">9A, Vashi</a>.
        <b><span style="background:#ffc107">Tel: 022-2766 3985</span></b>
        Email: <a href="mailto:disp10@barc.gov.in">disp10@barc.gov.in</a>
        <b><span style="font-size:14pt;font-family:'roboto bold';background-color:#ffeb3b">Timings</span></b>
        <u><b>Sat</b>: Only for employees</u></p>
    """.trimIndent()

    @Test
    fun `keeps the formatting that carries meaning`() {
        val out = HtmlBanner.sanitise(realBanner)
        assertTrue("bold kept", out.contains("<b>"))
        assertTrue("underline kept", out.contains("<u>"))
        assertTrue("text colour kept", out.contains("color:#3f51b5"))
        assertTrue("highlight kept", out.contains("background-color:#ffeb3b"))
    }

    @Test
    fun `the background shorthand is kept and normalised`() {
        // Gmail writes `background:`, Word writes `background-color:`, and the NGO's banner has
        // both. Accepting only the long form silently dropped the amber highlight on the phone
        // numbers, which is the formatting most worth keeping.
        val out = HtmlBanner.sanitise("""<span style="background:#ffc107">Tel: 022-2766 3985</span>""")
        assertTrue("shorthand kept", out.contains("background-color:#ffc107"))
        assertFalse("not left as the shorthand", out.contains("style=\"background:"))
    }

    @Test
    fun `both highlight spellings survive the real banner`() {
        val out = HtmlBanner.sanitise(realBanner)
        assertTrue("amber from the shorthand", out.contains("background-color:#ffc107"))
        assertTrue("yellow from the long form", out.contains("background-color:#ffeb3b"))
    }

    @Test
    fun `a background that is not a plain colour is dropped`() {
        val out = HtmlBanner.sanitise("""<span style="background:url(http://x/y.png) no-repeat">Hi</span>""")
        assertFalse(out.contains("url("))
        assertFalse(out.contains("background"))
        assertTrue(out.contains("Hi"))
    }

    @Test
    fun `colour values are accepted in the forms authors actually use`() {
        assertTrue(HtmlBanner.sanitise("""<span style="color:#f44336">x</span>""").contains("color:#f44336"))
        assertTrue(HtmlBanner.sanitise("""<span style="color:red">x</span>""").contains("color:red"))
    }

    @Test
    fun `rgb notation is rewritten to hex`() {
        // Android's HTML-to-Spanned converter reads #hex and colour names and nothing else. Handed
        // an rgb() value it produces no span at all and the colour vanishes with no error, which is
        // exactly why the first real banner rendered entirely in black.
        assertTrue(
            HtmlBanner.sanitise("""<span style="color:rgb(63, 81, 181)">x</span>""")
                .contains("color:#3f51b5")
        )
        assertTrue(
            HtmlBanner.sanitise("""<span style="background-color:rgb(255, 193, 7)">x</span>""")
                .contains("background-color:#ffc107")
        )
    }

    @Test
    fun `rgba drops the alpha rather than approximating it`() {
        assertTrue(
            HtmlBanner.sanitise("""<span style="background:rgba(255, 235, 59, 0.5)">x</span>""")
                .contains("background-color:#ffeb3b")
        )
    }

    @Test
    fun `no rgb notation survives into the output`() {
        // The parser downstream cannot read it, so its presence anywhere means a lost colour.
        val out = HtmlBanner.sanitise(storedBanner)
        assertFalse("rgb() left in the output", out.contains("rgb("))
        assertTrue("indigo converted", out.contains("color:#3f51b5"))
        assertTrue("amber converted", out.contains("background-color:#ffc107"))
        assertTrue("yellow converted", out.contains("background-color:#ffeb3b"))
    }

    /** Exactly what the console stored after the NGO's banner was pasted from Gmail. */
    private val storedBanner = """
        <b><span style="color:rgb(63, 81, 181)">BARC Vashi Dispensary:</span></b> Plot No 2, Sect 9A, Vashi.
        <b><span style="background-color:rgb(255, 193, 7)">Tel: 022-2766 3985, Cell: 8591108329.</span></b>
        Email: <a href="mailto:disp10@barc.gov.in">disp10@barc.gov.in</a>&nbsp;
        <b><span style="background-color:rgb(255, 235, 59)">Timings: Mon-Sat 8 AM to 3 PM.</span></b>
        <u><b>Sat</b>: Only for employees &amp; their families.</u>
    """.trimIndent()

    @Test
    fun `drops sizes and fonts so the system font setting still wins`() {
        val out = HtmlBanner.sanitise(realBanner)
        assertFalse("font-size dropped", out.contains("font-size"))
        assertFalse("font-family dropped", out.contains("font-family"))
        assertFalse("line-height dropped", out.contains("line-height"))
    }

    @Test
    fun `keeps safe links and their text`() {
        val out = HtmlBanner.sanitise(realBanner)
        assertTrue("https kept", out.contains("href=\"https://www.google.com/maps/search/9A,+Vashi\""))
        assertTrue("mailto kept", out.contains("href=\"mailto:disp10@barc.gov.in\""))
        assertTrue("link text survives", out.contains("disp10@barc.gov.in"))
    }

    @Test
    fun `an unsafe link becomes plain text rather than vanishing`() {
        val out = HtmlBanner.sanitise("""Call <a href="javascript:alert(1)">the desk</a> now""")
        assertFalse(out.contains("javascript"))
        assertFalse(out.contains("<a"))
        // The words must survive: losing the sentence is worse than losing the link.
        assertTrue(out.contains("the desk"))
    }

    @Test
    fun `scripts and their contents are removed entirely`() {
        val out = HtmlBanner.sanitise("""Hours <script>steal()</script><SCRIPT >x()</SCRIPT> today""")
        assertFalse(out.contains("steal"))
        assertFalse(out.contains("x()"))
        assertTrue(out.contains("Hours"))
        assertTrue(out.contains("today"))
    }

    @Test
    fun `event handlers cannot survive because the attribute is dropped`() {
        val out = HtmlBanner.sanitise("""<span onclick="steal()" style="color:#ff0000">Tel</span>""")
        assertFalse(out.contains("onclick"))
        assertTrue(out.contains("color:#ff0000"))
        assertTrue(out.contains("Tel"))
    }

    @Test
    fun `Word and Gmail debris is stripped`() {
        val out = HtmlBanner.sanitise(
            """<p class="MsoNormal" style="mso-line-height-alt:12pt;color:#111"><o:p>x</o:p>Hours</p>"""
        )
        assertFalse(out.contains("MsoNormal"))
        assertFalse(out.contains("mso-"))
        assertFalse(out.contains("<o:p>"))
        assertTrue(out.contains("color:#111"))
        assertTrue(out.contains("Hours"))
    }

    @Test
    fun `disallowed tags go but their text stays`() {
        val out = HtmlBanner.sanitise("<table><tr><td>Mon-Sat 8 AM</td></tr></table>")
        assertFalse(out.contains("<table"))
        assertFalse(out.contains("<td"))
        assertTrue(out.contains("Mon-Sat 8 AM"))
    }

    @Test
    fun `comments are removed`() {
        assertFalse(HtmlBanner.sanitise("<!-- hidden --><b>Open</b>").contains("hidden"))
    }

    @Test
    fun `line breaks are normalised`() {
        val out = HtmlBanner.sanitise("""Line one<br/><BR />Line two""")
        assertEquals(2, Regex("<br>").findAll(out).count())
    }

    @Test
    fun `sanitising twice changes nothing further`() {
        // The console sanitises on save and the app sanitises again on read; the second pass must
        // not corrupt what the first produced.
        val once = HtmlBanner.sanitise(realBanner)
        assertEquals(once, HtmlBanner.sanitise(once))
    }

    @Test
    fun `hasContent sees through markup`() {
        assertTrue(HtmlBanner.hasContent("<p><b>Open today</b></p>"))
        assertFalse(HtmlBanner.hasContent("<p><b></b></p>"))
        assertFalse(HtmlBanner.hasContent("<p>&nbsp;</p>"))
        assertFalse(HtmlBanner.hasContent(""))
        assertFalse(HtmlBanner.hasContent(null))
    }
}
