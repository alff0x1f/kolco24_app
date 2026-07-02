package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.kolco24.kolco24.data.db.JudgeScanEntity

/**
 * Request body of `POST /app/race/<race_id>/judge_scans/` — a batch of judge-side start/finish piks
 * for one race (all teams). Idempotent upsert by client [JudgeScanDto.id], so re-sending an
 * already-accepted batch is safe; the server dedupes repeat piks.
 */
@Serializable
data class JudgeScanUploadRequest(
    @SerialName("source_install_id") val sourceInstallId: String,
    val scans: List<JudgeScanDto>,
)

/**
 * One judge pik on the wire. Field names differ from [JudgeScanEntity]: `wall_ms ← takenAt`,
 * `trusted_ms ← trustedTakenAt`, `elapsed_at ← elapsedRealtimeAt`, `participant_number ← participantNumber`.
 */
@Serializable
data class JudgeScanDto(
    val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("participant_number") val participantNumber: Int,
    @SerialName("nfc_uid") val nfcUid: String,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("trusted_ms") val trustedMs: Long?,
    @SerialName("elapsed_at") val elapsedAt: Long,
    @SerialName("boot_count") val bootCount: Int?,
)

/** Response of `POST /app/race/<race_id>/judge_scans/`: the client `id`s the server accepted (upserted). */
@Serializable
data class JudgeScanUploadResponse(
    val accepted: List<String>,
)

/** Pure entity → wire mapper. */
fun JudgeScanEntity.toDto(): JudgeScanDto = JudgeScanDto(
    id = id,
    eventType = eventType,
    participantNumber = participantNumber,
    nfcUid = nfcUid,
    wallMs = takenAt,
    trustedMs = trustedTakenAt,
    elapsedAt = elapsedRealtimeAt,
    bootCount = bootCount,
)
