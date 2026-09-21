package org.paramanuseniorshealth.notices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import java.io.File

class PruneTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Distinct mtimes, oldest first, so "newest kept" is unambiguous. */
    private fun write(name: String, bytes: Int, ageMillis: Long): File =
        folder.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }

    @Test
    fun `keeps the newest files within budget and deletes the rest`() {
        val newest = write("c.jpg", 400, 1_000)
        val middle = write("b.jpg", 400, 2_000)
        val oldest = write("a.jpg", 400, 3_000)

        NoticeImageStore.pruneTo(folder.root, budgetBytes = 900)

        assertTrue(newest.exists())
        assertTrue(middle.exists())
        assertFalse(oldest.exists())
    }

    @Test
    fun `a directory already within budget is untouched`() {
        val only = write("a.jpg", 100, 1_000)
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 1_000)
        assertTrue(only.exists())
    }

    /**
     * The newest file is kept even alone over budget. Deleting it would mean a notice that had
     * just arrived was pruned before it could be read.
     */
    @Test
    fun `the newest file survives even when it alone exceeds the budget`() {
        val huge = write("a.jpg", 5_000, 1_000)
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 100)
        assertTrue(huge.exists())
    }

    @Test
    fun `an empty or missing directory is a no-op`() {
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 100)
        NoticeImageStore.pruneTo(File(folder.root, "absent"), budgetBytes = 100)
    }

    /**
     * The bug this pins: a running total that keeps counting bytes it has already deleted will take
     * the older small file with it, though the survivors are nowhere near the budget.
     */
    @Test
    fun `a small older file survives an oversized newer neighbour`() {
        val newest = write("c.jpg", 100, 1_000)
        val oversized = write("b.jpg", 5_000, 2_000)
        val oldSmall = write("a.jpg", 100, 3_000)

        NoticeImageStore.pruneTo(folder.root, budgetBytes = 1_000)

        assertTrue(newest.exists())
        assertFalse(oversized.exists())
        assertTrue(oldSmall.exists())
    }
}
