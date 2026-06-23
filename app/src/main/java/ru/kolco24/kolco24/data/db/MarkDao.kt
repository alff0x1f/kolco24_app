package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkDao {
    // Order by the scoring/take time: trusted time when present, else the raw wall time. After a
    // clock change the tiles render trusted time, so they must also sort by it (untrusted rows fall
    // back to wall — the best source available).
    @Query("SELECT * FROM marks WHERE teamId = :teamId ORDER BY COALESCE(trustedTakenAt, takenAt) DESC")
    fun observeForTeam(teamId: Int): Flow<List<MarkEntity>>

    @Query("SELECT * FROM marks WHERE id = :id")
    suspend fun getById(id: String): MarkEntity?

    @Upsert
    suspend fun upsert(mark: MarkEntity)

    /**
     * Add one member to an existing take with set semantics: if [numberInTeam] is already in
     * `present`, the row is untouched (idempotent rescan); otherwise it is appended, [complete] is
     * recomputed against [expectedCount], and [updatedAt] is bumped. The caller is responsible for the
     * resulting `taken` flip on the checkpoint (a `complete` row scores). A missing row is a no-op.
     */
    @Transaction
    suspend fun addMember(id: String, numberInTeam: Int, now: Long, expectedCount: Int) {
        val mark = getById(id) ?: return
        if (numberInTeam in mark.present) return
        val present = mark.present + numberInTeam
        upsert(
            mark.copy(
                present = present,
                expectedCount = expectedCount,
                complete = expectedCount > 0 && present.size >= expectedCount,
                updatedAt = now,
            ),
        )
    }
}
