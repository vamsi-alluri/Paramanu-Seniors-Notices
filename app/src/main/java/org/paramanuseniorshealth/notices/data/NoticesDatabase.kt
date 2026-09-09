package org.paramanuseniorshealth.notices.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Started at version 1 with no inherited history, deliberately.
 *
 * The forked app carried a 1->2 migration adding severity columns; this app has a differently named
 * table and those columns do not exist here, so reintroducing that history would have meant
 * maintaining a path no installed device could ever have been on. Version 2 below is this app's own
 * first migration, and it is real -- there are already installs holding redeemed codes and received
 * notices. Every migration from here must stay real, because the stored history is the only
 * copy a user has of a notice they have already dismissed.
 */
@Database(
    entities = [NoticeEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class NoticesDatabase : RoomDatabase() {

    abstract fun noticeDao(): NoticeDao

    companion object {
        @Volatile
        private var instance: NoticesDatabase? = null

        /**
         * Adds [NoticeEntity.imageUrl]. A real migration rather than a destructive fallback: the
         * stored history is the only copy a user has of a notice they have already dismissed, and
         * there are already installs carrying redeemed codes and received notices.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE notices ADD COLUMN imageUrl TEXT")
            }
        }

        /**
         * Adds the four link-preview columns.
         *
         * Four separate columns rather than one JSON blob because Room can query and migrate
         * columns, and because each field genuinely arrives on its own: a site with no favicon the
         * service recognises yields a title and no image, and a JS-rendered page yields an image
         * and no title. A blob would make "which of these is missing" a parsing question.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE notices ADD COLUMN linkUrl TEXT")
                connection.execSQL("ALTER TABLE notices ADD COLUMN linkTitle TEXT")
                connection.execSQL("ALTER TABLE notices ADD COLUMN linkImage TEXT")
                connection.execSQL("ALTER TABLE notices ADD COLUMN linkSite TEXT")
            }
        }

        /**
         * Opened from two entry points that can race -- the Activity and the FCM service, the
         * latter able to start with no Activity ever having run -- so creation is double-checked
         * rather than lazy-per-caller.
         */
        fun getInstance(context: Context): NoticesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoticesDatabase::class.java,
                    "notices.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
