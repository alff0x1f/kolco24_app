package ru.kolco24.kolco24.data.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Streams a race's MBTiles basemap to `<raceId>.mbtiles.part`, validates it, then [MapFileStorage.commit]s
 * it over the final file. Any failure (HTTP error, broken body, invalid file, cancellation) deletes the
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
     * `contentLength <= 0` means unknown (indeterminate progress). Throws [IOException] on failure,
     * [CancellationException] on cancellation.
     */
    suspend fun download(url: String, raceId: Int, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = storage.partFile(raceId)
            val call = client.newCall(Request.Builder().url(url).get().build())
            // call.cancel() unblocks a read stuck in the socket; ensureActive() in the loop catches the rest.
            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            var committed = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Пустой ответ")
                    val total = body.contentLength()
                    if (total > 0 && total > usableSpace()) throw IOException("Недостаточно места")
                    storage.ensureRoot()
                    var read = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                onProgress(read, total)
                            }
                        }
                    }
                    coroutineContext.ensureActive()
                    if (total > 0 && read != total) throw IOException("Файл карты скачан не полностью")
                }
                if (!validate(part)) throw IOException("Файл карты повреждён")
                if (!storage.commit(raceId)) throw IOException("Не удалось сохранить файл карты")
                committed = true
            } finally {
                cancelHandle.dispose()
                if (!committed) part.delete()
            }
        }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
