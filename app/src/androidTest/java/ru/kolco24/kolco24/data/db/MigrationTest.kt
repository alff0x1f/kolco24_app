package ru.kolco24.kolco24.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_keepsRacesAndAddsTeamTables() {
        val dbName = "migration-test.db"

        // Create v1 with one race row.
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus, isLegendVisible) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open', 1)"
            )
        }

        // Run the migration; MigrationTestHelper validates the resulting schema against 2.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )

        db.query("SELECT name FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
        }

        // New tables exist and are empty.
        db.query("SELECT count(*) FROM teams").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT count(*) FROM categories").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT count(*) FROM selected_team").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate1To2_indexExists() {
        val dbName = "migration-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_teams_raceId'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.isNull(0))
            }
        db.close()
    }

    @Test
    fun migrate2To3_keepsDataAndAddsCheckpointsTable() {
        val dbName = "migration-2to3-test.db"

        // Create v1, run 1→2 to reach v2, then seed a race + a team row.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus, isLegendVisible) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open', 1)"
            )
            // Exact v2 `teams` column set (from 2.json).
            db.execSQL(
                "INSERT INTO teams (id, raceId, teamname, startNumber, categoryId, ucount, " +
                    "paidPeople, startTime, finishTime, members) " +
                    "VALUES (3, 7, 'Команда', '12', NULL, 2, 2.0, 0, 0, '[]')"
            )
        }

        // Run 2→3; MigrationTestHelper validates the resulting schema against 3.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        )

        // Existing rows survive the additive migration.
        db.query("SELECT name FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
        }
        db.query("SELECT teamname FROM teams WHERE id = 3").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Команда", cursor.getString(0))
        }

        // New table exists and is empty.
        db.query("SELECT count(*) FROM checkpoints").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // Column set matches CheckpointEntity — a camelCase/snake_case mismatch only surfaces here.
        db.execSQL(
            "INSERT INTO checkpoints (id, raceId, number, cost, type, description, taken) " +
                "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0)"
        )
        db.query("SELECT id, raceId, number, cost, type, description, taken FROM checkpoints WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(7, cursor.getInt(1))
                assertEquals(5, cursor.getInt(2))
                assertEquals(10, cursor.getInt(3))
                assertEquals("kp", cursor.getString(4))
                assertEquals("У пня", cursor.getString(5))
                assertEquals(0, cursor.getInt(6))
            }
        db.close()
    }

    @Test
    fun migrate3To4_dropsLegendFlagAndAddsEncColumnsAndTags() {
        val dbName = "migration-3to4-test.db"

        // Reach v3, then seed a full race row (with isLegendVisible) + a checkpoint row.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus, isLegendVisible) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open', 1)"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, taken) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0)"
            )
        }

        // Run 3→4; MigrationTestHelper validates the resulting schema against 4.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        )

        // races: isLegendVisible is gone, but name/date/regStatus survived the recreate.
        db.query("SELECT name, date, regStatus FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
            assertEquals("2026-08-01", cursor.getString(1))
            assertEquals("open", cursor.getString(2))
        }
        db.query("SELECT count(*) FROM pragma_table_info('races') WHERE name = 'isLegendVisible'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

        // checkpoints: old row intact, copied as an open CP (locked=0, enc null), new columns present.
        db.query(
            "SELECT cost, description, locked, encIv, encCt, taken FROM checkpoints WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10, cursor.getInt(0))
            assertEquals("У пня", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(0, cursor.getInt(5))
        }
        // The recreated checkpoints table accepts nullable cost/description + a locked enc row.
        db.execSQL(
            "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, taken) " +
                "VALUES (2, 7, 6, NULL, 'kp', NULL, 1, 'iv==', 'ct==', 0)"
        )
        db.query("SELECT cost, locked, encIv FROM checkpoints WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("iv==", cursor.getString(2))
        }

        // tags: new table exists and is usable.
        db.query("SELECT count(*) FROM tags").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO tags (bid, raceId, point, checkMethod, iv, ct) " +
                "VALUES ('abc123', 7, 5, 'nfc', NULL, NULL)"
        )
        db.query("SELECT point FROM tags WHERE bid = 'abc123'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate3To4_tagIndicesExist() {
        val dbName = "migration-3to4-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name IN ('index_tags_raceId', 'index_tags_point') AND tbl_name = 'tags'"
        ).use { cursor ->
            assertEquals(2, cursor.count)
        }
        db.close()
    }

    @Test
    fun migrate2To3_indexExists() {
        val dbName = "migration-2to3-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_checkpoints_raceId' AND tbl_name = 'checkpoints'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.isNull(0))
        }
        db.close()
    }
}
