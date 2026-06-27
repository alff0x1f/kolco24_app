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

/**
 * Upload progress for one `(raceId, teamId)` scope: [total] points stored, [local] of them already
 * delivered to the LAN target, [cloud] of them to the cloud target. Source of truth for the
 * "how much / where" half of the track-upload status row (the "when / what error" half is the
 * in-memory outcome). Derived from the durable `uploadedLocal`/`uploadedCloud` flags.
 */
data class UploadCounts(val total: Int, val local: Int, val cloud: Int)

@Dao
interface TrackDao {
    @Query(
        "SELECT * FROM track_points WHERE teamId = :teamId AND raceId = :raceId " +
            "ORDER BY COALESCE(trustedMs, wallMs), COALESCE(bootCount, -1), elapsedRealtimeAt, id"
    )
    fun observeForTeam(teamId: Int, raceId: Int): Flow<List<TrackPointEntity>>

    @Query("SELECT count(*) FROM track_points WHERE teamId = :teamId AND raceId = :raceId")
    fun countForTeam(teamId: Int, raceId: Int): Flow<Int>

    // Per-target upload progress for one scope. Explicit CASE over the Boolean column (SUM(boolean)
    // is fragile for codegen/type-mapping); COALESCE(...,0) guards the empty-scope NULL so the
    // non-null Int columns always map. Aliases match UploadCounts property names (Room maps by name).
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN uploadedLocal THEN 1 ELSE 0 END), 0) AS local, " +
            "COALESCE(SUM(CASE WHEN uploadedCloud THEN 1 ELSE 0 END), 0) AS cloud " +
            "FROM track_points WHERE teamId = :teamId AND raceId = :raceId"
    )
    fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts>

    // Every insert goes through Room and always supplies a value for the upload flags, so an
    // OnConflictStrategy.IGNORE keeps a re-delivered id (same client UUID) idempotent.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE teamId = :teamId AND raceId = :raceId")
    suspend fun deleteForTeam(teamId: Int, raceId: Int)

    // Upload queries are scoped by (raceId, teamId): a batch goes to /race/<raceId>/track/, so it must
    // never sweep up another race/team's points and POST them to the wrong endpoint.
    @Query(
        "SELECT * FROM track_points WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedLocal = 0 " +
            "ORDER BY COALESCE(trustedMs, wallMs), COALESCE(bootCount, -1), elapsedRealtimeAt, id " +
            "LIMIT :limit"
    )
    suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity>

    @Query(
        "SELECT * FROM track_points WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedCloud = 0 " +
            "ORDER BY COALESCE(trustedMs, wallMs), COALESCE(bootCount, -1), elapsedRealtimeAt, id " +
            "LIMIT :limit"
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
