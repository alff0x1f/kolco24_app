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
        LegendMetaEntity::class,
        TrackPointEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(
    TeamMembersConverter::class,
    IntListConverter::class,
    MarkMemberSnapshotListConverter::class,
)
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
    abstract fun legendMetaDao(): LegendMetaDao
    abstract fun trackDao(): TrackDao

    companion object {
        // Migrations are LIVE again as of v2. The app is shipped (project memory:
        // room-released-with-migrations) — the v1→v11 history was collapsed to a v1 baseline back when the
        // only install was a dev device, but that assumption no longer holds, so a real upgrade migration
        // is now required for every schema change. v1→v2 is the first real one (adds marks.presentDetails
        // + index_marks_raceId). Add the next migration here — with a version bump and a committed
        // schemas/<n>.json — and append it to .addMigrations(...) below.
        //
        // fallbackToDestructiveMigrationOnDowngrade: opening a newer schema over an older-versioned DB
        // (e.g. a db restored by Android Auto Backup on reinstall — `allowBackup=true` and the backup
        // rules do not exclude kolco24.db) wipes and recreates instead of crashing on a downgrade. Scoped
        // to DOWNGRADE only — a missing *upgrade* migration must still fail loudly. dropAllTables=true
        // clears even tables Room no longer knows about. Local-only data (marks/bindings/tracks) is lost
        // on the wipe; races/teams/legend re-sync from the server.

        /**
         * v1→v2: adds the nullable `marks.presentDetails` column (per-member snapshots for the upload
         * `present[]`) and `index_marks_raceId` (symmetric to `track_points`, for the scope-filtered
         * upload queries). Additive only — no data is touched: legacy rows keep `presentDetails IS NULL`,
         * and the upload mapper merges over `present` so no member is ever lost.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE marks ADD COLUMN presentDetails TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_marks_raceId` ON `marks` (`raceId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
