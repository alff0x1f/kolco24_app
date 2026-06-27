package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.TrackPointEntity

/**
 * Pure, Android-free track models (mirrors `ScanSession.kt`/`CheckpointColor.kt`): a raw-fix value
 * type, the entity mapper, and the read-time accuracy filter. Kept off Android so it stays
 * JVM-unit-testable; the impure pieces (time anchoring, persistence) live in `TrackRepository`.
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
 * The minimal read-side shape the pure read-time helpers need ([TrackPointEntity] implements it).
 * Decouples [filterPoints] from Room so it stays JVM-testable without a DB.
 */
interface TrackPointLike {
    val id: String
    val lat: Double
    val lon: Double
    val accuracy: Float
    val elapsedRealtimeAt: Long
    val bootCount: Int?
    val wallMs: Long
    val trustedMs: Long?
}

/** Default accuracy cutoff (meters) for [filterPoints] — coarse network fixes are dropped on read. */
const val DEFAULT_MAX_ACCURACY_METERS = 50f

/**
 * Drop coarse fixes ([TrackPointLike.accuracy] worse than [maxAccuracyMeters]) for display.
 * Read-time only — every fix is still stored raw in the DB. Generic so it preserves the caller's
 * element type.
 */
fun <T : TrackPointLike> filterPoints(
    points: List<T>,
    maxAccuracyMeters: Float = DEFAULT_MAX_ACCURACY_METERS,
): List<T> = points.filter { it.accuracy <= maxAccuracyMeters }

/** The display/export/upload order: absolute fix time first, monotonic time only as a tie-breaker. */
fun trackPointTimeMs(point: TrackPointLike): Long = point.trustedMs ?: point.wallMs

/** Reboot-safe order for track points. `elapsedRealtimeAt` resets on device reboot, so it is not first. */
fun <T : TrackPointLike> trackPointComparator(): Comparator<T> =
    compareBy<T> { trackPointTimeMs(it) }
        .thenBy { it.bootCount ?: -1 }
        .thenBy { it.elapsedRealtimeAt }
        .thenBy { it.id }

fun <T : TrackPointLike> sortedTrackPoints(points: List<T>): List<T> =
    points.sortedWith(trackPointComparator())

/**
 * The two independent upload targets a track flushes to: the LAN server («Локальный») and the cloud
 * HTTPS server («Интернет»). Pure so the upload-status model stays JVM-testable.
 */
enum class UploadTarget { Local, Cloud }

/** The terminal outcome of one target's flush attempt: clean drain / no network / any other error. */
enum class UploadResultKind { Ok, Offline, Error }

/** One target's last flush outcome with the wall-clock instant it was recorded (for «N мин назад»). */
data class TargetUploadOutcome(val kind: UploadResultKind, val atWallMs: Long)

/**
 * Map a non-`null` [PostResult] to the coarse [UploadResultKind] the status row shows: a clean
 * [PostResult.Success] → [UploadResultKind.Ok], [PostResult.Offline] → [UploadResultKind.Offline],
 * any other failure → [UploadResultKind.Error]. Shared by `TrackRepository` and its tests so the
 * mapping lives in one place. NB: a `Success` with **no forward progress** is the repo's concern
 * (it must hand-map to `Error`) — this mapper treats any `Success` as `Ok`.
 */
fun uploadResultKind(result: PostResult<*>): UploadResultKind = when (result) {
    is PostResult.Success -> UploadResultKind.Ok
    PostResult.Offline -> UploadResultKind.Offline
    else -> UploadResultKind.Error
}

/**
 * Map a [RawFix] to a [TrackPointEntity]. The time fields are injected so the mapper stays
 * deterministic and unit-testable: [trustedMs] is computed by the caller from this fix's
 * [RawFix.elapsedRealtimeNanos] via `TrustedClock.trustedAt`, [wallMs] is the back-projected
 * wall-clock of the fix moment, [bootCount] is the current boot session, [segmentId] is the
 * recording-session id (one per «Начать запись»), and [idFactory] supplies the client UUID.
 * [TrackPointEntity.elapsedRealtimeAt] is `elapsedRealtimeNanos / 1_000_000` (the same millisecond
 * monotonic scale as `TimeSample.elapsedMs`).
 */
fun RawFix.toTrackPoint(
    raceId: Int,
    teamId: Int,
    wallMs: Long,
    trustedMs: Long?,
    bootCount: Int?,
    segmentId: String,
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
    segmentId = segmentId,
)
