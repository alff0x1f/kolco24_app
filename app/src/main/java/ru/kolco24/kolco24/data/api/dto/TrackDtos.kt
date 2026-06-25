package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.kolco24.kolco24.data.db.TrackPointEntity

/**
 * Request body of `POST /app/race/<race_id>/track/` — a batch of GPS track points for one team.
 * Idempotent upsert by client [TrackPointDto.id], so re-sending an already-accepted batch is safe.
 */
@Serializable
data class TrackUploadRequest(
    @SerialName("team_id") val teamId: Int,
    val points: List<TrackPointDto>,
)

/**
 * One GPS track point on the wire. Times are pinned to the **moment of the fix** (see
 * [TrackPointEntity]): [segmentId] is the recording-session id (per «Начать запись» tap) the server
 * groups by so a stop→start gap isn't drawn as one line, [trustedMs] is the trusted server time
 * derived from [elapsedAt] (null when no
 * clock sync), [altitude]/[verticalAccuracyMeters] are the WGS84-ellipsoid elevation + estimate
 * (null when the fix has no vertical component), [gpsTimeMs] is the satellite-time hint, [elapsedAt] is the monotonic capture moment
 * (millis), and [bootCount] is the boot session of [elapsedAt]. The raw `wallMs` fallback is local
 * only and not uploaded — the server uses [trustedMs]/[gpsTimeMs].
 */
@Serializable
data class TrackPointDto(
    val id: String,
    @SerialName("segment_id") val segmentId: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val altitude: Double?,
    @SerialName("vertical_accuracy") val verticalAccuracyMeters: Float?,
    @SerialName("gps_time_ms") val gpsTimeMs: Long,
    @SerialName("trusted_ms") val trustedMs: Long?,
    @SerialName("elapsed_at") val elapsedAt: Long,
    @SerialName("boot_count") val bootCount: Int?,
)

/** Response of `POST /app/race/<race_id>/track/`: the client `id`s the server accepted (upserted). */
@Serializable
data class TrackUploadResponse(
    val accepted: List<String>,
)

/**
 * Pure entity → wire mapper. Drops the local-only `wallMs`/`raceId`/`teamId`/`uploaded*` fields (the
 * race/team are in the URL/envelope) and carries the fix-moment times the server scores on.
 */
fun TrackPointEntity.toDto(): TrackPointDto =
    TrackPointDto(
        id = id,
        segmentId = segmentId,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        altitude = altitude,
        verticalAccuracyMeters = verticalAccuracyMeters,
        gpsTimeMs = gpsTimeMs,
        trustedMs = trustedMs,
        elapsedAt = elapsedRealtimeAt,
        bootCount = bootCount,
    )
