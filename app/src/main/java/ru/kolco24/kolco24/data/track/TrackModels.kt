package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.TrackPointEntity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, Android-free track models (mirrors `ScanSession.kt`/`CheckpointColor.kt`): a raw-fix value
 * type, the entity mapper, and the read-time spike filter [trackLines]. Kept off Android so it stays
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
 *
 * [isMock] (`Location.isMock`/`isFromMockProvider`) and [provider] (`Location.provider`) are carried
 * **only** for the trusted-time GPS-anchor filter (`gpsTimeCandidate`) — a mock fix or a non-`"gps"`
 * provider must not be allowed to set the trusted clock. They default so existing fixtures/call sites
 * are unaffected and play no part in track persistence.
 */
data class RawFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val altitude: Double?,
    val verticalAccuracyMeters: Float?,
    val gpsTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean = false,
    val provider: String? = null,
)

/**
 * The minimal read-side shape the pure read-time helpers need ([TrackPointEntity] implements it).
 * Decouples [trackLines] from Room so it stays JVM-testable without a DB. [segmentId] is the
 * recording-session id — [trackLines] never joins points across two sessions.
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
    val segmentId: String
}

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

/** Fixes reporting a worse accuracy (meters) than this are always dropped by [trackLines]. */
private const val HARD_CAP_ACCURACY_M = 500f

/** Top plausible speed (~50 km/h — covers a bike downhill) for the [trackLines] reachability test. */
private const val MAX_SPEED_MPS = 14f

/** A chain of at most this many points **and** shorter than [SHORT_CHAIN_MAX_DURATION_MS] is short. */
private const val SHORT_CHAIN_MAX_POINTS = 3

/** A chain spanning this long (or longer) is never short, whatever its point count. */
private const val SHORT_CHAIN_MAX_DURATION_MS = 60_000L

/** A short tail is dropped only when its median accuracy is at least this many times worse. */
private const val TAIL_ACCURACY_RATIO = 3f

/**
 * Floor (meters) of the reference median in the tail ratio — a long chain reporting accuracy 0
 * would otherwise make `>= 3 × 0` drop every short tail.
 */
private const val TAIL_REFERENCE_MIN_ACCURACY_M = 1f

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in meters between two WGS84 coordinates (haversine, spherical Earth). */
internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = toRadians(lat2 - lat1)
    val dLon = toRadians(lon2 - lon1)
    val sinLat = sin(dLat / 2)
    val sinLon = sin(dLon / 2)
    val h = sinLat * sinLat + cos(toRadians(lat1)) * cos(toRadians(lat2)) * sinLon * sinLon
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private fun toRadians(deg: Double): Double = deg * PI / 180.0

/**
 * Could the device have moved from [a] to [b]? The distance is reduced by the better of the two
 * accuracies (a noise allowance), the time gap is floored at 1 s, and the implied speed must not
 * exceed [MAX_SPEED_MPS] (inclusive). Time is [trackPointTimeMs] (`trustedMs ?: wallMs`). Internal for
 * the direct boundary tests.
 */
internal fun isReachable(a: TrackPointLike, b: TrackPointLike): Boolean {
    val d = haversineMeters(a.lat, a.lon, b.lat, b.lon)
    val dtMs = maxOf(abs(trackPointTimeMs(b) - trackPointTimeMs(a)), 1000L)
    val excess = maxOf(0.0, d - minOf(a.accuracy, b.accuracy))
    return excess / (dtMs / 1000.0) <= MAX_SPEED_MPS
}

/**
 * A chain is short (a removal candidate) when it has at most [SHORT_CHAIN_MAX_POINTS] points and
 * spans less than [SHORT_CHAIN_MAX_DURATION_MS]. A single point is always short.
 */
internal fun isShortChain(chain: List<TrackPointLike>): Boolean =
    chain.size <= SHORT_CHAIN_MAX_POINTS &&
        trackPointTimeMs(chain.last()) - trackPointTimeMs(chain.first()) < SHORT_CHAIN_MAX_DURATION_MS

private fun medianAccuracy(chain: List<TrackPointLike>): Float {
    val sorted = chain.map { it.accuracy }.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}

