package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.kolco24.kolco24.data.track.TrackPointLike

/**
 * A **local-only** GPS track point recorded during a race. Like [MarkEntity] the table is designed for
 * a future dual upload (local wifi + cloud): [id] is a client-generated UUID so the two servers can
 * merge databases idempotently (upsert by id), and [uploadedLocal]/[uploadedCloud] are the
 * per-target delivery seeds.
 *
 * Time fields are pinned to the **moment of the fix**, not its delivery: [elapsedRealtimeAt]
 * (`Location.elapsedRealtimeNanos / 1_000_000`) is the monotonic capture moment and a same-boot
 * tie-breaker, [trustedMs] is the trusted server time derived from it via `TrustedClock.trustedAt`
 * (NULL when no clock sync yet). [altitude]/[verticalAccuracyMeters] are the WGS84-ellipsoid
 * elevation + its 1-sigma estimate, both nullable when the provider gives no vertical fix.
 * [wallMs] is the back-projected wall-clock of the fix moment (per
 * the batching design — not the wall time of the batch insert). [bootCount] is the boot session of
 * [elapsedRealtimeAt] so a future Δelapsed reconciliation never mixes monotonic marks across reboots.
 *
 * [segmentId] is a UUID minted once per recording session (per «Начать запись» tap) by the recording
 * service: a stop→start produces two distinct ids so the server can group `(race, team, install,
 * segment)` and draw a separate polyline per segment instead of teleporting across the gap.
 */
@Entity(
    tableName = "track_points",
    indices = [Index("teamId"), Index("raceId")],
)
data class TrackPointEntity(
    @PrimaryKey override val id: String,
    val raceId: Int,
    val teamId: Int,
    override val lat: Double,
    override val lon: Double,
    override val accuracy: Float,
    val altitude: Double?,
    val verticalAccuracyMeters: Float?,
    val gpsTimeMs: Long,
    override val elapsedRealtimeAt: Long,
    override val bootCount: Int?,
    override val wallMs: Long,
    override val trustedMs: Long?,
    val segmentId: String,
    val uploadedLocal: Boolean = false,
    val uploadedCloud: Boolean = false,
) : TrackPointLike
