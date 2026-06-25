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
        // history was collapsed: the schema is re-created from scratch (a fresh install starts at
        // user_version 1; an existing build must be reinstalled / have its data cleared). Add migrations
        // here again — with a version bump and a committed schemas/<n>.json — once the app ships to
        // real users and on-device data must be preserved across upgrades.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            )
                .build()
    }
}
