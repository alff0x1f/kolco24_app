package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.kolco24.kolco24.data.db.MarkEntity

/**
 * Request body of `POST /app/race/<race_id>/marks/` — a batch of checkpoint-take events for one team.
 * Idempotent upsert by client [MarkDto.id], so re-sending an already-accepted batch is safe.
 *
 * Unlike [TrackUploadRequest], this body carries [sourceInstallId] (the device-provenance UUID, same
 * value as the `X-Install-Id` header but duplicated into the **signed** body) — required by the marks
 * contract so the server can dedupe two phones of one team and reattribute a wrong-team report later.
 */
@Serializable
data class MarkUploadRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("source_install_id") val sourceInstallId: String,
    val marks: List<MarkDto>,
)

/**
 * One checkpoint-take event on the wire. Field names differ from [MarkEntity] (do NOT map "1:1"
 * blindly): `cp_nfc_uid ← cpUid` (КП tag uid), `cp_code ← cpCode` (hex code), `wall_ms ← takenAt`
 * (the entity has **no** `wallMs` field), `trusted_ms ← trustedTakenAt`, `elapsed_at ← elapsedRealtimeAt`.
 */
@Serializable
data class MarkDto(
    val id: String,
    @SerialName("checkpoint_id") val checkpointId: Int,
    val method: String,
    @SerialName("cp_code") val cpCode: String,
    @SerialName("cp_nfc_uid") val cpNfcUid: String,
    val present: List<PresentMemberDto>,
    @SerialName("expected_count") val expectedCount: Int,
    val complete: Boolean,
    @SerialName("trusted_ms") val trustedMs: Long?,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("elapsed_at") val elapsedAt: Long?,
    @SerialName("boot_count") val bootCount: Int?,
    val location: TakeLocationDto? = null,
)

/**
 * One present team member on the wire, by **physical chip identity**. The server resolves
 * `uid/code → participant` against its own pool, not trusting the client [number]. A sentinel entry
 * (`nfc_uid = null`, `code = null`, `number = 0`) means "this slot is in `present` but no snapshot was
 * captured" (a legacy row written before `presentDetails` existed) — **not** a real participant number.
 */
@Serializable
data class PresentMemberDto(
    @SerialName("nfc_uid") val nfcUid: String?,
    val code: String?,
    val number: Int,
    @SerialName("number_in_team") val numberInTeam: Int,
)

/**
 * Anti-cheat take-place coordinate (nested so the fix's `gps_time_ms`/`elapsed_at` don't collide with
 * the take's own same-named times). Mapped from the 7 `loc*` columns of [MarkEntity]; the whole object
 * is `null` when no fix was obtained (`locLat == null`). [accuracy] and the "fix age"
 * (`mark.elapsed_at − location.elapsed_at`) are the key anti-cheat signals.
 */
@Serializable
data class TakeLocationDto(
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val altitude: Double?,
    @SerialName("vertical_accuracy") val verticalAccuracy: Float?,
    @SerialName("gps_time_ms") val gpsTimeMs: Long?,
    @SerialName("elapsed_at") val elapsedAt: Long?,
)

/** Response of `POST /app/race/<race_id>/marks/`: the client `id`s the server accepted (upserted). */
@Serializable
data class MarkUploadResponse(
    val accepted: List<String>,
)

/**
 * Pure entity → wire mapper. The `present[]` array is **merged over [MarkEntity.present]** (the scoring
 * truth) so no member is ever lost: every `numberInTeam` in `present` becomes a [PresentMemberDto],
 * enriched from the matching [MarkMemberSnapshot] when one exists and falling back to a sentinel
 * (`nfc_uid = null`, `number = 0`) when it doesn't. So a legacy row (`presentDetails == null`) still
 * uploads all its members as sentinels, and a partially-snapshotted row enriches only the slots it has.
 *
 * The anti-cheat [location] is built from the 7 `loc*` columns, or `null` when `locLat == null` (no fix).
 */
fun MarkEntity.toDto(): MarkDto {
    val byNum = presentDetails.orEmpty().associateBy { it.numberInTeam }
    val presentDtos = present.map { num ->
        byNum[num]?.let {
            PresentMemberDto(
                nfcUid = it.nfcUid,
                code = it.code,
                number = it.number,
                numberInTeam = it.numberInTeam,
            )
        } ?: PresentMemberDto(nfcUid = null, code = null, number = 0, numberInTeam = num)
    }
    val location = if (locLat != null && locLon != null) {
        TakeLocationDto(
            lat = locLat,
            lon = locLon,
            accuracy = locAccuracy,
            altitude = locAltitude,
            verticalAccuracy = locVerticalAccuracy,
            gpsTimeMs = locGpsTimeMs,
            elapsedAt = locElapsedRealtimeAt,
        )
    } else null
    return MarkDto(
        id = id,
        checkpointId = checkpointId,
        method = method,
        cpCode = cpCode,
        cpNfcUid = cpUid,
        present = presentDtos,
        expectedCount = expectedCount,
        complete = complete,
        trustedMs = trustedTakenAt,
        wallMs = takenAt,
        elapsedAt = elapsedRealtimeAt,
        bootCount = bootCount,
        location = location,
    )
}