/**
 * Read-time spike filter: split already-[sortedTrackPoints]-ordered [points] into drawable **lines**.
 * Every transition inside a line is reachable ([isReachable]); nothing is drawn between lines. Every
 * fix is still stored raw in the DB (and uploaded raw) — this only shapes the map and the GPX.
 *
 * [filter] `false` («Все точки»): split into consecutive [TrackPointLike.segmentId] runs, nothing
 * else. [filter] `true`:
 * 1. drop fixes with accuracy worse than [HARD_CAP_ACCURACY_M];
 * 2. split into consecutive `segmentId` runs — lines never cross runs;
 * 3. cut each run into chains at every unreachable step; a single chain is one line as is;
 * 4. walk the chains left to right. The **head** is dropped if it is short and the next chain is
 *    long (a long chain proves the real track is elsewhere). A short **interior** chain is dropped if
 *    bypassing it is reachable from the last accepted point. A short **tail** is dropped only after
 *    a long chain whose median accuracy is at least [TAIL_ACCURACY_RATIO]× better
 *    (median accuracy of the long chain floored at [TAIL_REFERENCE_MIN_ACCURACY_M]) — otherwise the
 *    live tail stays visible (the next fix turns it into an interior chain with the bypass check);
 * 5. a kept chain joins the current line when reachable from its last point, else starts a new one.
 *
 * Accuracy never decides which point is right (a 68% estimate): it only feeds the hard cap, the
 * reachability noise allowance and the tail ratio. One deterministic pass. Known accepted misses
 * (rare; no jump is ever drawn, only an extra short line stays):
 * - two mutually unreachable spike chains in a row between long chains (`L X Y R`) both stay, each
 *   as its own line — neither bypass (`L→Y`, `X→R`) is reachable;
 * - a spike cluster split into two short chains at the head or tail survives: the head rule only
 *   looks at the next chain (short), the tail rule only after a kept long chain (`L n1 n2`: `n1`'s
 *   bypass `L→n2` is unreachable, `n2` follows a short chain).
 * Lines are never empty.
 */
fun <T : TrackPointLike> trackLines(points: List<T>, filter: Boolean): List<List<T>> {
    val input = if (filter) points.filter { it.accuracy <= HARD_CAP_ACCURACY_M } else points
    val runs = splitWhen(input) { a, b -> a.segmentId != b.segmentId }
    if (!filter) return runs
    return runs.flatMap(::runLines)
}

/** Lines for one `segmentId` run (see [trackLines] steps 3–5). */
private fun <T : TrackPointLike> runLines(run: List<T>): List<List<T>> {
    val chains = splitWhen(run) { a, b -> !isReachable(a, b) }
    if (chains.size == 1) return chains
    val lines = mutableListOf<MutableList<T>>()
    var prevKeptLong = false
    for ((i, chain) in chains.withIndex()) {
        val short = isShortChain(chain)
        val lastAccepted = lines.lastOrNull()?.last()
        val drop = when {
            i == 0 -> short && !isShortChain(chains[1])
            !short -> false
            i < chains.size - 1 -> lastAccepted != null && isReachable(lastAccepted, chains[i + 1].first())
            else -> prevKeptLong &&
                medianAccuracy(chain) >=
                TAIL_ACCURACY_RATIO * maxOf(medianAccuracy(chains[i - 1]), TAIL_REFERENCE_MIN_ACCURACY_M)
        }
        prevKeptLong = !drop && !short
        if (drop) continue
        val current = lines.lastOrNull()
        if (current != null && isReachable(current.last(), chain.first())) {
            current.addAll(chain)
        } else {
            lines.add(chain.toMutableList())
        }
    }
    return lines
}

/** Split [items] into non-empty consecutive runs, cutting between neighbours where [cut] is true. */
private fun <T> splitWhen(items: List<T>, cut: (T, T) -> Boolean): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val out = mutableListOf<MutableList<T>>(mutableListOf(items[0]))
    for (i in 1 until items.size) {
        if (cut(items[i - 1], items[i])) out.add(mutableListOf(items[i])) else out.last().add(items[i])
    }
    return out
}

/**
 * The two independent upload targets a track flushes to: the LAN finish server («Финиш») and the cloud
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
