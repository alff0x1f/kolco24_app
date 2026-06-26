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

    /**
     * Attach the take-place GPS fix to an existing take. **Column-scoped** on purpose: it writes only
     * the 7 `loc*` columns and never touches `present`/`complete`/the take times. This runs
     * fire-and-forget on the application scope **in parallel** with the window's [addMember] calls, so a
     * full-row read-modify-write (like [addMember]) would lose-update — a late location write would roll
     * back `present`/`complete`, or an [addMember] would clobber the coordinate. A column-scoped `UPDATE`
     * serializes atomically around [addMember]'s `@Transaction` (SQLite won't interleave it) and touches
     * only the `loc*` columns, so there is no race. A missing row is a silent no-op.
     */
    @Query(
        "UPDATE marks SET locLat = :lat, locLon = :lon, locAccuracy = :accuracy, " +
            "locAltitude = :altitude, locVerticalAccuracy = :verticalAccuracy, " +
            "locGpsTimeMs = :gpsTimeMs, locElapsedRealtimeAt = :elapsedRealtimeAt WHERE id = :id",
    )
    suspend fun attachLocation(
        id: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        altitude: Double?,
        verticalAccuracy: Float?,
        gpsTimeMs: Long?,
        elapsedRealtimeAt: Long,
    )
}
