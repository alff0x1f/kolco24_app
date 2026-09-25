package ru.kolco24.kolco24.data.map

import android.database.sqlite.SQLiteDatabase
import java.io.File

// Android adapters over an MBTiles file (an SQLite database), opened READONLY. Untested by
// convention; the parsing half lives in the pure `parseMbtilesMetadata`.

/**
 * Reads the `metadata` table and parses it. Any failure (missing file, not SQLite, no `metadata`
 * table) → `null`; a corrupt map must never crash the map tab.
 */
fun readMbtilesMetadata(file: File): MbtilesMetadata? {
    if (!file.isFile) return null
    return runCatching {
        SQLiteDatabase.openDatabase(file.path, null, OPEN_FLAGS).use { db ->
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                val values = HashMap<String, String>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val value = c.getString(1) ?: continue
                    values[name] = value
                }
                parseMbtilesMetadata(values)
            }
        }
    }.getOrNull()
}

/**
 * `true` when [file] is an MBTiles that MapLibre's native `MBTilesFileSource` can open without
 * crashing the process (see [isMbtilesLoadable]): an SQLite database with a `metadata` table read
 * exactly as MapLibre reads it (`SELECT *`, columns 0/1 by position, no `NULL`s), a `tiles` table with
 * the columns MapLibre queries and at least one row, and numeric metadata its `std::stoi`/`std::stod`
 * accept, and no tile payload native would try to decompress ([isNativeCompressedTile] — one scan of
 * `tiles`, run before commit and once per committed map at startup). Rejects HTML
 * captive-portal/proxy bodies and empty maps too. Any failure → `false`.
 */
fun validateMbtiles(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching {
        SQLiteDatabase.openDatabase(file.path, null, OPEN_FLAGS).use { db ->
            val metadata = db.rawQuery("SELECT * FROM metadata", null).use { c ->
                if (c.columnCount < 2) return false
                val values = HashMap<String, String>()
                while (c.moveToNext()) {
                    // Native reads NULL as a null char* into std::string — never let one through.
                    if (c.isNull(0) || c.isNull(1)) return false
                    val name = c.getString(0)
                    // Native routes `json` to a lenient JSON parse, not into the values map.
                    if (name != "json") values[name] = c.getString(1)
                }
                values
            }
            val hasTiles = db.rawQuery(
                "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            val (minZoom, maxZoom) = db.rawQuery(
                "SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles",
                null,
            ).use { c ->
                if (!c.moveToFirst()) return false
                (c.getString(0) ?: "") to (c.getString(1) ?: "")
            }
            // Native gunzips/inflates any tile with a gzip/zlib magic and aborts the process on a
            // corrupt stream; a raster basemap never has one, so one full-table probe rejects it.
            val hasCompressedTile = db.rawQuery(COMPRESSED_TILE_PROBE_SQL, null).use { it.moveToFirst() }
            !hasCompressedTile && isMbtilesLoadable(metadata, hasTiles, minZoom, maxZoom)
        }
    }.getOrDefault(false)
}

// NO_LOCALIZED_COLLATORS: a foreign MBTiles has no `android_metadata` table; skip locale setup.
private const val OPEN_FLAGS = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
