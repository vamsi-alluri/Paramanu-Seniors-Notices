package org.paramanuseniorshealth.notices

/**
 * Resolves the accent colour for a message.
 *
 * Two palettes exist because a tray notification is drawn by the system and cannot read the Compose
 * theme: [trayColor] returns fixed ARGB for `NotificationCompat.setColor`, while the UI resolves a
 * theme-aware equivalent. Both are derived from the same hues so a message looks consistent in the
 * shade and in the list.
 *
 * Precedence is always explicit hex, then semantic level, then the app default.
 */
object NotificationAccent {

    enum class Level(val trayColor: Int, val lightColor: Int, val darkColor: Int) {
        INFO(0xFF2196F3.toInt(), 0xFF1976D2.toInt(), 0xFF64B5F6.toInt()),
        SUCCESS(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt(), 0xFF81C784.toInt()),
        WARNING(0xFFFF9800.toInt(), 0xFFE65100.toInt(), 0xFFFFB74D.toInt()),
        ERROR(0xFFF44336.toInt(), 0xFFC62828.toInt(), 0xFFE57373.toInt()),
    }

    /** Default tray accent when the sender specifies neither a colour nor a level. */
    val DEFAULT_TRAY_COLOR = 0xFF6650A4.toInt()

    fun levelOf(level: String?): Level? = level?.trim()?.uppercase()?.let { name ->
        Level.entries.firstOrNull { it.name == name }
    }

    private val HEX = Regex("[0-9a-fA-F]+")

    /**
     * Parses `#RRGGBB` / `#AARRGGBB`. Malformed values yield null rather than throwing, so a typo in
     * one service's payload degrades to the level colour instead of dropping the notification.
     *
     * Hand-rolled rather than delegating to `android.graphics.Color.parseColor`, which is a stub
     * under JVM unit tests and would force this logic onto an emulator to verify.
     */
    fun parseHex(hex: String?): Int? {
        val value = hex?.trim()?.removePrefix("#") ?: return null
        if (!HEX.matches(value)) return null
        return when (value.length) {
            6 -> value.toLong(16).or(0xFF000000L).toInt()   // opaque by default
            8 -> value.toLong(16).toInt()
            else -> null
        }
    }

    /** ARGB accent for the system tray notification. */
    fun trayColor(color: String?, level: String?): Int =
        parseHex(color) ?: levelOf(level)?.trayColor ?: DEFAULT_TRAY_COLOR
}
