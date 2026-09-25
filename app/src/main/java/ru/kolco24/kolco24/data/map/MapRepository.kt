package ru.kolco24.kolco24.data.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File

/** Download state of the (single, app-wide) race-map download. */
sealed interface MapDownloadState {
    data object Idle : MapDownloadState

    /** [progress] in `0f..1f`, or `null` when the server sent no `Content-Length` (indeterminate). */
    data class Downloading(val raceId: Int, val progress: Float?) : MapDownloadState

    /** Sticky until [MapRepository.consumeFailure] (the UI shows a snackbar, then consumes it). */
    data class Failed(val raceId: Int, val message: String) : MapDownloadState
}

/**
 * Owner of the race-map download state and of the set of races with a map on disk.
 *
 * - One download at a time, app-wide: [download] is a no-op while any race is `Downloading`.
 * - The download runs on [scope] (prod: `applicationScope`, `Dispatchers.IO`) so it outlives the Map
 *   tab / composition.
 * - Construction does no I/O: a startup job on [scope] first sweeps stale `*.part` files (a process
 *   killed mid-download), then seeds [downloaded] from disk. Every download joins that job first, so
 *   the sweep can never delete a live download's `.part`.
 * - [downloaded] is `null` until that seed lands, then refreshed after every download attempt that
 *   may have committed a file and after [delete] — the single «file exists» source for the UI. It maps
 *   race id → absolute path of the current map; the path changes on every commit (see
 *   [MapFileStorage]), so a re-download re-keys the host's style even when the race set is unchanged.
 *
 * [download] is the network seam (prod: `MapDownloader::download`); [readMetadata] the SQLite seam
 * (prod: `readMbtilesMetadata`). Both lambdas keep this class JVM-testable.
 */
class MapRepository(
    private val storage: MapFileStorage,
    private val download: suspend (url: String, raceId: Int, onProgress: (Long, Long) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val readMetadata: (File) -> MbtilesMetadata?,
) {
    private val lock = Any()
    private var job: Job? = null

    private val _state = MutableStateFlow<MapDownloadState>(MapDownloadState.Idle)
    val state: StateFlow<MapDownloadState> = _state.asStateFlow()

    private val _downloaded = MutableStateFlow<Map<Int, String>?>(null)

    /** Race id → absolute path of its committed map; `null` until the startup disk listing completes. */
    val downloaded: StateFlow<Map<Int, String>?> = _downloaded.asStateFlow()

    private val startup: Job = scope.launch {
        runCatching { storage.sweep() }
        refreshDownloaded()
    }

    /** Starts downloading [url] as the map of [raceId]. No-op while any download is in flight. */
    fun download(raceId: Int, url: String) {
        synchronized(lock) {
            if (_state.value is MapDownloadState.Downloading) return
            // A just-cancelled job may still be deleting its `.part` — let it finish before we reuse it.
            val previous = job
            _state.value = MapDownloadState.Downloading(raceId, null)
            // LAZY + assign-then-start: `job` must already point at this coroutine when its body runs.
            // `synchronized` is reentrant, so an immediate/undispatched dispatcher would otherwise run
            // the body (and its setIfCurrent) on this thread, under this lock, before the assignment.
            val self = scope.launch(start = CoroutineStart.LAZY) {
                val me = coroutineContext.job
                startup.join()
                previous?.join()
                var lastPercent = -1
                try {
                    download(url, raceId) { read, total ->
                        val progress = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null
                        // Throttle to whole-percent steps (a 64 KB chunk each would flood recomposition).
                        val percent = progress?.let { (it * 100).toInt() } ?: -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setIfCurrent(me, MapDownloadState.Downloading(raceId, progress))
                        }
                    }
                    // Refresh before going Idle, so the UI never sees «Idle + not downloaded» in between.
                    refreshDownloaded()
                    setIfCurrent(me, MapDownloadState.Idle)
                } catch (e: CancellationException) {
                    // A cancel can land after the commit (e.g. on withContext's way out) — the file may exist.
                    refreshDownloaded()
                    setIfCurrent(me, MapDownloadState.Idle)
                    throw e
                } catch (e: Exception) {
                    setIfCurrent(me, MapDownloadState.Failed(raceId, e.message ?: e.javaClass.simpleName))
                }
            }
            job = self
            self.start()
        }
    }

    /** Cancels the in-flight download (the `.part` is deleted, a previous map stays). */
    fun cancel() {
        synchronized(lock) {
            val current = job ?: return
            current.cancel()
            if (_state.value is MapDownloadState.Downloading) _state.value = MapDownloadState.Idle
        }
    }

    /** Clears a sticky [MapDownloadState.Failed] back to `Idle`. */
    fun consumeFailure() {
        synchronized(lock) {
            if (_state.value is MapDownloadState.Failed) _state.value = MapDownloadState.Idle
        }
    }

    /**
     * Deletes the map of [raceId]. No-op while that race is downloading — the download would recreate
     * the file anyway. The check and the unlink share the lock, so a download of the same race can't
     * start in between and lose its `.part`. Blocking file I/O: call off the main thread.
     */
    fun delete(raceId: Int) {
        synchronized(lock) {
            val s = _state.value
            if (s is MapDownloadState.Downloading && s.raceId == raceId) return
            storage.delete(raceId)
            _downloaded.value = listing()
        }
    }

    /** MBTiles metadata of the map at [path] (from [downloaded]), or `null` when gone / unreadable. */
    fun metadata(path: String): MbtilesMetadata? = File(path).takeIf { it.isFile }?.let(readMetadata)

    /** Size in bytes of the committed map, or `null` when there is none. */
    fun size(raceId: Int): Long? = storage.size(raceId)

    /** Re-lists the maps dir. Under the lock so a stale listing can't overwrite a newer one. */
    private fun refreshDownloaded() {
        synchronized(lock) { _downloaded.value = listing() }
    }

    private fun listing(): Map<Int, String> = storage.listDownloaded().mapValues { it.value.absolutePath }

    private fun setIfCurrent(owner: Job, value: MapDownloadState) {
        synchronized(lock) {
            if (job === owner && _state.value is MapDownloadState.Downloading) _state.value = value
        }
    }
}
