package com.downloadhub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room cannot be made to fail the build when a column is added without a migration.
 *
 * `version` is a plain `Int`, so adding a field to the entity and forgetting the number
 * leaves the version where it was. Room then compares the schema on disk with the one it
 * expects, finds they differ, and throws on the first query - a crash on launch with
 * nothing in the build output to point at. That is exactly what happened when the
 * per-download settings were added, and it is checked here instead.
 *
 * The SQL is read from the source rather than run: checking the statement text catches
 * the mistake that actually happens, which is leaving the migration out or adding the
 * column with the wrong type, and needs no database to do it.
 */
class DatabaseMigrationTest {

    private val databaseSource = File("src/main/java/com/downloadhub/app/data/local/DownloadDatabase.kt").readText()
    private val entitySource = File("src/main/java/com/downloadhub/app/data/local/DownloadEntity.kt").readText()
    private val containerSource = File("src/main/java/com/downloadhub/app/AppContainer.kt").readText()

    /** The version the database declares. */
    private fun declaredVersion(): Int =
        Regex("""version\s*=\s*(\d+)""").find(databaseSource)?.groupValues?.get(1)?.toInt()
            ?: error("the database no longer declares a version")

    /** Every migration step named in the source. */
    private fun declaredSteps(): List<Pair<Int, Int>> =
        Regex("""Migration\((\d+),\s*(\d+)\)""")
            .findAll(databaseSource)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

    @Test
    fun everyStepUpToTheCurrentVersionHasAMigration() {
        val steps = declaredSteps()
        for (from in 1 until declaredVersion()) {
            val to = from + 1
            assertTrue(
                "there is no migration from $from to $to, so an app updated from that " +
                    "version cannot open its database and crashes on launch",
                steps.contains(from to to)
            )
        }
    }

    @Test
    fun theMigrationsFormOneUnbrokenChain() {
        val sorted = declaredSteps().sortedWith(compareBy({ it.first }, { it.second }))
        var expected = 1
        sorted.forEach { (from, to) ->
            assertEquals("the migration chain has a gap before $from->$to", expected, from)
            expected = to
        }
        assertEquals(
            "the migration chain stops at $expected but the database is at ${declaredVersion()}",
            declaredVersion(),
            expected
        )
    }

    /**
     * Every column the entity declares has to be added by some migration.
     *
     * The direct check behind the crash: a new field that no `ALTER TABLE` mentions is
     * invisible at runtime until Room refuses to open the database.
     */
    @Test
    fun everyPerDownloadColumnIsAddedByAMigration() {
        val columns = listOf(
            "priorityRank", "speedLimitBytesPerSecond", "startAfterEpochMillis",
            "shareRatioLimit", "seedTimeLimitMinutes", "seedingSinceEpochMillis",
            "seedingStoppedAtEpochMillis"
        )
        columns.forEach { column ->
            assertTrue(
                "DownloadEntity declares $column but no migration adds it",
                entitySource.contains("val $column")
            )
            assertTrue(
                "DownloadEntity declares $column but no ALTER TABLE adds it, so an " +
                    "existing install cannot open its database",
                databaseSource.contains("ADD COLUMN $column ")
            )
        }
    }

    @Test
    fun theNewestColumnsAreNotNullWithADefault() {
        // Scoped to columns the entity declares NOT NULL. `quality` and `audioFormat`
        // are genuinely nullable - a plain HTTP item simply has no quality set - so they
        // are added without a default, which is right for them and would be wrong here.
        listOf(
            "retryCount", "priorityRank", "speedLimitBytesPerSecond", "startAfterEpochMillis",
            "shareRatioLimit", "seedTimeLimitMinutes", "seedingSinceEpochMillis",
            "seedingStoppedAtEpochMillis"
        ).forEach { column ->
            val notNull = Regex("""val $column\s*:\s*\w+(?!\?)""").containsMatchIn(entitySource)
            if (!notNull) return@forEach
            assertTrue(
                "$column is NOT NULL in the entity but is added without a default, so a " +
                    "row saved before it existed cannot be read",
                Regex("""ADD COLUMN $column \w+ NOT NULL DEFAULT""").containsMatchIn(databaseSource)
            )
        }
    }

    @Test
    fun theStreamChoiceColumnsAreAddedByAMigration() {
        // Nullable like quality and audioFormat: a plain HTTP item has no streams
        // named, so these are added without a default - which is right for them.
        listOf("streamFormatId", "streamAudioFormatId").forEach { column ->
            assertTrue(
                "DownloadEntity declares $column",
                entitySource.contains("val $column")
            )
            assertTrue(
                "DownloadEntity declares $column but no ALTER TABLE adds it, so an " +
                    "existing install cannot open its database",
                databaseSource.contains("ADD COLUMN $column ")
            )
        }
    }

    @Test
    fun appContainerRegistersTheMigrationsTheDatabaseDeclares() {
        assertTrue(
            "AppContainer must register DownloadDatabase.ALL rather than its own copy, " +
                "or a migration can be written and never installed",
            containerSource.contains("addMigrations(*DownloadDatabase.ALL)")
        )
        assertTrue(
            "and the database must expose that list",
            databaseSource.contains("val ALL: Array<androidx.room.migration.Migration>")
        )
    }

    /** Destructive fallback must stay the last resort, not the mechanism. */
    @Test
    fun thereIsAPathForEveryUpgradeEvenThoughTheFallbackExists() {
        assertTrue(
            "fallbackToDestructiveMigration would silently wipe the queue, and it is only " +
                "safe because every version has a real path",
            containerSource.contains("fallbackToDestructiveMigration")
        )
        assertEquals(
            "every declared version needs its own step",
            declaredVersion() - 1,
            declaredSteps().distinct().size
        )
    }
}
