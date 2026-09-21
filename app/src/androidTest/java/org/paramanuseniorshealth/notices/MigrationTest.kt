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
                "INSERT INTO notices (logId, title, body, receivedAt, imageUrl, pdfUrl, " +
                    "linkUrl, linkTitle, linkImage, linkSite) " +
                    "VALUES ('1757000000000', 'Holiday list', 'Closed Monday', 1757000000000, NULL, " +
                    "'https://paramanuseniorshealth.org/files/list.pdf', " +
                    "'https://paramanuseniorshealth.org', 'Paramanu Seniors Health', " +
                    "'https://paramanuseniorshealth.org/logo.png', 'paramanuseniorshealth.org')"
            )
        }

        val db = helper.runMigrationsAndValidate(
            name, 4, true, NoticesDatabase.MIGRATION_3_4,
        )

        db.query("SELECT title, body, pdfUrl, linkUrl, linkTitle, linkImage, linkSite, " +
            "pdfThumbUrl, pdfPages, pdfBytes, attachmentState, attachmentAttempts " +
            "FROM notices").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Holiday list", c.getString(0))
            assertEquals("Closed Monday", c.getString(1))
            assertEquals("https://paramanuseniorshealth.org/files/list.pdf", c.getString(2))
            // The pre-existing link-preview columns must survive the 3->4 hop untouched, not just
            // continue to exist -- an ALTER TABLE that rebuilds the table wrong could drop data
            // while leaving the column itself present and merely empty.
            assertEquals("https://paramanuseniorshealth.org", c.getString(3))
            assertEquals("Paramanu Seniors Health", c.getString(4))
            assertEquals("https://paramanuseniorshealth.org/logo.png", c.getString(5))
            assertEquals("paramanuseniorshealth.org", c.getString(6))
            // The new columns arrive empty, and a NULL state reads as PENDING -- which is what
            // makes an existing notice with no thumbnail repair itself on the next app open.
            assertNull(c.getString(7))
            assertTrue(c.isNull(8))
            assertTrue(c.isNull(9))
            assertNull(c.getString(10))
            assertEquals(0, c.getInt(11))
        }
    }

    /**
     * The upgrade path a real field device takes: some testers' phones are still on version 1 or
     * 2, and [androidx.room.RoomDatabase.Builder.addMigrations] chains every migration in one
     * hop. Only covering 3->4 in isolation would miss a break in how the three migrations compose.
     */
    @Test
    fun migrate1To4_keepsExistingNotices() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO notices (logId, title, body, receivedAt, pdfUrl) " +
                    "VALUES ('1757000000000', 'Holiday list', 'Closed Monday', 1757000000000, " +
                    "'https://paramanuseniorshealth.org/files/list.pdf')"
            )
        }

        val db = helper.runMigrationsAndValidate(
            name, 4, true,
            NoticesDatabase.MIGRATION_1_2, NoticesDatabase.MIGRATION_2_3, NoticesDatabase.MIGRATION_3_4,
        )

        db.query("SELECT title, body, pdfUrl FROM notices").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Holiday list", c.getString(0))
            assertEquals("Closed Monday", c.getString(1))
            assertEquals("https://paramanuseniorshealth.org/files/list.pdf", c.getString(2))
        }
    }
}
