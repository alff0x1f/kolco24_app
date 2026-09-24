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
 * - The download runs on [scope] (prod: `applicationScope`) so it outlives the Map tab / composition.
 * - [downloaded] is seeded from disk at construction and refreshed after a successful download and
 *   after [delete] — the single «file exists» source for the UI (no manual re-checks).
 *
 * [download] is the network seam (prod: `MapDownloader::download`); [readMetadata] the SQLite seam
 * (prod: `readMbtilesMetadata`). Both lambdas keep this class JVM-testable.
 */
class MapRepository(
    private val storage: MapFileStorage,
    private val download: suspend (url: String, raceId: Int, onProgress: (Long, Long) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val readMetadata: (File) -> MbtilesMetadata? = { null },
) {
    private val lock = Any()
    private var job: Job? = null

    private val _state = MutableStateFlow<MapDownloadState>(MapDownloadState.Idle)
    val state: StateFlow<MapDownloadState> = _state.asStateFlow()

    private val _downloaded = MutableStateFlow(storage.listDownloaded())
    val downloaded: StateFlow<Set<Int>> = _downloaded.asStateFlow()

    /** Starts downloading [url] as the map of [raceId]. No-op while any download is in flight. */
    fun download(raceId: Int, url: String) {
        synchronized(lock) {
            if (_state.value is MapDownloadState.Downloading) return
            // A just-cancelled job may still be deleting its `.part` — let it finish before we reuse it.
            val previous = job
            _state.value = MapDownloadState.Downloading(raceId, null)
            // LAZY + assign-then-start: `job` must already point at this coroutine when its body runs
            // (an immediate dispatcher would otherwise run it before the assignment).
            val self = scope.launch(start = CoroutineStart.LAZY) {
                val me = coroutineContext.job
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
                    _downloaded.value = storage.listDownloaded()
                    setIfCurrent(me, MapDownloadState.Idle)
                } catch (e: CancellationException) {
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
     * Deletes the map of [raceId]. No-op (returns `false`) while that race is downloading — the
     * download would recreate the file anyway. Blocking file I/O: call off the main thread.
     */
    fun delete(raceId: Int): Boolean {
        synchronized(lock) {
            val s = _state.value
            if (s is MapDownloadState.Downloading && s.raceId == raceId) return false
        }
        val ok = storage.delete(raceId)
        _downloaded.value = storage.listDownloaded()
        return ok
    }

    /** MBTiles metadata of the committed map, or `null` when there is none / it is unreadable. */
    fun metadata(raceId: Int): MbtilesMetadata? =
        storage.file(raceId).takeIf { it.isFile }?.let(readMetadata)

    /** Absolute path of the map file (whether or not it exists). */
    fun path(raceId: Int): String = storage.file(raceId).absolutePath

    /** Size in bytes of the committed map, or `null` when there is none. */
    fun size(raceId: Int): Long? = storage.size(raceId)

    private fun setIfCurrent(owner: Job, value: MapDownloadState) {
        synchronized(lock) {
            if (job === owner && _state.value is MapDownloadState.Downloading) _state.value = value
        }
    }
}
