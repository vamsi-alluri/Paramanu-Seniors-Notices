package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.RedeemResult
import org.paramanuseniorshealth.notices.activation.analyticsName

class RedeemResultNameTest {

    /** The strings the activation funnel is queried by; changing one splits the data. */
    @Test
    fun eachOutcomeReportsItsPublishedName() {
        assertEquals("success", RedeemResult.Success.analyticsName())
        assertEquals("mistyped", RedeemResult.Mistyped.analyticsName())
        assertEquals("notaccepted", RedeemResult.NotAccepted.analyticsName())
        assertEquals("samecode", RedeemResult.SameCode.analyticsName())
        assertEquals("offline", RedeemResult.Offline.analyticsName())
    }
}
