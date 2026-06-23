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
    fun migrate4To5_keepsDataAndAddsMemberTables() {
        val dbName = "migration-4to5-test.db"

        // Reach v4, then seed a race + a checkpoint so we can assert existing data survives.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open')"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, taken) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0, NULL, NULL, 0)"
            )
        }

        // Run 4→5; MigrationTestHelper validates the resulting schema against 5.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        )

        // Existing rows survive the additive migration.
        db.query("SELECT name FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
        }
        db.query("SELECT description FROM checkpoints WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("У пня", cursor.getString(0))
        }

        // New tables exist, are empty, and accept the entity column sets — a camelCase/snake_case
        // mismatch only surfaces here.
        db.query("SELECT count(*) FROM member_tags").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO member_tags (raceId, nfcUid, number) VALUES (7, '04A2B3C4', 101)"
        )
        db.query("SELECT number FROM member_tags WHERE raceId = 7 AND nfcUid = '04A2B3C4'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(101, cursor.getInt(0))
            }

        db.query("SELECT count(*) FROM member_chip_bindings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO member_chip_bindings (teamId, numberInTeam, nfcUid, participantNumber) " +
                "VALUES (3, 1, '04A2B3C4', 101)"
        )
        db.query(
            "SELECT participantNumber FROM member_chip_bindings WHERE teamId = 3 AND numberInTeam = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(101, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate4To5_memberIndicesExist() {
        val dbName = "migration-4to5-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_member_tags_raceId' AND tbl_name = 'member_tags'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.isNull(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_member_chip_bindings_nfcUid' AND tbl_name = 'member_chip_bindings'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.isNull(0))
        }
        db.close()
    }

    @Test
    fun migrate5To6_keepsDataAndAddsMarksTable() {
        val dbName = "migration-5to6-test.db"

        // Reach v5, then seed a race + a checkpoint so we can assert existing data survives.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open')"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, taken) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0, NULL, NULL, 0)"
            )
        }

        // Run 5→6; MigrationTestHelper validates the resulting schema against 6.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            6,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        )

        // Existing rows survive the additive migration.
        db.query("SELECT name FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
        }
        db.query("SELECT description FROM checkpoints WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("У пня", cursor.getString(0))
        }

        // New table exists, is empty, and accepts the entity column set — a camelCase/snake_case
        // mismatch only surfaces here.
        db.query("SELECT count(*) FROM marks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO marks (id, raceId, teamId, point, checkpointNumber, cost, method, " +
                "cpUid, cpCode, present, expectedCount, complete, photoPath, takenAt, updatedAt, " +
                "uploadedLocal, uploadedCloud) " +
                "VALUES ('uuid-1', 7, 3, 1, 5, 10, 'nfc', '04A2B3C4', 'DEADBEEF', '[1,2]', 2, 1, " +
                "NULL, 1000, 1000, 0, 0)"
        )
        db.query("SELECT point, complete, present FROM marks WHERE id = 'uuid-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("[1,2]", cursor.getString(2))
        }
        db.close()
    }

    @Test
    fun migrate5To6_marksIndicesExist() {
        val dbName = "migration-5to6-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            6,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name IN ('index_marks_teamId', 'index_marks_point') AND tbl_name = 'marks'"
        ).use { cursor ->
            assertEquals(2, cursor.count)
        }
        db.close()
    }

    @Test
    fun migrate6To7_dropsTakenColumnAndKeepsCheckpointData() {
        val dbName = "migration-6to7-test.db"

        // Reach v6, then seed a race + a checkpoint (with the soon-to-be-dropped `taken` column set).
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            6,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open')"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, taken) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0, NULL, NULL, 1)"
            )
        }

        // Run 6→7; MigrationTestHelper validates the resulting schema against 7.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
        )

        // The `taken` column is gone, but the rest of the checkpoint survives the recreate.
        db.query("SELECT count(*) FROM pragma_table_info('checkpoints') WHERE name = 'taken'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        db.query("SELECT cost, description, locked FROM checkpoints WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10, cursor.getInt(0))
            assertEquals("У пня", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        db.close()
    }

    @Test
    fun migrate6To7_indexSurvivesRecreate() {
        val dbName = "migration-6to7-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
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

    @Test
    fun migrate7To8_keepsDataAndAddsColorColumn() {
        val dbName = "migration-7to8-test.db"

        // Reach v7, then seed a race + a checkpoint so we can assert existing data survives.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open')"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0, NULL, NULL)"
            )
        }

        // Run 7→8; MigrationTestHelper validates the resulting schema against 8.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        )

        // The new `color` column exists and the existing row backfilled to '' (the default).
        db.query("SELECT count(*) FROM pragma_table_info('checkpoints') WHERE name = 'color'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        db.query("SELECT cost, description, color FROM checkpoints WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10, cursor.getInt(0))
            assertEquals("У пня", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        // The column accepts a non-default token too.
        db.execSQL(
            "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, color) " +
                "VALUES (2, 7, 6, 20, 'kp', 'У реки', 0, NULL, NULL, 'red')"
        )
        db.query("SELECT color FROM checkpoints WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("red", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate8To9_keepsDataAndAddsLegendMetaTable() {
        val dbName = "migration-8to9-test.db"

        // Reach v8, then seed a race + a checkpoint so we can assert existing data survives.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        ).use { db ->
            db.execSQL(
                "INSERT INTO races (id, name, slug, date, dateEnd, place, regStatus) " +
                    "VALUES (7, 'Кольцо', 'kolco', '2026-08-01', NULL, 'Лес', 'open')"
            )
            db.execSQL(
                "INSERT INTO checkpoints (id, raceId, number, cost, type, description, locked, encIv, encCt, color) " +
                    "VALUES (1, 7, 5, 10, 'kp', 'У пня', 0, NULL, NULL, '')"
            )
        }

        // Run 8→9; MigrationTestHelper validates the resulting schema against 9.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            9,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
        )

        // Existing rows survive the additive migration.
        db.query("SELECT name FROM races WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Кольцо", cursor.getString(0))
        }
        db.query("SELECT description FROM checkpoints WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("У пня", cursor.getString(0))
        }

        // New table exists, is empty, and accepts the entity column set — a camelCase/snake_case
        // mismatch only surfaces here.
        db.query("SELECT count(*) FROM legend_meta").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL("INSERT INTO legend_meta (raceId, totalCost) VALUES (7, 42)")
        db.query("SELECT totalCost FROM legend_meta WHERE raceId = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(42, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate9To10_keepsMarkRowAndAddsTimeColumns() {
        val dbName = "migration-9to10-test.db"

        // Reach v9, then seed a marks row (with the v9 column set, no trusted-clock columns).
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            9,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
        ).use { db ->
            db.execSQL(
                "INSERT INTO marks (id, raceId, teamId, point, checkpointNumber, cost, method, " +
                    "cpUid, cpCode, present, expectedCount, complete, photoPath, takenAt, updatedAt, " +
                    "uploadedLocal, uploadedCloud) " +
                    "VALUES ('uuid-1', 7, 3, 1, 5, 10, 'nfc', '04A2B3C4', 'DEADBEEF', '[1,2]', 2, 1, " +
                    "NULL, 1000, 1000, 0, 0)"
            )
        }

        // Run 9→10; MigrationTestHelper validates the resulting schema against 10.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            10,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
        )

        // The legacy row survives, and the three new columns are present and NULL on it.
        db.query(
            "SELECT point, complete, trustedTakenAt, elapsedRealtimeAt, bootCount " +
                "FROM marks WHERE id = 'uuid-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        // The new columns accept real values too.
        db.execSQL(
            "INSERT INTO marks (id, raceId, teamId, point, checkpointNumber, cost, method, " +
                "cpUid, cpCode, present, expectedCount, complete, photoPath, takenAt, updatedAt, " +
                "uploadedLocal, uploadedCloud, trustedTakenAt, elapsedRealtimeAt, bootCount) " +
                "VALUES ('uuid-2', 7, 3, 2, 6, 20, 'nfc', '04A2B3C5', 'BEEFCAFE', '[1,2]', 2, 1, " +
                "NULL, 2000, 2000, 0, 0, 1500, 50, 11)"
        )
        db.query(
            "SELECT trustedTakenAt, elapsedRealtimeAt, bootCount FROM marks WHERE id = 'uuid-2'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1500, cursor.getInt(0))
            assertEquals(50, cursor.getInt(1))
            assertEquals(11, cursor.getInt(2))
        }
        db.close()
    }

    @Test
    fun migrate10To11_keepsDataAndAddsTrackTable() {
        val dbName = "migration-10to11-test.db"

        // Reach v10, then seed a marks row so we can assert existing data survives.
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(
            dbName,
            10,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
        ).use { db ->
            db.execSQL(
                "INSERT INTO marks (id, raceId, teamId, point, checkpointNumber, cost, method, " +
                    "cpUid, cpCode, present, expectedCount, complete, photoPath, takenAt, updatedAt, " +
                    "uploadedLocal, uploadedCloud) " +
                    "VALUES ('uuid-1', 7, 3, 1, 5, 10, 'nfc', '04A2B3C4', 'DEADBEEF', '[1,2]', 2, 1, " +
                    "NULL, 1000, 1000, 0, 0)"
            )
        }

        // Run 10→11; MigrationTestHelper validates the resulting schema against 11.json.
        val db = helper.runMigrationsAndValidate(
            dbName,
            11,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
        )

        // The legacy marks row survives the additive migration.
        db.query("SELECT point FROM marks WHERE id = 'uuid-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // New table exists, is empty, and accepts the entity column set — a camelCase/snake_case
        // mismatch only surfaces here.
        db.query("SELECT count(*) FROM track_points").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO track_points (id, raceId, teamId, lat, lon, accuracy, gpsTimeMs, " +
                "elapsedRealtimeAt, bootCount, wallMs, trustedMs, uploadedLocal, uploadedCloud) " +
                "VALUES ('tp-1', 7, 3, 55.75, 37.61, 12.4, 1718900000000, 9876543, 11, " +
                "1718900000123, 1718900000200, 0, 0)"
        )
        db.query(
            "SELECT lat, lon, accuracy, elapsedRealtimeAt, bootCount, trustedMs " +
                "FROM track_points WHERE id = 'tp-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(55.75, cursor.getDouble(0), 0.0001)
            assertEquals(37.61, cursor.getDouble(1), 0.0001)
            assertEquals(12.4f, cursor.getFloat(2), 0.01f)
            assertEquals(9876543, cursor.getLong(3))
            assertEquals(11, cursor.getInt(4))
            assertEquals(1718900000200, cursor.getLong(5))
        }
        // A NULL bootCount / trustedMs is accepted (no clock sync yet).
        db.execSQL(
            "INSERT INTO track_points (id, raceId, teamId, lat, lon, accuracy, gpsTimeMs, " +
                "elapsedRealtimeAt, bootCount, wallMs, trustedMs, uploadedLocal, uploadedCloud) " +
                "VALUES ('tp-2', 7, 3, 55.76, 37.62, 8.0, 1718900060000, 9936543, NULL, " +
                "1718900060000, NULL, 0, 0)"
        )
        db.query("SELECT bootCount, trustedMs FROM track_points WHERE id = 'tp-2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
    }

    @Test
    fun migrate10To11_trackIndicesExist() {
        val dbName = "migration-10to11-index-test.db"
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            11,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name IN ('index_track_points_teamId', 'index_track_points_raceId') " +
                "AND tbl_name = 'track_points'"
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
