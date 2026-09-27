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
    version = 4,
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
