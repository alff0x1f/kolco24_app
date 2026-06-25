package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A **local-only** record of one checkpoint-taking event (взятие КП). The table is designed for a
 * future upload to two servers (local wifi + cloud): the [id] is a client-generated UUID so the two
 * servers can merge databases without key collisions, and `(cpUid, cpCode)` is an anti-cheat log that
 * lets the server reconcile the physical checkpoint tag after the fact.
 *
 * A row is created the moment the КП chip is scanned (so the take survives process death), then
 * [present] accumulates the `numberInTeam` of each member scanned within the rolling window. [complete]
 * (= counts for score) is set once `present` covers the whole roster ([expectedCount]); a partial
 * collect is stored for the server log but not scored. A repeat take of the same checkpoint produces a
 * **new** row (history for the server-side order). Upload flags are unindexed seeds — no upload queries
 * exist yet (an additive migration will add indices when they do).
 */
@Entity(
    tableName = "marks",
    indices = [Index("teamId"), Index("checkpointId")],
)
data class MarkEntity(
    @PrimaryKey val id: String,
    val raceId: Int,
    val teamId: Int,
    val checkpointId: Int,
    val checkpointNumber: Int,
    val cost: Int,
    val method: String,
    val cpUid: String,
    val cpCode: String,
    val present: List<Int>,
    val expectedCount: Int,
    val complete: Boolean,
    val photoPath: String? = null,
    val takenAt: Long,
    val updatedAt: Long,
    val uploadedLocal: Boolean = false,
    val uploadedCloud: Boolean = false,
    /**
     * Trusted take time (monotonic-anchored server time) — the scoring/order source. NULL on legacy
     * rows and whenever no clock sync has happened (the raw [takenAt] wall time is the fallback).
     */
    val trustedTakenAt: Long? = null,
    /**
     * Monotonic timestamp (`SystemClock.elapsedRealtime()`) at take time. **Nullable**: NULL on legacy
     * rows honestly distinguishes "no data" from a real `0` right after boot. Paired with [bootCount]
     * for forensic Δelapsed reconciliation.
     */
    val elapsedRealtimeAt: Long? = null,
    /**
     * Boot-session id (`Settings.Global.BOOT_COUNT`) of the [elapsedRealtimeAt] mark. Required so a
     * future Δelapsed reconciliation never mixes monotonic marks from different boot sessions (which
     * live on different timelines and would otherwise read as a false jump). NULL on legacy rows or
     * when the boot count could not be read.
     */
    val bootCount: Int? = null,
)
