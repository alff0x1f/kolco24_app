package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A **local-only** record of one judge-side start/finish pik: a judge stationed at the start (or
 * finish) checkpoint taps participants' NFC bracelets to record their time, as an alternative source
 * of timing for when participants don't self-mark at the start/finish КП. Unlike [MarkEntity] (scoped
 * by `teamId` + `checkpointId`), a judge station scans across all teams of a race, so the scope is
 * [raceId] **only** — there is no team or checkpoint dimension.
 *
 * Rows are **write-once**: nothing mutates a scan after insert (no `updatedAt` version guard is
 * needed, a plain `UPDATE ... WHERE id = :id` marks it uploaded), and every pik produces a **new**
 * row — the server dedupes repeat piks, the client does not.
 *
 * [id] is a client-generated UUID so the two servers (local wifi + cloud) can merge databases
 * idempotently. [eventType] is fixed per admin sub-page (`"start"` | `"finish"`). [trustedTakenAt] is
 * the trusted server-anchored time preferred for ordering (falls back to [takenAt] when null, see
 * [ru.kolco24.kolco24.data.db.JudgeScanDao]). [elapsedRealtimeAt]/[bootCount] pair for a future
 * Δelapsed reconciliation, mirroring [MarkEntity.elapsedRealtimeAt]/[MarkEntity.bootCount] — but here
 * [elapsedRealtimeAt] is **non-null** ([ru.kolco24.kolco24.data.time.TrustedClock.TimeSample.elapsedMs]
 * is always available), while [bootCount] stays nullable (the boot count can fail to read).
 */
@Entity(
    tableName = "judge_scans",
    indices = [Index("raceId")],
)
data class JudgeScanEntity(
    @PrimaryKey val id: String,
    val raceId: Int,
    val eventType: String,
    val participantNumber: Int,
    val nfcUid: String,
    val takenAt: Long,
    val trustedTakenAt: Long? = null,
    val elapsedRealtimeAt: Long,
    val bootCount: Int? = null,
    val sourceInstallId: String,
    val uploadedLocal: Boolean = false,
    val uploadedCloud: Boolean = false,
)
