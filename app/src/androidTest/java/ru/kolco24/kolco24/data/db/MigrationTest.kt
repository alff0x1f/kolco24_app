package ru.kolco24.kolco24.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the **first real upgrade migration** [AppDatabase.MIGRATION_1_2] against the exported schemas
 * (see `app/schemas/`). Robolectric is not on the classpath, so this runs as an instrumented test:
 * `./gradlew connectedDebugAndroidTest` (needs an emulator/device).
 *
 * Creates a v1 `marks` row, runs the migration, and asserts the legacy row gains `presentDetails IS NULL`
 * and the new `index_marks_raceId` exists. The helper also validates the resulting schema against 2.json.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test-db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_addsPresentDetailsNullAndRaceIdIndex() {
        // Create the v1 schema and insert one legacy mark (no presentDetails column exists yet).
        helper.createDatabase(testDb, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO marks (
                    id, raceId, teamId, checkpointId, checkpointNumber, cost, method,
                    cpUid, cpCode, present, expectedCount, complete, takenAt, updatedAt,
                    uploadedLocal, uploadedCloud
                ) VALUES (
                    'm1', 7, 42, 100, 1, 10, 'nfc',
                    'AABB', 'deadbeef', '[1,2]', 3, 0, 1000, 1000,
                    0, 0
                )
                """.trimIndent(),
            )
        }

        // Run the migration; MigrationTestHelper validates the result against schemas/2.json.
        val db = helper.runMigrationsAndValidate(testDb, 2, true, AppDatabase.MIGRATION_1_2)

        // The legacy row survived migration with original data intact (not dropped or zeroed).
        db.query("SELECT COUNT(*), teamId, present, complete FROM marks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(42, c.getInt(1))
            assertEquals("[1,2]", c.getString(2))
            assertEquals(0, c.getInt(3))
        }

        // The legacy row's new column is NULL (not corrupted to some default).
        db.query("SELECT presentDetails FROM marks WHERE id = 'm1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }

        // The new index exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_marks_raceId'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("index_marks_raceId", c.getString(0))
        }

        db.close()
    }

    @Test
    fun migrate2To3_addsPhotosUploadedColumnsDefaultZeroAndPreservesRows() {
        // Create the v2 schema and insert one pre-Phase-2 mark (no photosUploaded* columns exist yet).
        helper.createDatabase(testDb, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO marks (
                    id, raceId, teamId, checkpointId, checkpointNumber, cost, method,
                    cpUid, cpCode, present, presentDetails, expectedCount, complete, takenAt, updatedAt,
                    uploadedLocal, uploadedCloud
                ) VALUES (
                    'm1', 7, 42, 100, 1, 10, 'photo',
                    '', '', '[]', NULL, 3, 1, 1000, 1000,
                    1, 0
                )
                """.trimIndent(),
            )
        }

        // Run the migration; MigrationTestHelper validates the result against schemas/3.json.
        val db = helper.runMigrationsAndValidate(testDb, 3, true, AppDatabase.MIGRATION_2_3)

        // The legacy row survived migration with original data intact.
        db.query("SELECT COUNT(*), teamId, method, uploadedLocal FROM marks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(42, c.getInt(1))
            assertEquals("photo", c.getString(2))
            assertEquals(1, c.getInt(3))
        }

        // The new columns default to 0 (not uploaded yet), not corrupted or NULL.
        db.query("SELECT photosUploadedLocal, photosUploadedCloud FROM marks WHERE id = 'm1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
        }

        db.close()
    }

    @Test
    fun migrate3To4_addsScoringCountColumnDefaultZeroAndPreservesRows() {
        // Create the v3 schema and insert one pre-scoring-count legend_meta row (no scoringCount column yet).
        helper.createDatabase(testDb, 3).use { db ->
            db.execSQL(
                "INSERT INTO legend_meta (raceId, totalCost) VALUES (7, 250)",
            )
        }

        // Run the migration; MigrationTestHelper validates the result against schemas/4.json.
        val db = helper.runMigrationsAndValidate(testDb, 4, true, AppDatabase.MIGRATION_3_4)

        // The legacy row survived migration with original data intact.
        db.query("SELECT COUNT(*), totalCost FROM legend_meta WHERE raceId = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(250, c.getInt(1))
        }

        // The new column defaults to 0 (not yet refreshed from the server), not corrupted or NULL.
        db.query("SELECT scoringCount FROM legend_meta WHERE raceId = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun migrate4To5_createsJudgeScansTableAndAcceptsARow() {
        // v4 has no judge_scans table at all — nothing to seed beforehand.
        helper.createDatabase(testDb, 4).close()

        // Run the migration; MigrationTestHelper validates the result against schemas/5.json.
        val db = helper.runMigrationsAndValidate(testDb, 5, true, AppDatabase.MIGRATION_4_5)

        // The new table exists and accepts a row (write-once, nullable trustedTakenAt/bootCount).
        db.execSQL(
            """
            INSERT INTO judge_scans (
                id, raceId, eventType, participantNumber, nfcUid, takenAt, trustedTakenAt,
                elapsedRealtimeAt, bootCount, sourceInstallId, uploadedLocal, uploadedCloud
            ) VALUES (
                'scan-1', 7, 'start', 42, 'AABBCC', 1000, NULL,
                500, NULL, 'install-1', 0, 0
            )
            """.trimIndent(),
        )

        db.query("SELECT COUNT(*), raceId, eventType, trustedTakenAt, bootCount FROM judge_scans").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(7, c.getInt(1))
            assertEquals("start", c.getString(2))
            assertNull(c.getString(3))
            assertNull(c.getString(4))
        }

        // The raceId index exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_judge_scans_raceId'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("index_judge_scans_raceId", c.getString(0))
        }

        db.close()
    }
}
