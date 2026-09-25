package ru.kolco24.kolco24.ui.map

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.map.Bounds
import ru.kolco24.kolco24.data.map.MbtilesMetadata
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.data.track.TrackPointLike
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * One taken checkpoint on the map. КП coordinates never come from the server — the pin sits at the
 * take's own GPS fix ([MarkEntity.locLat]/[MarkEntity.locLon]).
 */
data class MapPin(
    val checkpointId: Int,
    val number: Int,
    val cost: Int,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
)

/**
 * Pins for the map: only **complete** takes **with** a GPS fix (a take without a fix is deliberately
 * not shown), one pin per `checkpointId` — the earliest take by `trustedTakenAt ?: takenAt`. The cost
 * prefers the legend's current [checkpointCosts] over the cost captured on the mark.
 */
fun mapPins(marks: List<MarkEntity>, checkpointCosts: Map<Int, Int>): List<MapPin> =
    marks.asSequence()
        .filter { it.complete && it.locLat != null && it.locLon != null }
        .groupBy { it.checkpointId }
        .values
        .map { group -> group.minBy { it.trustedTakenAt ?: it.takenAt } }
        .map { mark ->
            MapPin(
                checkpointId = mark.checkpointId,
                number = mark.checkpointNumber,
                cost = checkpointCosts[mark.checkpointId] ?: mark.cost,
                timeMs = mark.trustedTakenAt ?: mark.takenAt,
                lat = mark.locLat!!,
                lon = mark.locLon!!,
            )
        }
        .sortedBy { it.timeMs }

/** Property set on the track's single-point feature — the map view's dot layer filters on it. */
const val TRACK_DOT_PROPERTY = "dot"

/**
 * GeoJSON `FeatureCollection` of the track, coordinates `[lon, lat]`, [lines] = the output of
 * `trackLines` (nothing is drawn between lines, so a stop→start gap or an unreachable jump never
 * renders as a straight line):
 * - every line of 2+ points is one part of a single `MultiLineString` feature (drawn by the line layer);
 * - every 1-point line (the first fix of a live tail after a break, a kept short chain with an
 *   unreachable bypass) is one point of a single `MultiPoint` feature with property
 *   [TRACK_DOT_PROPERTY] `true` (drawn as a dot by the circle layer) — so every kept point is visible
 *   and the «на карте N» / «+N» counts match what the map draws.
 * Features in that order, each only when non-empty; empty lines are ignored; nothing → an empty
 * collection. Built with a plain [StringBuilder] — a day-long track is ~17k points and is rebuilt on
 * every GPS fix (the map view calls this off the main thread).
 */
fun trackGeoJson(lines: List<List<TrackPointLike>>): String {
    val polylines = lines.filter { it.size >= 2 }
    val dots = lines.mapNotNull { it.singleOrNull() }
    if (polylines.isEmpty() && dots.isEmpty()) return EMPTY_FEATURE_COLLECTION
    val sb = StringBuilder((polylines.sumOf { it.size } + dots.size) * 40 + 256)
    sb.append("""{"type":"FeatureCollection","features":[""")
    if (polylines.isNotEmpty()) {
        sb.append("""{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[""")
        polylines.forEachIndexed { li, line ->
            if (li > 0) sb.append(',')
            sb.append('[')
            line.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.appendLonLat(p)
            }
            sb.append(']')
        }
        sb.append("]}}")
    }
    if (dots.isNotEmpty()) {
        if (polylines.isNotEmpty()) sb.append(',')
        sb.append("""{"type":"Feature","properties":{"$TRACK_DOT_PROPERTY":true},""")
        sb.append(""""geometry":{"type":"MultiPoint","coordinates":[""")
        dots.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.appendLonLat(p)
        }
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun StringBuilder.appendLonLat(p: TrackPointLike) {
    append('[').append(p.lon).append(',').append(p.lat).append(']')
}

private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

/** Style-image name of a pin's icon (registered by the map view per checkpoint number). */
fun pinIconName(number: Int): String = "kp-$number"

/**
 * GeoJSON `FeatureCollection` of pins: one `Point` feature each, properties `id` (= `checkpointId`,
 * used by tap handling) and `icon` (= [pinIconName]).
 */
fun pinsGeoJson(pins: List<MapPin>): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            pins.forEach { pin ->
                add(pinFeature(pin))
            }
        }
    }.toString()

private fun pinFeature(pin: MapPin): JsonObject = buildJsonObject {
    put("type", "Feature")
    putJsonObject("properties") {
        put("id", pin.checkpointId)
        put("icon", pinIconName(pin.number))
    }
    putJsonObject("geometry") {
        put("type", "Point")
        putJsonArray("coordinates") {
            add(pin.lon)
            add(pin.lat)
        }
    }
}

