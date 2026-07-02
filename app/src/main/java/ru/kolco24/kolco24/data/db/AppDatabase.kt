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
    version = 4,
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

        /**
         * v2→v3 (Phase 2, photo-mark upload): adds `marks.photosUploadedLocal`/`photosUploadedCloud`
         * (per-target frame-drain flags, see [MarkEntity.photosUploadedLocal]). Additive only, default
         * 0 — legacy rows are treated as "no frames uploaded yet", which is correct for both a row with
         * no photos (the drain query also requires `photoPath IS NOT NULL`) and a row with unsent frames.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE marks ADD COLUMN photosUploadedLocal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE marks ADD COLUMN photosUploadedCloud INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3→v4 (scoring-count taken-KP): adds `legend_meta.scoringCount` (server-supplied count of
         * checkpoints with `cost > 0`, open + locked — the denominator for the taken-КП counter).
         * Additive only, default 0 — legacy rows read as "unknown" until the next legend refresh
         * repopulates it; both consumers (MarksScreen's «ВЗЯТО», LegendScreen's `ScoreCard`) gate
         * the "/total" suffix on `total > 0`, so a 0 is silently hidden rather than shown as "/0".
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE legend_meta ADD COLUMN scoringCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4→v5 (judge start/finish scan): creates `judge_scans` (see [JudgeScanEntity]), a new
         * table for judge-side start/finish piks, scoped by `raceId` only. Write-once rows, no
         * `updatedAt` guard. Column types/nullability and the index name must match exactly what Room
         * generates for the entity, or `runMigrationsAndValidate` fails against the committed schema.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `judge_scans` (
                        `id` TEXT NOT NULL,
                        `raceId` INTEGER NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `participantNumber` INTEGER NOT NULL,
                        `nfcUid` TEXT NOT NULL,
                        `takenAt` INTEGER NOT NULL,
                        `trustedTakenAt` INTEGER,
                        `elapsedRealtimeAt` INTEGER NOT NULL,
                        `bootCount` INTEGER,
                        `sourceInstallId` TEXT NOT NULL,
                        `uploadedLocal` INTEGER NOT NULL DEFAULT 0,
                        `uploadedCloud` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_judge_scans_raceId` ON `judge_scans` (`raceId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
