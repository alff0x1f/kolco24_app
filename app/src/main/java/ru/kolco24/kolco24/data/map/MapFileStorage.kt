package ru.kolco24.kolco24.data.map

import java.io.File

/**
 * On-disk home of per-race MBTiles basemaps: `<rootDir>/<raceId>-<generation>.mbtiles`. The file itself
 * is the «downloaded» flag — no prefs. Downloads stream into `<raceId>.mbtiles.part` and [commit] renames
 * it to a **fresh** generation, so a half-written download is never mistaken for a ready map.
 *
 * Why a generation in the name: MapLibre's native `MBTilesFileSource` caches an open SQLite handle per
 * path for the whole process (its `db_cache` is never closed; the source hangs off the process-wide
 * `FileSource` singleton). Re-using one path per race would keep drawing the unlinked old file's tiles
 * after a delete + re-download or a re-download over an existing map. A new path per download makes
 * the `mbtiles://` URL change, so MapLibre opens the new file. [generation]s are strictly increasing
 * per instance (wall clock, bumped past anything seen), so a path is never re-used within a process.
 *
 * Production root is `noBackupFilesDir/maps/` (outside Auto Backup — a multi-MB map would blow the
 * 25 MB quota and take `kolco24.db` down with it). Pure `java.io` — JVM-tested.
 */
class MapFileStorage(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var lastGeneration = 0L

    /** The race's current (newest) committed map, or `null` when there is none. */
    fun file(raceId: Int): File? = generations(raceId).maxByOrNull { it.first }?.second

    fun partFile(raceId: Int): File = File(rootDir, "$raceId$EXT$PART")

    /** Path of generation [generation] of the race's map (`internal` for tests). */
    internal fun generationFile(raceId: Int, generation: Long): File =
        File(rootDir, "$raceId$SEP$generation$EXT")

    /** Creates the root directory if missing (downloads write into it). */
    fun ensureRoot(): File = rootDir.apply { mkdirs() }

    /** Size in bytes of the current committed map, or `null` when there is none. */
    fun size(raceId: Int): Long? = file(raceId)?.length()

    /** Best-effort deletes every generation of the race's map (and any stray `.part`). */
    fun delete(raceId: Int) {
        partFile(raceId).delete()
        generations(raceId).forEach { it.second.delete() }
    }

    /** Race id → its current committed map. Ignores `.part` files and anything not a `<int>-<long>.mbtiles` file. */
    fun listDownloaded(): Map<Int, File> =
        committed()
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, files) -> files.maxBy { it.first }.second }

    /**
     * Promotes `<raceId>.mbtiles.part` to a fresh `<raceId>-<generation>.mbtiles` (`renameTo` within
     * one directory is `rename(2)`), then best-effort deletes the race's older generations. Returns
     * `false` when there is no `.part` or the rename fails (the previous map stays current).
     */
    fun commit(raceId: Int): Boolean {
        val part = partFile(raceId)
        if (!part.isFile) return false
        val existing = generations(raceId)
        val generation = synchronized(lock) {
            maxOf(clock(), lastGeneration + 1, (existing.maxOfOrNull { it.first } ?: 0L) + 1)
                .also { lastGeneration = it }
        }
        if (!part.renameTo(generationFile(raceId, generation))) return false
        existing.forEach { it.second.delete() }
        return true
    }

    /**
     * Startup cleanup: deletes every `*.part` left behind by a process killed mid-download, and every
     * superseded generation left behind by a process killed between [commit]'s rename and its prune.
     */
    fun sweep() {
        val files = rootDir.listFiles().orEmpty()
        files.filter { it.isFile && it.name.endsWith(PART) }.forEach { it.delete() }
        val current = listDownloaded().values.toSet()
        committed().map { it.third }.filter { it !in current }.forEach { it.delete() }
    }

    /** `(generation, file)` of every committed generation of [raceId]. */
    private fun generations(raceId: Int): List<Pair<Long, File>> =
        committed().filter { it.first == raceId }.map { it.second to it.third }

    /** `(raceId, generation, file)` of every committed map file in the root. */
    private fun committed(): List<Triple<Int, Long, File>> =
        rootDir.listFiles().orEmpty().mapNotNull { f ->
            if (!f.isFile || !f.name.endsWith(EXT)) return@mapNotNull null
            val parts = f.name.removeSuffix(EXT).split(SEP)
            if (parts.size != 2) return@mapNotNull null
            val raceId = parts[0].toIntOrNull() ?: return@mapNotNull null
            val generation = parts[1].toLongOrNull() ?: return@mapNotNull null
            Triple(raceId, generation, f)
        }

    private companion object {
        const val EXT = ".mbtiles"
        const val PART = ".part"
        const val SEP = "-"
    }
}