/**
 * Bounding box of the track points and pins — the online-mode camera fit. `null` when there is no
 * position at all; a single position yields a zero-extent box.
 */
fun dataBounds(track: List<TrackPointLike>, pins: List<MapPin>): Bounds? {
    if (track.isEmpty() && pins.isEmpty()) return null
    var west = Double.POSITIVE_INFINITY
    var south = Double.POSITIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    fun visit(lat: Double, lon: Double) {
        if (lon < west) west = lon
        if (lon > east) east = lon
        if (lat < south) south = lat
        if (lat > north) north = lat
    }
    track.forEach { visit(it.lat, it.lon) }
    pins.forEach { visit(it.lat, it.lon) }
    return Bounds(west = west, south = south, east = east, north = north)
}

/** Initial camera of a freshly loaded style (see [cameraFrame]). */
sealed interface CameraFrame {
    /** Offline map with MBTiles `bounds`: clamp the camera target to them and frame them edge to edge. */
    data class FileBounds(val bounds: Bounds) : CameraFrame

    /** A single data position: center on it at a street-level zoom. */
    data class SinglePoint(val lat: Double, val lon: Double) : CameraFrame

    /** Track + pins: fit their box with padding. */
    data class FitData(val bounds: Bounds) : CameraFrame

    /** Nothing to frame: the last known location, else the default region. */
    data object NoData : CameraFrame
}

/** Camera choice: file bounds → single data point → data fit → no data. */
fun cameraFrame(fileBounds: Bounds?, dataBounds: Bounds?): CameraFrame = when {
    fileBounds != null -> CameraFrame.FileBounds(fileBounds)
    dataBounds == null -> CameraFrame.NoData
    dataBounds.west == dataBounds.east && dataBounds.south == dataBounds.north ->
        CameraFrame.SinglePoint(lat = dataBounds.north, lon = dataBounds.east)
    else -> CameraFrame.FitData(dataBounds)
}

/** Base layer of the map: the race's downloaded MBTiles file (+ its metadata), or online OSM tiles. */
sealed interface MapStyleSource {
    /** Absolute [path] of a downloaded `<raceId>-<generation>.mbtiles`; [metadata] read off-main by the host. */
    data class Offline(val path: String, val metadata: MbtilesMetadata?) : MapStyleSource
    data object Online : MapStyleSource
}

private const val BASE_SOURCE = "base"
private const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
private const val OSM_MAX_ZOOM = 19
private const val TILE_SIZE_PX = 256
const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/**
 * Style JSON with a single raster base layer (see «Итоги spike (Task 1)» in the map-tab plan):
 * offline → `mbtiles://<absolute path>` (MapLibre's MBTiles source handles the TMS y-flip itself);
 * online → the OSM tile template, `maxzoom` 19 and the attribution. Both set `tileSize` 256 explicitly —
 * MapLibre's raster default is 512 and both OSM and our MBTiles are 256 px tiles.
 */
fun styleJson(source: MapStyleSource): String = buildJsonObject {
    put("version", 8)
    putJsonObject("sources") {
        putJsonObject(BASE_SOURCE) {
            put("type", "raster")
            when (source) {
                is MapStyleSource.Offline -> put("url", "mbtiles://" + source.path)
                MapStyleSource.Online -> {
                    putJsonArray("tiles") { add(OSM_TILES) }
                    put("maxzoom", OSM_MAX_ZOOM)
                    put("attribution", OSM_ATTRIBUTION)
                }
            }
            put("tileSize", TILE_SIZE_PX)
        }
    }
    putJsonArray("layers") {
        addJsonObject {
            put("id", BASE_SOURCE)
            put("type", "raster")
            put("source", BASE_SOURCE)
        }
    }
}.toString()

/** «КП 32 · 4 балла · 14:07» — the take time rendered in [timeZone] (device TZ in prod). */
fun pinCaption(pin: MapPin, timeZone: TimeZone): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.US).apply { this.timeZone = timeZone }
    val points = pluralRu(pin.cost, "балл", "балла", "баллов")
    return "КП ${pin.number} · ${pin.cost} $points · ${fmt.format(Date(pin.timeMs))}"
}

private const val KB = 1024L
private const val MB = 1024L * 1024L

/**
 * Human map-file size: «820 КБ» below 1 МБ, «34 МБ» from there (both rounded to the nearest whole
 * unit). A size that would round up to «1024 КБ» is shown as «1 МБ»; a non-empty file never shows
 * «0 КБ».
 */
fun formatMapSize(bytes: Long): String {
    if (bytes <= 0) return "0 КБ"
    val kb = (bytes.toDouble() / KB).roundToLong().coerceAtLeast(1)
    if (kb < KB) return "$kb КБ"
    val mb = (bytes.toDouble() / MB).roundToLong().coerceAtLeast(1)
    return "$mb МБ"
}
