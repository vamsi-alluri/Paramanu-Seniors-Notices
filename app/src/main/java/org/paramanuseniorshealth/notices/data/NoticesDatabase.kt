package org.paramanuseniorshealth.notices.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [NotificationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class NoticesDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var instance: NoticesDatabase? = null

        /**
         * Adds the rich-notification columns. A real migration rather than a destructive fallback:
         * the stored history is the entire point of the app and must survive the upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE notifications ADD COLUMN imageUrl TEXT")
                connection.execSQL("ALTER TABLE notifications ADD COLUMN level TEXT")
                connection.execSQL("ALTER TABLE notifications ADD COLUMN color TEXT")
            }
        }

        /**
         * The database is opened from two processes-worth of entry points (the Activity and the
         * FCM service), so instance creation is double-checked rather than lazy-per-caller.
         */
        fun getInstance(context: Context): NoticesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoticesDatabase::class.java,
                    "notifier.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
