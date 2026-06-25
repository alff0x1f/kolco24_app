package ru.kolco24.kolco24.data.track

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import ru.kolco24.kolco24.data.db.TrackPointEntity

/**
 * Pure, Android-free track models (mirrors `ScanSession.kt`/`CheckpointColor.kt`): a raw-fix value
 * type, the entity mapper, and the read-time metrics (length / accuracy filter). Kept off Android so
 * it stays JVM-unit-testable; the impure pieces (time anchoring, persistence) live in
 * `TrackRepository`.
 */

/**
 * A single raw location fix as it leaves a location engine — a pure geo value type with **no**
 * boot-session or trusted-time fields. The fix is always captured in the current boot session (the
 * recording service is running now), so `bootCount`/`wallMs`/`trustedMs` are injected by
 * `TrackRepository` at insert time rather than carried here. [elapsedRealtimeNanos] is the monotonic
 * moment of the fix (`Location.elapsedRealtimeNanos`) — the source of the trusted time per point, so
 * batched points keep their real capture order/time instead of the delivery time.
 *
 * [altitude] is meters above the WGS84 ellipsoid (`Location.altitude`) and [verticalAccuracyMeters]
 * its 1-sigma estimate (`Location.verticalAccuracyMeters`, API 26+); both are nullable because
 * `hasAltitude()`/`hasVerticalAccuracy()` can be false and a network-provider fix often has neither.
 */
data class RawFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val altitude: Double?,
    val verticalAccuracyMeters: Float?,
    val gpsTimeMs: Long,
    val elapsedRealtimeNanos: Long,
)

/**
 * The minimal read-side shape the pure metrics need ([TrackPointEntity] implements it). Decouples
 * [trackLengthMeters]/[filterPoints] from Room so they stay JVM-testable without a DB.
 */
interface TrackPointLike {
    val lat: Double
    val lon: Double
    val accuracy: Float
    val elapsedRealtimeAt: Long
}

/** Mean Earth radius (meters) used for the haversine length. */
private const val EARTH_RADIUS_M = 6_371_000.0

/** Default accuracy cutoff (meters) for [filterPoints] — coarse network fixes are dropped on read. */
const val DEFAULT_MAX_ACCURACY_METERS = 50f

/**
 * Great-circle distance (meters) between two lat/lon pairs via the haversine formula. Pure; used by
 * [trackLengthMeters].
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sinLat = sin(dLat / 2)
    val sinLon = sin(dLon / 2)
    val a = sinLat * sinLat +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLon * sinLon
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/**
 * Track length (meters) = sum of haversine distances between consecutive points ordered by
 * [TrackPointLike.elapsedRealtimeAt] (the monotonic capture moment, robust to delivery reordering).
 * An empty or single-point list is `0.0`.
 */
fun trackLengthMeters(points: List<TrackPointLike>): Double {
    if (points.size < 2) return 0.0
    val ordered = points.sortedBy { it.elapsedRealtimeAt }
    var total = 0.0
    for (i in 1 until ordered.size) {
        val a = ordered[i - 1]
        val b = ordered[i]
        total += haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }
    return total
}

/**
 * Drop coarse fixes ([TrackPointLike.accuracy] worse than [maxAccuracyMeters]) for display/length.
 * Read-time only — every fix is still stored raw in the DB. Generic so it preserves the caller's
 * element type.
 */
fun <T : TrackPointLike> filterPoints(
    points: List<T>,
    maxAccuracyMeters: Float = DEFAULT_MAX_ACCURACY_METERS,
): List<T> = points.filter { it.accuracy <= maxAccuracyMeters }

/**
 * Map a [RawFix] to a [TrackPointEntity]. The time fields are injected so the mapper stays
 * deterministic and unit-testable: [trustedMs] is computed by the caller from this fix's
 * [RawFix.elapsedRealtimeNanos] via `TrustedClock.trustedAt`, [wallMs] is the back-projected
 * wall-clock of the fix moment, [bootCount] is the current boot session, and [idFactory] supplies the
 * client UUID. [TrackPointEntity.elapsedRealtimeAt] is `elapsedRealtimeNanos / 1_000_000` (the same
 * millisecond monotonic scale as `TimeSample.elapsedMs`).
 */
fun RawFix.toTrackPoint(
    raceId: Int,
    teamId: Int,
    wallMs: Long,
    trustedMs: Long?,
    bootCount: Int?,
    idFactory: () -> String,
): TrackPointEntity = TrackPointEntity(
    id = idFactory(),
    raceId = raceId,
    teamId = teamId,
    lat = lat,
    lon = lon,
    accuracy = accuracy,
    altitude = altitude,
    verticalAccuracyMeters = verticalAccuracyMeters,
    gpsTimeMs = gpsTimeMs,
    elapsedRealtimeAt = elapsedRealtimeNanos / 1_000_000,
    bootCount = bootCount,
    wallMs = wallMs,
    trustedMs = trustedMs,
)
