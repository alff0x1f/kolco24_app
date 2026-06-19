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

    /** Persist an offline-unlocked checkpoint's plaintext and mark the row as revealed. */
    @Query("UPDATE checkpoints SET cost = :cost, description = :description, locked = 0 WHERE id = :id")
    suspend fun reveal(id: Int, cost: Int, description: String?)

    /** Flip a checkpoint to taken (scored). Called once a take's roster is complete. */
    @Query("UPDATE checkpoints SET taken = 1 WHERE id = :id")
    suspend fun markTaken(id: Int)

    /** Snapshot of the currently-taken checkpoint ids for a race — used to preserve `taken` across
     *  a resync (a taken CP may come back open or locked, either way it stays taken). */
    @Query("SELECT id FROM checkpoints WHERE raceId = :raceId AND taken = 1")
    suspend fun takenIdsForRace(raceId: Int): List<Int>

    /** One-shot snapshot of all checkpoints for a race — used for point-in-time reads (e.g. crypto). */
    @Query("SELECT * FROM checkpoints WHERE raceId = :raceId ORDER BY number, id")
    suspend fun getCheckpointsForRace(raceId: Int): List<CheckpointEntity>

    /**
     * Full replacement of one race's checkpoints on a `200`, **preserving prior reveals**
     * (option A): a refresh must not re-lock a checkpoint the user already unlocked offline.
     * Within the transaction, the prior revealed `cost`/`description` are captured first, then the
     * fresh server rows are wiped+reinserted, then any incoming still-`locked` row whose id was
     * previously revealed gets its plaintext re-applied. Open rows arrive with their content already,
     * so they overwrite cleanly.
     *
     * `taken` (= scored, set by a complete take) is preserved the same way: the prior taken ids are
     * snapshotted before the wipe and re-applied **unconditionally** afterwards — not inside the
     * `locked` reveal branch — because a taken CP can come back as an open row (`locked = false`) on
     * a resync, and it must keep `taken` regardless.
     */
    @Transaction
    suspend fun replaceAllForRace(raceId: Int, checkpoints: List<CheckpointEntity>) {
        val previouslyRevealed = revealedForRace(raceId).associateBy { it.id }
        val previouslyTaken = takenIdsForRace(raceId)
        deleteCheckpointsForRace(raceId)
        insertCheckpoints(checkpoints)
        for (incoming in checkpoints) {
            if (incoming.locked) {
                val prior = previouslyRevealed[incoming.id] ?: continue
                val cost = prior.cost ?: continue
                reveal(incoming.id, cost, prior.description)
            }
        }
        val incomingIds = checkpoints.mapTo(HashSet()) { it.id }
        for (id in previouslyTaken) {
            if (id in incomingIds) markTaken(id)
        }
    }
}
