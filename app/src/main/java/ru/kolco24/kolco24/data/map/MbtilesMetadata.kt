package ru.kolco24.kolco24.data.map

import java.util.Locale

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

/**
 * `true` when MapLibre Android 13.6.1's native `MBTilesFileSource` can open an MBTiles with this
 * `metadata` table without taking the process down. Its `request_tilejson` runs on a native worker
 * thread with no exception handling, so any C++ exception there aborts the app — and, the file being
 * committed, again on every later map open. Follows that code's shape:
 *
 * - `minzoom`/`maxzoom` go through `std::stoi` (empty → 0). When **both** are in [metadata] their
 *   values are used; when either is missing **both** are replaced by `MIN/MAX(zoom_level)` of `tiles`
 *   ([minTileZoom]/[maxTileZoom], `""` for SQL `NULL`).
 * - `scale`, when present, goes through `std::stod`.
 * - `bounds` uses `atof` (never throws) and a wrong arity is a clean error response; `json` is parsed
 *   leniently — neither is checked here.
 *
 * [hasTiles]: `tiles` has at least one row (also rejects an HTML captive-portal body / an empty map).
 * The schema-level crash conditions (no `metadata` table, fewer than two columns, `NULL` rows, a
 * `tiles` table missing a queried column) are the SQLite adapter's job (`validateMbtiles`).
 *
 * Deliberately **stricter** than the C++ parsers rather than a re-implementation of their prefix
 * semantics (a prefix match let `0x1p1024` through as `0`, while `strtod` reads the whole hex float
 * and overflows): the **whole** value, trimmed of C whitespace, must be a plain decimal — a zoom
 * `0..30` ([MAX_ZOOM]), a scale in `[1e-6, 1e6]` ([SCALE_RANGE], so no overflow/underflow/subnormal
 * `ERANGE`). Hex, sign, `inf`/`nan`, trailing garbage → rejected; such a map just can't be downloaded.
 */
fun isMbtilesLoadable(
    metadata: Map<String, String>,
    hasTiles: Boolean,
    minTileZoom: String,
    maxTileZoom: String,
): Boolean {
    if (!hasTiles) return false
    val minZoom = metadata["minzoom"]
    val maxZoom = metadata["maxzoom"]
    val zooms = if (minZoom != null && maxZoom != null) listOf(minZoom, maxZoom) else listOf(minTileZoom, maxTileZoom)
    if (!zooms.all { it.isEmpty() || isPlainZoom(it) }) return false
    val scale = metadata["scale"]
    return scale == null || isPlainScale(scale)
}

private const val MAX_ZOOM = 30
private val SCALE_RANGE = 1e-6..1e6

// C `isspace` in the "C" locale: space, \t, \n, \v, \f, \r (what strtol/strtod skip). Kotlin's
// `trim()` would also strip Unicode spaces (e.g. NBSP) that strtol does NOT skip → invalid_argument.
private fun String.trimCSpace(): String = trim { it in " \t\n\u000B\u000C\r" }

private val PLAIN_INT = Regex("\\d{1,9}")
private val PLAIN_DECIMAL = Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")

/** Whole value (C-whitespace trimmed) is a plain base-10 integer in `0..MAX_ZOOM` — safe for `std::stoi`. */
private fun isPlainZoom(raw: String): Boolean {
    val s = raw.trimCSpace()
    if (!PLAIN_INT.matches(s)) return false
    return s.toInt() in 0..MAX_ZOOM
}

/** Whole value (C-whitespace trimmed) is a plain decimal float in [SCALE_RANGE] — safe for `std::stod`. */
private fun isPlainScale(raw: String): Boolean {
    val s = raw.trimCSpace()
    if (!PLAIN_DECIMAL.matches(s)) return false
    val value = s.toDoubleOrNull() ?: return false
    return value.isFinite() && value in SCALE_RANGE
}

/**
 * Two-byte tile payload prefixes MapLibre Android 13.6.1's `util::is_compressed` treats as
 * compressed (only for payloads longer than two bytes): gzip `1F 8B` and zlib `78 01/5E/9C/DA`.
 * `request_tile` then calls `util::decompress`, which throws on a corrupt stream on a native worker
 * with no exception handling → the process aborts. The race basemap is **raster** (png/jpg/webp),
 * whose tiles never start with these bytes, so any such tile rejects the whole file.
 */
private val NATIVE_COMPRESSED_PREFIXES: List<Pair<Int, Int>> = listOf(
    0x1F to 0x8B, // gzip (rfc1952)
    0x78 to 0x01, // zlib (rfc1950), no/low compression
    0x78 to 0x5E, // zlib, fast
    0x78 to 0x9C, // zlib, default
    0x78 to 0xDA, // zlib, best
)

/** `true` when MapLibre's native reader would try to decompress this tile payload. */
fun isNativeCompressedTile(tile: ByteArray): Boolean {
    if (tile.size <= 2) return false
    val b0 = tile[0].toInt() and 0xFF
    val b1 = tile[1].toInt() and 0xFF
    return NATIVE_COMPRESSED_PREFIXES.any { (p0, p1) -> p0 == b0 && p1 == b1 }
}

/**
 * One-row probe for a tile [isNativeCompressedTile] would flag — the same rule as SQL, so the
 * adapter scans the `tiles` table without pulling payloads into the JVM. `CAST(... AS BLOB)` makes
 * `length`/`substr` byte-based even for a `TEXT` payload (native reads the column as raw bytes too).
 */
val COMPRESSED_TILE_PROBE_SQL: String = run {
    val blob = "CAST(tile_data AS BLOB)"
    val prefixes = NATIVE_COMPRESSED_PREFIXES.joinToString(", ") { (p0, p1) -> "X'%02X%02X'".format(Locale.US, p0, p1) }
    "SELECT 1 FROM tiles WHERE length($blob) > 2 AND substr($blob, 1, 2) IN ($prefixes) LIMIT 1"
}
