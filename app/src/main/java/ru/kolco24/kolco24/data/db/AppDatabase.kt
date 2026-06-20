package ru.kolco24.kolco24.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RaceEntity::class,
        SyncMetaEntity::class,
        CategoryEntity::class,
        TeamEntity::class,
        SelectedTeamEntity::class,
        CheckpointEntity::class,
        TagEntity::class,
        MemberTagEntity::class,
        MemberChipBindingEntity::class,
        MarkEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(TeamMembersConverter::class, IntListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun teamDao(): TeamDao
    abstract fun selectedTeamDao(): SelectedTeamDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun tagDao(): TagDao
    abstract fun memberTagDao(): MemberTagDao
    abstract fun memberChipBindingDao(): MemberChipBindingDao
    abstract fun markDao(): MarkDao

    companion object {
        /**
         * Adds the team-selection tables (categories / teams / selected_team). Existing tables are
         * untouched, so the races list survives the upgrade. The SQL must match Room's generated
         * schema (see schemas/.../2.json) exactly, or the validation check fails at runtime.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `code` TEXT NOT NULL, `shortName` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `teams` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `teamname` TEXT NOT NULL, `startNumber` TEXT, " +
                        "`categoryId` INTEGER, `ucount` INTEGER NOT NULL, `paidPeople` REAL NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, `finishTime` INTEGER NOT NULL, " +
                        "`members` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_teams_raceId` ON `teams` (`raceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `selected_team` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `teamId` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Adds the `checkpoints` table (race legend). Existing tables are untouched, so races /
         * teams / selected-team survive the upgrade. The SQL must match Room's generated schema
         * (see schemas/.../3.json) exactly, or the validation check fails at runtime.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `checkpoints` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `number` INTEGER NOT NULL, " +
                        "`cost` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `taken` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_checkpoints_raceId` ON `checkpoints` (`raceId`)"
                )
            }
        }

        /**
         * Legend-encryption rework. SQLite-24 can neither relax `NOT NULL` nor `DROP COLUMN`, so
         * `checkpoints` and `races` are recreated (copy-rename), not altered in place:
         *  - `checkpoints` gains nullable `cost`/`description` plus `locked`/`encIv`/`encCt`; old rows
         *    are copied with `locked = 0` and enc columns null (everything was an open CP pre-v4).
         *  - `races` drops `isLegendVisible` (the race-level legend flag is gone from the API).
         *  - a new `tags` table maps each NFC tag's `bid → point` (+ optional unlock envelope).
         * The recreate copies preserve all existing race/checkpoint data. SQL must match Room's
         * generated schema (see schemas/.../4.json) exactly, or the validation check fails at runtime.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate `checkpoints` with nullable cost/description + locked/enc columns.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `checkpoints_new` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `cost` INTEGER, " +
                        "`type` TEXT NOT NULL, `description` TEXT, `locked` INTEGER NOT NULL, " +
                        "`encIv` TEXT, `encCt` TEXT, `taken` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `checkpoints_new` " +
                        "(`id`, `raceId`, `number`, `cost`, `type`, `description`, `locked`, " +
                        "`encIv`, `encCt`, `taken`) " +
                        "SELECT `id`, `raceId`, `number`, `cost`, `type`, `description`, 0, " +
                        "NULL, NULL, `taken` FROM `checkpoints`"
                )
                db.execSQL("DROP TABLE `checkpoints`")
                db.execSQL("ALTER TABLE `checkpoints_new` RENAME TO `checkpoints`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_checkpoints_raceId` ON `checkpoints` (`raceId`)"
                )

                // Recreate `races` dropping `isLegendVisible`.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `races_new` (`id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `slug` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`dateEnd` TEXT, `place` TEXT NOT NULL, `regStatus` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `races_new` " +
                        "(`id`, `name`, `slug`, `date`, `dateEnd`, `place`, `regStatus`) " +
                        "SELECT `id`, `name`, `slug`, `date`, `dateEnd`, `place`, `regStatus` " +
                        "FROM `races`"
                )
                db.execSQL("DROP TABLE `races`")
                db.execSQL("ALTER TABLE `races_new` RENAME TO `races`")

                // New `tags` table + indices. Composite PK (raceId, bid) keeps each race's tags
                // isolated so physical NFC tags shared across races cannot overwrite each other.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`raceId` INTEGER NOT NULL, " +
                        "`bid` TEXT NOT NULL, `point` INTEGER NOT NULL, " +
                        "`checkMethod` TEXT NOT NULL, `iv` TEXT, `ct` TEXT, PRIMARY KEY(`raceId`, `bid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_raceId` ON `tags` (`raceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_point` ON `tags` (`point`)")
            }
        }

        /**
         * NFC member-tag binding. Purely additive — existing tables are untouched, so races / teams /
         * checkpoints / tags survive the upgrade:
         *  - `member_tags` is the per-race NFC pool (`nfc_uid → participant number`), composite PK
         *    `(raceId, nfcUid)` because the feed carries no internal id and the same uid may appear in
         *    two races' pools; indexed on `raceId` for wholesale per-race replacement.
         *  - `member_chip_bindings` is the local-only binding of a chip to a `(teamId, numberInTeam)`
         *    member slot; indexed on `nfcUid` for the "already bound elsewhere?" duplicate check.
         * SQL must match Room's generated schema (see schemas/.../5.json) exactly, or the validation
         * check fails at runtime.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `member_tags` (`raceId` INTEGER NOT NULL, " +
                        "`nfcUid` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`raceId`, `nfcUid`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_member_tags_raceId` ON `member_tags` (`raceId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `member_chip_bindings` (`teamId` INTEGER NOT NULL, " +
                        "`numberInTeam` INTEGER NOT NULL, `nfcUid` TEXT NOT NULL, " +
                        "`participantNumber` INTEGER NOT NULL, PRIMARY KEY(`teamId`, `numberInTeam`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_member_chip_bindings_nfcUid` " +
                        "ON `member_chip_bindings` (`nfcUid`)"
                )
            }
        }

        /**
         * Local-only checkpoint-taking log. Purely additive — existing tables are untouched, so the
         * v5 data survives the upgrade. `marks` carries one row per take ([MarkEntity]); it is indexed
         * on `teamId` (the roster view's query) and `point` (the score derivation). The upload flags
         * stay unindexed — no upload queries exist yet (an additive migration will index them when
         * they do). SQL must match Room's generated schema (see schemas/.../6.json) exactly, or the
         * validation check fails at runtime.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `marks` (`id` TEXT NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `teamId` INTEGER NOT NULL, " +
                        "`point` INTEGER NOT NULL, `checkpointNumber` INTEGER NOT NULL, " +
                        "`cost` INTEGER NOT NULL, `method` TEXT NOT NULL, `cpUid` TEXT NOT NULL, " +
                        "`cpCode` TEXT NOT NULL, `present` TEXT NOT NULL, " +
                        "`expectedCount` INTEGER NOT NULL, `complete` INTEGER NOT NULL, " +
                        "`photoPath` TEXT, `takenAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`uploadedLocal` INTEGER NOT NULL, `uploadedCloud` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_marks_teamId` ON `marks` (`teamId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_marks_point` ON `marks` (`point`)")
            }
        }

        /**
         * Drops the race-global `taken` column from `checkpoints`. "Взято" is team-scoped, so it is
         * now derived from the marks log instead of persisted on the (race-shared) checkpoint row.
         * SQLite-24 cannot `DROP COLUMN`, so `checkpoints` is recreated (copy-rename) exactly like
         * [MIGRATION_3_4] — all other checkpoint data (incl. offline reveals) is copied across. SQL
         * must match Room's generated schema (see schemas/.../7.json) exactly, or the validation check
         * fails at runtime. The `marks` table is untouched.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `checkpoints_new` (`id` INTEGER NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `cost` INTEGER, " +
                        "`type` TEXT NOT NULL, `description` TEXT, `locked` INTEGER NOT NULL, " +
                        "`encIv` TEXT, `encCt` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `checkpoints_new` " +
                        "(`id`, `raceId`, `number`, `cost`, `type`, `description`, `locked`, " +
                        "`encIv`, `encCt`) " +
                        "SELECT `id`, `raceId`, `number`, `cost`, `type`, `description`, `locked`, " +
                        "`encIv`, `encCt` FROM `checkpoints`"
                )
                db.execSQL("DROP TABLE `checkpoints`")
                db.execSQL("ALTER TABLE `checkpoints_new` RENAME TO `checkpoints`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_checkpoints_raceId` ON `checkpoints` (`raceId`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .build()
    }
}
