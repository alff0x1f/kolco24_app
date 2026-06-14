package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE raceId = :raceId ORDER BY number, id")
    fun observeCheckpointsForRace(raceId: Int): Flow<List<CheckpointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoints(checkpoints: List<CheckpointEntity>)

    @Query("DELETE FROM checkpoints WHERE raceId = :raceId")
    suspend fun deleteCheckpointsForRace(raceId: Int)

    /** Full replacement of one race's checkpoints on a `200`: wipe then re-insert, atomically. */
    @Transaction
    suspend fun replaceAllForRace(raceId: Int, checkpoints: List<CheckpointEntity>) {
        deleteCheckpointsForRace(raceId)
        insertCheckpoints(checkpoints)
    }
}
