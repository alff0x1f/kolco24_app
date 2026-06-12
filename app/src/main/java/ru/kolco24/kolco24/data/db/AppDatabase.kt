package ru.kolco24.kolco24.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RaceEntity::class, SyncMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kolco24.db",
            ).build()
    }
}
