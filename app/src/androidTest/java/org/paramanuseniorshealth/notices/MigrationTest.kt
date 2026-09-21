package org.paramanuseniorshealth.notices

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.paramanuseniorshealth.notices.data.NoticesDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NoticesDatabase::class.java,
    )

    /** The case that matters: a notice already on a tester's phone must survive intact. */
    @Test
    fun migrate3To4_keepsExistingNotices() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO notices (logId, title, body, receivedAt, imageUrl, pdfUrl) " +
                    "VALUES ('1757000000000', 'Holiday list', 'Closed Monday', 1757000000000, NULL, " +
                    "'https://paramanuseniorshealth.org/files/list.pdf')"
            )
        }

        val db = helper.runMigrationsAndValidate(
            name, 4, true, NoticesDatabase.MIGRATION_3_4,
        )

        db.query("SELECT title, body, pdfUrl, pdfThumbUrl, pdfPages, pdfBytes, " +
            "attachmentState, attachmentAttempts FROM notices").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Holiday list", c.getString(0))
            assertEquals("Closed Monday", c.getString(1))
            assertEquals("https://paramanuseniorshealth.org/files/list.pdf", c.getString(2))
            // The new columns arrive empty, and a NULL state reads as PENDING -- which is what
            // makes an existing notice with no thumbnail repair itself on the next app open.
            assertNull(c.getString(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))
            assertNull(c.getString(6))
            assertEquals(0, c.getInt(7))
        }
    }
}
