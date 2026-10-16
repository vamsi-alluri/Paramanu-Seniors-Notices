package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationLink

/**
 * The activation link is the one entry point an attacker can address directly: MainActivity is
 * exported, so any installed app can hand it an `ACTION_VIEW` intent with a URI of its choosing.
 * Hence the host and path checks here, which the manifest's intent filter does not substitute for.
 */
class ActivationLinkTest {

    @Test
    fun `reads the code from an activation link`() {
        assertEquals(
            "AXGXBBBE",
            ActivationLink.codeFrom("https://paramanuseniorshealth.org/activation?code=AXGXBBBE"),
        )
    }

    @Test
    fun `reads the code when other parameters are present`() {
        assertEquals(
            "AXGXBBBE",
            ActivationLink.codeFrom(
                "https://paramanuseniorshealth.org/activation?from=slip&code=AXGXBBBE&v=2"
            ),
        )
    }

    @Test
    fun `hands back what the link carried, leaving cleaning to the screen`() {
        // CodeDashTransformation.clean is the single cleaning path for typing, pasting and this.
        // Normalising here as well would mean two places that decide what a code looks like.
        assertEquals(
            "axgx-bbbe",
            ActivationLink.codeFrom("https://paramanuseniorshealth.org/activation?code=axgx-bbbe"),
        )
    }

    @Test
    fun `ignores a link from another host`() {
        assertNull(ActivationLink.codeFrom("https://example.com/activation?code=AXGXBBBE"))
    }

    @Test
    fun `ignores a host that merely ends with the real one`() {
        assertNull(
            ActivationLink.codeFrom("https://notparamanuseniorshealth.org/activation?code=AXGXBBBE")
        )
    }

    @Test
    fun `ignores another path on the right host`() {
        assertNull(ActivationLink.codeFrom("https://paramanuseniorshealth.org/notices?code=AXGXBBBE"))
    }

    @Test
    fun `ignores a link with no code parameter`() {
        assertNull(ActivationLink.codeFrom("https://paramanuseniorshealth.org/activation"))
    }

    @Test
    fun `ignores an empty code parameter`() {
        assertNull(ActivationLink.codeFrom("https://paramanuseniorshealth.org/activation?code="))
    }

    @Test
    fun `ignores null and rubbish`() {
        assertNull(ActivationLink.codeFrom(null))
        assertNull(ActivationLink.codeFrom(""))
        assertNull(ActivationLink.codeFrom("not a uri at all"))
    }

    @Test
    fun `accepts a trailing slash on the path`() {
        assertEquals(
            "AXGXBBBE",
            ActivationLink.codeFrom("https://paramanuseniorshealth.org/activation/?code=AXGXBBBE"),
        )
    }

    @Test
    fun `ignores a plain http link`() {
        // The intent filter declares https only; anything else reaching us did not come from it.
        assertNull(ActivationLink.codeFrom("http://paramanuseniorshealth.org/activation?code=AXGXBBBE"))
    }
}
