package ru.kolco24.kolco24.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    version = 1,
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
    abstract fun legendMetaDao(): LegendMetaDao
    abstract fun trackDao(): TrackDao

    companion object {
        // Schema baseline (v1). The app's only install is a dev device, so the entire v1→v11 migration
        // history was collapsed: the schema is re-created from scratch. Add migrations here again — with
        // a version bump and a committed schemas/<n>.json — once the app ships to real users and on-device
        // data must be preserved across upgrades.
        //
        // fallbackToDestructiveMigrationOnDowngrade: opening the v1 schema over an older-versioned DB
        // (e.g. a pre-collapse v11 db restored by Android Auto Backup on reinstall — `allowBackup=true`
        // and the backup rules do not exclude kolco24.db) wipes and recreates instead of crashing with
        // "A migration from 11 to 1 was required but not found". Scoped to DOWNGRADE only — a missing
        // *upgrade* migration must still fail loudly once real migrations return. dropAllTables=true
        // clears even tables Room no longer knows about. Local-only data (marks/bindings/tracks) is lost
        // on the wipe; races/teams/legend re-sync from the server.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
