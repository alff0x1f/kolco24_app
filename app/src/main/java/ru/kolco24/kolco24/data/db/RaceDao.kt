package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RaceDao {
    /** Offline source of truth for the UI. */
    @Query("SELECT * FROM races ORDER BY date DESC, id DESC")
    fun observeRaces(): Flow<List<RaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(races: List<RaceEntity>)

    @Query("DELETE FROM races")
    suspend fun deleteAll()

    /** Full replacement on a `200`: wipe then re-insert, atomically. */
    @Transaction
    suspend fun replaceAll(races: List<RaceEntity>) {
        deleteAll()
        insertAll(races)
    }
}
