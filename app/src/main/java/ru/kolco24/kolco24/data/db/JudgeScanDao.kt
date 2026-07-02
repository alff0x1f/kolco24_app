package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface JudgeScanDao {
    @Insert
    suspend fun insert(scan: JudgeScanEntity)

    // Explicit `= :raceId` — a bare `WHERE raceId` reads the column as a truthy expression and
    // breaks the scope filter.
    @Query(
        "SELECT * FROM judge_scans WHERE raceId = :raceId AND uploadedLocal = 0 " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT :limit"
    )
    suspend fun unuploadedLocal(raceId: Int, limit: Int): List<JudgeScanEntity>

    @Query(
        "SELECT * FROM judge_scans WHERE raceId = :raceId AND uploadedCloud = 0 " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT :limit"
    )
    suspend fun unuploadedCloud(raceId: Int, limit: Int): List<JudgeScanEntity>

    // Write-once rows — no updatedAt version guard needed, unlike MarkDao's markUploaded*IfUnchanged.
    @Query("UPDATE judge_scans SET uploadedLocal = 1 WHERE id IN (:ids)")
    suspend fun markUploadedLocal(ids: List<String>)

    @Query("UPDATE judge_scans SET uploadedCloud = 1 WHERE id IN (:ids)")
    suspend fun markUploadedCloud(ids: List<String>)

    @Query(
        "SELECT DISTINCT raceId FROM judge_scans WHERE uploadedLocal = 0 OR uploadedCloud = 0"
    )
    suspend fun pendingUploadRaces(): List<Int>
}
