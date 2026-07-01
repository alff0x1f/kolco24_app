package ru.kolco24.kolco24.data.marks

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Pure (Android-free apart from [Log]) codec for the JSON list of photo paths stored in the existing
 * `marks.photoPath` TEXT column. A photo take carries N captured frames; storing them as a JSON array
 * in the already-nullable column keeps Phase 1 **migration-free** (the column type stays TEXT) — the
 * count is then derived trivially from the parsed list, so no separate `photoCount` column is needed.
 *
 * Paths are stored **relative** to `filesDir` (`marks/<markId>/<uuid>.jpg`); the absolute file is
 * resolved at the `AsyncImage`/file-write site, never here (a pure mapper has no `filesDir`).
 */
private val json = Json { ignoreUnknownKeys = true }

/** JSON-encode a list of relative photo paths. Mirrors the Room JSON converters; order is preserved. */
fun encodePhotoPaths(paths: List<String>): String = json.encodeToString(paths)

/**
 * Decode the `photoPath` column into a list of **validated** relative paths. Never throws — `null`,
 * blank, malformed JSON, or a non-array all decode to [emptyList]. Each entry must match the expected
 * `marks/<markId>/<uuid>.jpg` shape; **absolute paths and any containing `..` are dropped** so that a
 * later `File(filesDir, relPath)` resolve can never escape `filesDir` (path traversal guard).
 */
fun photoPaths(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val decoded: List<String> = try {
        json.decodeFromString(raw)
    } catch (e: SerializationException) {
        Log.e("PhotoPaths", "Failed to decode photo paths JSON", e)
        return emptyList()
    } catch (e: IllegalArgumentException) {
        Log.e("PhotoPaths", "Failed to decode photo paths JSON", e)
        return emptyList()
    }
    return decoded.filter(::isSafeRelativePhotoPath)
}

/**
 * `marks/<markId>/<uuid>.jpg`: a 3-segment relative path under `marks/`, no absolute prefix, no `..`
 * traversal segment, ending in `.jpg`. Anything else is a corrupted/hostile entry and is dropped.
 */
internal fun isSafeRelativePhotoPath(path: String): Boolean {
    if (path.isBlank()) return false
    if (path.startsWith("/")) return false
    if (!path.endsWith(".jpg")) return false
    val segments = path.split("/")
    if (segments.size != 3) return false
    if (segments.first() != "marks") return false
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
    return true
}
