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
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(TeamMembersConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun teamDao(): TeamDao
    abstract fun selectedTeamDao(): SelectedTeamDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
