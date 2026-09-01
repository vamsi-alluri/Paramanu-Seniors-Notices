package org.paramanuseniorshealth.notices

import org.paramanuseniorshealth.notices.fcm.NotificationImageStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationImageStorePruneTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun image(dir: File, name: String, modifiedAt: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(8))
            setLastModified(modifiedAt)
        }

    @Test
    fun `keeps the ten newest images and deletes the rest`() {
        val dir = temp.newFolder("notif_images")
        // 15 images, oldest first: base + i means higher i is newer.
        repeat(15) { i -> image(dir, "$i.jpg", 1_000_000L + i * 1_000L) }

        NotificationImageStore.prune(dir, keep = 10)

        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(10, remaining.size)
        // 0..4 are the five oldest and should be gone.
        assertEquals(
            listOf("10.jpg", "11.jpg", "12.jpg", "13.jpg", "14.jpg", "5.jpg", "6.jpg", "7.jpg", "8.jpg", "9.jpg"),
            remaining,
        )
    }

    @Test
    fun `leaves the directory untouched when at or below the limit`() {
        val dir = temp.newFolder("notif_images")
        repeat(10) { i -> image(dir, "$i.jpg", 1_000_000L + i * 1_000L) }

        NotificationImageStore.prune(dir, keep = 10)

        assertEquals(10, dir.listFiles()!!.size)
    }

    @Test
    fun `handles an empty directory`() {
        val dir = temp.newFolder("notif_images")

        NotificationImageStore.prune(dir, keep = 10)

        assertEquals(0, dir.listFiles()!!.size)
    }
}
