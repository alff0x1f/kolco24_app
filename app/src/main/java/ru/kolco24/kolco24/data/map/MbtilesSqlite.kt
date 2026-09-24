package ru.kolco24.kolco24.data.map

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Android adapters over an MBTiles file (an SQLite database), opened READONLY. Untested by
 * convention; the parsing half lives in the pure [parseMbtilesMetadata].
 */

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
 * `true` when [file] is an SQLite database whose `tiles` table has at least one row. Rejects HTML
 * captive-portal/proxy bodies and empty maps before they are committed. Any failure → `false`.
 */
fun validateMbtiles(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching {
        SQLiteDatabase.openDatabase(file.path, null, OPEN_FLAGS).use { db ->
            db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { c -> c.moveToFirst() }
        }
    }.getOrDefault(false)
}

// NO_LOCALIZED_COLLATORS: a foreign MBTiles has no `android_metadata` table; skip locale setup.
private const val OPEN_FLAGS = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
