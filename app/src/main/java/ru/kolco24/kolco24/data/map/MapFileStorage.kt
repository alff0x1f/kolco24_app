package ru.kolco24.kolco24.data.map

import java.io.File

/**
 * On-disk home of per-race MBTiles basemaps: `<rootDir>/<raceId>.mbtiles`. The file itself is the
 * «downloaded» flag — no prefs. Downloads stream into `<raceId>.mbtiles.part` and [commit] renames it
 * over the final file, so a half-written download is never mistaken for a ready map.
 *
 * Production root is `noBackupFilesDir/maps/` (outside Auto Backup — a multi-MB map would blow the
 * 25 MB quota and take `kolco24.db` down with it). Pure `java.io` — JVM-tested.
 */
class MapFileStorage(private val rootDir: File) {

    fun file(raceId: Int): File = File(rootDir, "$raceId$EXT")

    fun partFile(raceId: Int): File = File(rootDir, "$raceId$EXT$PART")

    /** Creates the root directory if missing (downloads write into it). */
    fun ensureRoot(): File = rootDir.apply { mkdirs() }

    fun exists(raceId: Int): Boolean = file(raceId).isFile

    /** Size in bytes of the committed map, or `null` when there is none. */
    fun size(raceId: Int): Long? = file(raceId).takeIf { it.isFile }?.length()

    /** Deletes the committed map (and any stray `.part`). Returns `true` if no map remains. */
    fun delete(raceId: Int): Boolean {
        partFile(raceId).delete()
        val f = file(raceId)
        return !f.exists() || f.delete()
    }

    /** Race ids with a committed map. Ignores `.part` files and anything not named `<int>.mbtiles`. */
    fun listDownloaded(): Set<Int> =
        rootDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(EXT) }
            .mapNotNull { it.name.removeSuffix(EXT).toIntOrNull() }
            .toSet()

    /**
     * Atomically promotes `<raceId>.mbtiles.part` to `<raceId>.mbtiles`, replacing an existing map.
     * `renameTo` over an existing file is atomic on Linux; if it fails, falls back to delete + rename.
     * Returns `false` when there is no `.part` or both attempts fail.
     */
    fun commit(raceId: Int): Boolean {
        val part = partFile(raceId)
        if (!part.isFile) return false
        val target = file(raceId)
        if (part.renameTo(target)) return true
        target.delete()
        return part.renameTo(target)
    }

    /** Startup cleanup: deletes every `*.part` left behind by a process killed mid-download. */
    fun sweepParts() {
        rootDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PART) }
            .forEach { it.delete() }
    }

    private companion object {
        const val EXT = ".mbtiles"
        const val PART = ".part"
    }
}
