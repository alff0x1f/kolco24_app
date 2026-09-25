package ru.kolco24.kolco24.data.map

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Streams a race's MBTiles basemap to `<raceId>.mbtiles.part`, validates it, then commits it as a
 * fresh generation (see [MapFileStorage]). Any failure (HTTP error, broken body, invalid file, cancellation) deletes the
 * `.part` and leaves a previously committed map untouched.
 *
 * [client] must be a plain OkHttp client — no HMAC signing / server-time interceptors (the map URL is a
 * static file, possibly on another host). [validate] is the SQLite seam (prod: `validateMbtiles`) that
 * rejects a captive-portal HTML page saved under the `.mbtiles` name. [usableSpace] is the free space of
 * the storage dir, checked against a known `Content-Length` before reading the body.
 */
class MapDownloader(
    private val client: OkHttpClient,
    private val storage: MapFileStorage,
    private val validate: (File) -> Boolean,
    private val usableSpace: () -> Long,
) {

    /**
     * Downloads [url] as the map of [raceId]. [onProgress] gets `(bytesRead, contentLength)`;
     * `contentLength <= 0` means unknown (indeterminate progress). Throws [IOException] on failure —
     * its message is always Russian and user-facing (it ends up in the «Не удалось скачать карту: …»
     * snackbar); raw OkHttp/network messages are mapped by [networkErrorMessage] and only logged —
     * [CancellationException] on cancellation.
     */
    suspend fun download(url: String, raceId: Int, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = storage.partFile(raceId)
            val call = client.newCall(Request.Builder().url(url).get().build())
            // Cancellation must abort a blocking connect/read stuck on a stalled socket: a sibling
            // watcher calls call.cancel() the moment this scope starts cancelling (ensureActive() in
            // the loop alone would wait for the next chunk — up to the 60 s read timeout).
            // UNDISPATCHED: the watcher is parked in awaitCancellation() before any blocking call.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            var committed = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw MapDownloadException("Ошибка сервера (HTTP ${response.code})")
                    // execute() always has a body; a short body with a known Content-Length makes
                    // OkHttp throw ProtocolException («unexpected end of stream») from read().
                    val body = checkNotNull(response.body)
                    val total = body.contentLength()
                    if (total > 0 && total > usableSpace()) throw MapDownloadException("Недостаточно места")
                    storage.ensureRoot()
                    var read = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                onProgress(read, total)
                            }
                        }
                    }
                }
                ensureActive()
                if (!validate(part)) throw MapDownloadException("Файл карты повреждён")
                if (!storage.commit(raceId)) throw MapDownloadException("Не удалось сохранить файл карты")
                committed = true
            } catch (e: IOException) {
                // call.cancel() surfaces as IOException("Canceled") — report it as the cancellation it is.
                ensureActive()
                if (e is MapDownloadException) throw e
                Log.w(TAG, "map download failed", e)
                throw IOException(networkErrorMessage(e), e)
            } finally {
                watcher.cancel()
                if (!committed) part.delete()
            }
        }

    /** A failure whose message is already user-facing Russian (passed through unmapped). */
    private class MapDownloadException(message: String) : IOException(message)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TAG = "MapDownloader"
    }
}

/**
 * User-facing Russian message for a raw network [IOException] (OkHttp's own messages are English:
 * «timeout», «Unable to resolve host …», «unexpected end of stream»).
 */
internal fun networkErrorMessage(e: IOException): String = when (e) {
    is UnknownHostException, is SocketTimeoutException, is ConnectException -> "Нет соединения"
    else -> "Ошибка сети"
}
