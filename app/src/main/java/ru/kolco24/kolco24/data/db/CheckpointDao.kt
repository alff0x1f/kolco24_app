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

    /** Snapshot of the currently-revealed (non-null `cost`) rows for a race — used to preserve
     *  unlocked content across a resync without re-locking it. */
    @Query("SELECT * FROM checkpoints WHERE raceId = :raceId AND cost IS NOT NULL")
    suspend fun revealedForRace(raceId: Int): List<CheckpointEntity>

    /** Persist an offline-unlocked checkpoint's plaintext. [locked] stays true (the row keeps its
     *  enc envelope); only the revealed [cost]/[description] are written. */
    @Query("UPDATE checkpoints SET cost = :cost, description = :description WHERE id = :id")
    suspend fun reveal(id: Int, cost: Int, description: String?)

    /**
     * Full replacement of one race's checkpoints on a `200`, **preserving prior reveals**
     * (option A): a refresh must not re-lock a checkpoint the user already unlocked offline.
     * Within the transaction, the prior revealed `cost`/`description` are captured first, then the
     * fresh server rows are wiped+reinserted, then any incoming still-`locked` row whose id was
     * previously revealed gets its plaintext re-applied. Open rows arrive with their content already,
     * so they overwrite cleanly.
     */
    @Transaction
    suspend fun replaceAllForRace(raceId: Int, checkpoints: List<CheckpointEntity>) {
        val previouslyRevealed = revealedForRace(raceId).associateBy { it.id }
        deleteCheckpointsForRace(raceId)
        insertCheckpoints(checkpoints)
        for (incoming in checkpoints) {
            if (incoming.cost == null) {
                val prior = previouslyRevealed[incoming.id] ?: continue
                val cost = prior.cost ?: continue
                reveal(incoming.id, cost, prior.description)
            }
        }
    }
}
