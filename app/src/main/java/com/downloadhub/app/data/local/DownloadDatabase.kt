package com.downloadhub.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus

@Database(
    entities = [DownloadEntity::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(DownloadConverters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Adds the YouTube quality/audio-format columns without wiping the queue. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN audioFormat TEXT")
            }
        }

        /** Adds thumbnail artwork and media duration. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailUrl TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailPath TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN durationSeconds INTEGER")
            }
        }

        /** Adds the automatic-retry counter. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the per-download settings: priority, its own speed cap, when it may
         * start, and the two share limits.
         *
         * Each is added with the same default as its Kotlin field, so a queue saved
         * before these existed carries on behaving exactly as it did rather than
         * picking up a null where a number is expected.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN priorityRank INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE downloads ADD COLUMN speedLimitBytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN startAfterEpochMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN shareRatioLimit REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN seedTimeLimitMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN seedingSinceEpochMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN seedingStoppedAtEpochMillis INTEGER NOT NULL DEFAULT 0")
            }
        }


        /**
         * Per-file choices for a torrent, as two text columns.
         *
         * Both nullable with no default: a row written before this has no choice recorded,
         * which is the same thing as "every file" and not the same as an empty selection.
         * That distinction is what makes a torrent added before this version keep
         * downloading all of its files.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN torrentSelectedFiles TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN torrentFilePriorities TEXT")
            }
        }
        /**
         * Every migration, in one list.

         *
         * Named here so it can be checked that a path exists between every released
         * version and the current one. Adding a column to [DownloadEntity] with no
         * migration does not fail the build: `version` is a plain `Int`, so it simply
         * stays where it was, Room compares the schema on disk against the one it
         * expects, fails the integrity check, and the app throws on its first query.
         * That is a crash on launch with nothing in the build to point at, which is
         * exactly what happened when the per-download settings were added.
         */
        val ALL: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6
        )
    }
}

class DownloadConverters {
    @TypeConverter
    fun sourceToString(value: DownloadSource): String = value.name

    @TypeConverter
    fun stringToSource(value: String): DownloadSource =
        runCatching { DownloadSource.valueOf(value) }.getOrDefault(DownloadSource.HTTP)

    @TypeConverter
    fun statusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.FAILED)

    @TypeConverter
    fun categoryToString(value: DownloadCategory): String = value.name

    @TypeConverter
    fun stringToCategory(value: String): DownloadCategory =
        runCatching { DownloadCategory.valueOf(value) }.getOrDefault(DownloadCategory.OTHER)
}
