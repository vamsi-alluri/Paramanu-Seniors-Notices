package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationAccentTest {

    @Test
    fun `six digit hex is parsed as opaque`() {
        assertEquals(0xFFFF5722.toInt(), NotificationAccent.parseHex("#FF5722"))
    }

    @Test
    fun `hash prefix is optional`() {
        assertEquals(0xFFFF5722.toInt(), NotificationAccent.parseHex("FF5722"))
    }

    @Test
    fun `eight digit hex keeps its alpha`() {
        assertEquals(0x80FF5722.toInt(), NotificationAccent.parseHex("#80FF5722"))
    }

    @Test
    fun `malformed hex yields null rather than throwing`() {
        // A typo in one service's payload must not drop the notification.
        assertNull(NotificationAccent.parseHex("#ZZZZZZ"))
        assertNull(NotificationAccent.parseHex("#FF57"))
        assertNull(NotificationAccent.parseHex("red"))
        assertNull(NotificationAccent.parseHex(""))
        assertNull(NotificationAccent.parseHex(null))
    }

    @Test
    fun `level parsing is case insensitive and tolerates whitespace`() {
        assertEquals(NotificationAccent.Level.SUCCESS, NotificationAccent.levelOf("success"))
        assertEquals(NotificationAccent.Level.ERROR, NotificationAccent.levelOf("  ERROR "))
        assertNull(NotificationAccent.levelOf("catastrophe"))
        assertNull(NotificationAccent.levelOf(null))
    }

    @Test
    fun `explicit hex takes precedence over level`() {
        assertEquals(
            0xFF123456.toInt(),
            NotificationAccent.trayColor(color = "#123456", level = "error"),
        )
    }

    @Test
    fun `level is used when no hex is given`() {
        assertEquals(
            NotificationAccent.Level.WARNING.trayColor,
            NotificationAccent.trayColor(color = null, level = "warning"),
        )
    }

    @Test
    fun `falls back to the default accent when nothing is supplied`() {
        assertEquals(
            NotificationAccent.DEFAULT_TRAY_COLOR,
            NotificationAccent.trayColor(color = null, level = null),
        )
    }

    @Test
    fun `unparseable hex degrades to the level colour`() {
        assertEquals(
            NotificationAccent.Level.INFO.trayColor,
            NotificationAccent.trayColor(color = "not-a-colour", level = "info"),
        )
    }
}
