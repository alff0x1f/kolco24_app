package ru.kolco24.kolco24.data.map

/** Geographic extent from MBTiles `metadata.bounds` («W,S,E,N», degrees). */
data class Bounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

/**
 * The subset of MBTiles `metadata` the map tab needs — camera framing and zoom limits only.
 * Tile addressing (TMS y-flip, TileJSON) is handled by MapLibre's `mbtiles://` source itself.
 */
data class MbtilesMetadata(
    val bounds: Bounds?,
    val minZoom: Int?,
    val maxZoom: Int?,
)

/**
 * Parses the MBTiles `metadata` name→value table. Never throws: a malformed or missing
 * field becomes `null` independently of the others.
 *
 * `bounds` must also be a geographically valid, non-antimeridian box with a non-zero extent
 * (`-90 <= S < N <= 90`, `-180 <= W < E <= 180`) — MapLibre's `LatLngBounds.from` throws on
 * anything else, and a crashing camera would make the map tab unopenable until the file is deleted.
 */
fun parseMbtilesMetadata(values: Map<String, String>): MbtilesMetadata = MbtilesMetadata(
    bounds = values["bounds"]?.let(::parseBounds),
    minZoom = values["minzoom"]?.trim()?.toIntOrNull(),
    maxZoom = values["maxzoom"]?.trim()?.toIntOrNull(),
)

private fun parseBounds(raw: String): Bounds? {
    val parts = raw.split(',')
    if (parts.size != 4) return null
    val nums = parts.map { part ->
        part.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    }
    val (west, south, east, north) = nums
    if (south < -90.0 || north > 90.0 || south >= north) return null
    if (west < -180.0 || east > 180.0 || west >= east) return null
    return Bounds(west = west, south = south, east = east, north = north)
}
