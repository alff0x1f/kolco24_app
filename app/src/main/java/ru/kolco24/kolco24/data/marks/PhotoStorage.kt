package ru.kolco24.kolco24.data.marks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * On-disk storage for photo-mark frames. Frames live under `filesDir/marks/<markId>/<uuid>.jpg`; the
 * relative `marks/<markId>/<uuid>.jpg` form is what [encodePhotoPaths]/[photoPaths] persist in the
 * `marks.photoPath` column, and the absolute file is resolved against `filesDir` only here and at the
 * `AsyncImage` render site (a pure mapper never sees `filesDir`).
 *
 * The pure helpers ([scaledDimensions], [orphanPhotoDirs]) are JVM-unit-tested; the bitmap/file I/O
 * below is an Android adapter and untested by convention.
 */
object PhotoStorage {

    /** Longest edge of a saved frame; keeps the JPEG small enough to hash for the Phase-2 multipart upload. */
    const val MAX_EDGE_PX = 1600

    /** JPEG quality for the downscaled frame (~80%). */
    const val JPEG_QUALITY = 80

    /**
     * Longest edge of the tile thumbnail written next to each frame (`<uuid>.thumb.jpg`). Sized for
     * the «Отметки» grid tile (~90dp ≈ 270–330px on 3x screens; a 4:3 frame's short edge lands at
     * 384px, enough for a [android.widget.ImageView.ScaleType.CENTER_CROP]-style fill without blur).
     */
    const val THUMB_MAX_EDGE = 512

    /** JPEG quality for the thumbnail (~30–60KB per file). */
    const val THUMB_JPEG_QUALITY = 75

    private const val TAG = "PhotoStorage"

    /** The `marks/` root under `filesDir` (the parent of every per-take photo directory). */
    fun marksRoot(filesDir: File): File = File(filesDir, "marks")

    /** The per-take photo directory `filesDir/marks/<markId>/`. */
    fun photoDir(filesDir: File, markId: String): File = File(marksRoot(filesDir), markId)

    /** The relative path stored in the column: `marks/<markId>/<fileName>`. */
    fun relativePath(markId: String, fileName: String): String = "marks/$markId/$fileName"

    /**
     * Decode the captured JPEG [jpegBytes], rotate it by [rotationDegrees] (the `ImageCapture` EXIF
     * orientation — re-encoding loses EXIF, so the rotation is baked into the pixels), downscale so the
     * longest edge is ≤ [MAX_EDGE_PX], recompress at [JPEG_QUALITY], and write it to a fresh
     * `marks/<markId>/<uuid>.jpg`. Returns the **relative** path on success, or `null` if decode/write
     * failed (a corrupt frame is simply dropped — the caller never adds a null to the strip).
     *
     * Runs blocking; call from `Dispatchers.IO`.
     */
    fun writeDownscaledJpeg(
        filesDir: File,
        markId: String,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
    ): String? {
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (decoded == null) {
            Log.e(TAG, "Failed to decode captured JPEG for mark $markId")
            return null
        }
        val prepared = try {
            prepareBitmap(decoded, rotationDegrees)
        } catch (e: Exception) {
            decoded.recycle()
            Log.e(TAG, "Failed to prepare bitmap for mark $markId", e)
            return null
        }
        val dir = photoDir(filesDir, markId)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create photo dir ${dir.absolutePath}")
            if (prepared !== decoded) prepared.recycle()
            decoded.recycle()
            return null
        }
        val fileName = "${UUID.randomUUID()}.jpg"
        val file = File(dir, fileName)
        return try {
            FileOutputStream(file).use { out ->
                prepared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            // The frame is safely on disk; the thumb is best-effort (its own try/catch) — a failed
            // thumb never fails the capture, the tile just falls back to decoding the full frame.
            writeThumb(prepared, dir, fileName)
            relativePath(markId, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write photo ${file.absolutePath}", e)
            file.delete()
            null
        } finally {
            if (prepared !== decoded) prepared.recycle()
            decoded.recycle()
        }
    }

    /**
     * Write the `<uuid>.thumb.jpg` tile thumbnail next to its frame: [prepared] downscaled to
     * [THUMB_MAX_EDGE] at [THUMB_JPEG_QUALITY]. Never throws — a failure is logged and the partial
     * file deleted (the «Отметки» tile falls back to the full frame when the thumb is missing).
     */
    private fun writeThumb(prepared: Bitmap, dir: File, fileName: String) {
        val file = File(dir, thumbPathOf(fileName))
        try {
            val (w, h) = scaledDimensions(prepared.width, prepared.height, THUMB_MAX_EDGE)
            val thumb = if (w != prepared.width || h != prepared.height) {
                Bitmap.createScaledBitmap(prepared, w, h, true)
            } else {
                prepared
            }
            try {
                FileOutputStream(file).use { out ->
                    thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
                }
            } finally {
                if (thumb !== prepared) thumb.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write thumb ${file.absolutePath}", e)
            file.delete()
        }
    }

    /** Rotate [src] by [rotationDegrees] and downscale to [MAX_EDGE_PX]; returns [src] unchanged if neither applies. */
    private fun prepareBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val (w, h) = scaledDimensions(src.width, src.height, MAX_EDGE_PX)
        val needsScale = w != src.width || h != src.height
        val needsRotate = rotationDegrees % 360 != 0
        if (!needsScale && !needsRotate) return src
        val scaled = if (needsScale) Bitmap.createScaledBitmap(src, w, h, true) else src
        if (!needsRotate) return scaled
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        // Use try/finally so `scaled` is always recycled even if createBitmap throws (e.g. OOM).
        return try {
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    /**
     * Physically delete a single relative photo path **and its derived thumb**; missing files are a
     * no-op. Safe to call off the main thread.
     */
    fun deletePhoto(filesDir: File, relativePath: String) {
        if (!isSafeRelativePhotoPath(relativePath)) return
        File(filesDir, relativePath).delete()
        File(filesDir, thumbPathOf(relativePath)).delete()
    }

    /**
     * Startup sweep: delete every `marks/<id>/` directory whose `<id>` is not a live [MarkEntity] id.
     * Reclaims frames orphaned by process death mid-capture (the directory exists but the row was never
     * written). [knownMarkIds] is the full set of persisted mark ids.
     */
    fun sweepOrphanDirs(filesDir: File, knownMarkIds: Set<String>) {
        val root = marksRoot(filesDir)
        val dirNames = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: return
        for (name in orphanPhotoDirs(dirNames, knownMarkIds)) {
            File(root, name).deleteRecursively()
        }
    }

}

/**
 * Target dimensions so the longest edge is ≤ [maxEdge], preserving aspect ratio. An image already within
 * the cap (or a degenerate non-positive size) is returned unchanged. Pure — JVM-tested.
 */
fun scaledDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return w to h
}

/**
 * The subset of [dirNames] (each a `marks/` subdirectory name = a markId) that has no matching live
 * [MarkEntity] id in [knownMarkIds] — i.e. the orphan photo directories to delete on startup. Pure —
 * JVM-tested.
 */
fun orphanPhotoDirs(dirNames: List<String>, knownMarkIds: Set<String>): List<String> =
    dirNames.filter { it !in knownMarkIds }
