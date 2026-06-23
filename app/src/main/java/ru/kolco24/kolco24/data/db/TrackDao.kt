package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A distinct `(raceId, teamId)` pair with pending (un-uploaded) track points. Drives the
 * opportunistic re-send: the uploader iterates every scope from [TrackDao.pendingUploadScopes] so
 * points stranded under an old race/team still flush, not just the current selection.
 */
data class TrackScope(val raceId: Int, val teamId: Int)

@Dao
interface TrackDao {
    @Query("SELECT * FROM track_points WHERE teamId = :teamId ORDER BY elapsedRealtimeAt ASC")
    fun observeForTeam(teamId: Int): Flow<List<TrackPointEntity>>

    @Query("SELECT count(*) FROM track_points WHERE teamId = :teamId")
    fun countForTeam(teamId: Int): Flow<Int>

    // Every insert goes through Room and always supplies a value for the upload flags, so an
    // OnConflictStrategy.IGNORE keeps a re-delivered id (same client UUID) idempotent.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE teamId = :teamId")
    suspend fun deleteForTeam(teamId: Int)

    // Upload queries are scoped by (raceId, teamId): a batch goes to /race/<raceId>/track/, so it must
    // never sweep up another race/team's points and POST them to the wrong endpoint.
    @Query(
        "SELECT * FROM track_points WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedLocal = 0 ORDER BY elapsedRealtimeAt LIMIT :limit"
    )
    suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity>

    @Query(
        "SELECT * FROM track_points WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedCloud = 0 ORDER BY elapsedRealtimeAt LIMIT :limit"
    )
    suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity>

    @Query("UPDATE track_points SET uploadedLocal = 1 WHERE id IN (:ids)")
    suspend fun markUploadedLocal(ids: List<String>)

    @Query("UPDATE track_points SET uploadedCloud = 1 WHERE id IN (:ids)")
    suspend fun markUploadedCloud(ids: List<String>)

    // Every (raceId, teamId) pair that still has a point not yet delivered to one of the targets —
    // the opportunistic re-send walks all of them, not just the current selection.
    @Query(
        "SELECT DISTINCT raceId, teamId FROM track_points WHERE uploadedLocal = 0 OR uploadedCloud = 0"
    )
    suspend fun pendingUploadScopes(): List<TrackScope>
}
