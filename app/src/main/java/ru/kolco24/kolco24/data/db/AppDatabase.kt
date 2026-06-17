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
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(TeamMembersConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun teamDao(): TeamDao
    abstract fun selectedTeamDao(): SelectedTeamDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun tagDao(): TagDao

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

                // New `tags` table + indices.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`bid` TEXT NOT NULL, " +
                        "`raceId` INTEGER NOT NULL, `point` INTEGER NOT NULL, " +
                        "`checkMethod` TEXT NOT NULL, `iv` TEXT, `ct` TEXT, PRIMARY KEY(`bid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_raceId` ON `tags` (`raceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_point` ON `tags` (`point`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
