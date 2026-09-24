package ru.kolco24.kolco24.ui.map

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.kolco24.kolco24.data.db.MarkEntity
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

/**
 * GeoJSON `FeatureCollection` of the track: a single `LineString` feature in the given (already
 * filtered + sorted) order, coordinates `[lon, lat]`. Fewer than 2 points → an empty collection
 * (a `LineString` needs at least two positions).
 */
fun trackGeoJson(points: List<TrackPointLike>): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            if (points.size >= 2) {
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {}
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            points.forEach { p ->
                                addJsonArray {
                                    add(p.lon)
                                    add(p.lat)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

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
