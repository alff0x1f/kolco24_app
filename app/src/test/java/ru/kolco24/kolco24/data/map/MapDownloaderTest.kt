package ru.kolco24.kolco24.data.map

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var storage: MapFileStorage
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private var validateCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = MapFileStorage(File(tmp.root, "maps"), clock = { GENERATION })
        validateCalls = 0
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(
        valid: Boolean = true,
        space: Long = Long.MAX_VALUE,
    ) = MapDownloader(
        client = client,
        storage = storage,
        validate = { validateCalls++; valid },
        usableSpace = { space },
    )

    private fun url() = server.url("/maps/7.mbtiles").toString()

    /** Commits a previous map with [text] through the real `.part` → generation path. */
    private fun seedMap(text: String) {
        storage.ensureRoot()
        storage.partFile(7).writeText(text)
        check(storage.commit(7))
    }

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun expectIOException(block: suspend () -> Unit): IOException = runBlocking {
        try {
            block()
            fail("expected IOException")
            throw AssertionError()
        } catch (e: IOException) {
            e
        }
    }

    @Test
    fun successCommitsFileAndReportsMonotonicProgress() = runBlocking {
        val payload = bytes(200_000)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val progress = mutableListOf<Pair<Long, Long>>()

        downloader().download(url(), 7) { read, total -> progress += read to total }

        assertNotNull(storage.file(7))
        assertFalse(storage.partFile(7).exists())
        assertArrayEquals(payload, storage.file(7)!!.readBytes())
        assertEquals(1, validateCalls)
        assertTrue(progress.isNotEmpty())
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b.first > a.first) }
        assertEquals(payload.size.toLong(), progress.last().first)
        progress.forEach { assertEquals(payload.size.toLong(), it.second) }
    }

    @Test
    fun http404ThrowsAndLeavesNoFile() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val e = expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertEquals("Ошибка сервера (HTTP 404)", e.message)
        assertNull(storage.file(7))
        assertFalse(storage.partFile(7).exists())
        assertEquals(0, validateCalls)
    }

    @Test
    fun disconnectMidBodyDeletesPartAndKeepsOldMap() {
        storage.ensureRoot()
        seedMap("old map")
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes(500_000)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val e = expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertEquals("Ошибка сети", e.message)
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7)!!.readText())
        assertEquals(0, validateCalls)
    }

    @Test
    fun invalidFileThrowsAndLeavesNoFile() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        val e = expectIOException { downloader(valid = false).download(url(), 7) { _, _ -> } }
        assertEquals("Файл карты повреждён", e.message)
        assertEquals(1, validateCalls)
        assertNull(storage.file(7))
        assertFalse(storage.partFile(7).exists())
    }

    @Test
    fun contentLengthOverUsableSpaceThrowsBeforeReading() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(10_000))))
        var progressCalls = 0
        val e = expectIOException {
            downloader(space = 9_999).download(url(), 7) { _, _ -> progressCalls++ }
        }
        assertEquals("Недостаточно места", e.message)
        assertEquals(0, progressCalls)
        assertEquals(0, validateCalls)
        assertNull(storage.file(7))
        assertFalse(storage.partFile(7).exists())
    }

    @Test
    fun cancellationDeletesPartAndKeepsOldMap() = runBlocking {
        storage.ensureRoot()
        seedMap("old map")
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes(2_000_000)))
                .throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS),
        )
        val started = CompletableDeferred<Unit>()
        val job = launch {
            downloader().download(url(), 7) { _, _ -> started.complete(Unit) }
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7)!!.readText())
        assertEquals(0, validateCalls)
    }

    @Test
    fun cancellationAbortsReadStalledInSocket() = runBlocking {
        storage.ensureRoot()
        seedMap("old map")
        // First 64 KB arrive, then the body stalls for 3 s (kept under MockWebServer's 5 s shutdown wait).
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes(256 * 1024)))
                .throttleBody(64 * 1024, 3, TimeUnit.SECONDS),
        )
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            downloader().download(url(), 7) { _, _ -> started.complete(Unit) }
        }
        started.await()
        delay(200) // let the loop block inside read()
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        // Without call.cancel() on cancellation start, join waits ~3 s for the next chunk.
        assertTrue("cancel took $elapsedMs ms", elapsedMs < 1_000)
        assertTrue(job.isCancelled)
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7)!!.readText())
        assertEquals(0, validateCalls)
    }

    @Test
    fun cancellationDuringValidationDoesNotCommit() = runBlocking {
        storage.ensureRoot()
        seedMap("old map")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(10_000))))
        var validatedPart: File? = null
        lateinit var job: Job
        val d = MapDownloader(
            client = client,
            storage = storage,
            // The cancel arrives mid-scan; the (blocking) scan itself still finishes and passes.
            validate = { part ->
                validateCalls++
                validatedPart = part
                job.cancel()
                true
            },
            usableSpace = { Long.MAX_VALUE },
        )
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            d.download(url(), 7) { _, _ -> }
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, validateCalls)
        assertEquals(storage.partFile(7), validatedPart)
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7)!!.readText())
    }

    @Test
    fun unknownContentLengthSkipsSpaceCheckAndReportsUnknownTotal() = runBlocking {
        val payload = bytes(100_000)
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 8 * 1024))
        val progress = mutableListOf<Pair<Long, Long>>()

        downloader(space = 1).download(url(), 7) { read, total -> progress += read to total }

        assertArrayEquals(payload, storage.file(7)!!.readBytes())
        assertTrue(progress.isNotEmpty())
        progress.forEach { assertTrue(it.second <= 0) }
        assertEquals(payload.size.toLong(), progress.last().first)
    }

    @Test
    fun truncatedBodyWithKnownLengthThrowsAndKeepsOldMap() {
        storage.ensureRoot()
        seedMap("old map")
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes(1_000)))
                .setHeader("Content-Length", 5_000)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7)!!.readText())
        assertEquals(0, validateCalls)
    }

    @Test
    fun commitFailureThrowsAndDeletesPart() {
        // A non-empty directory where the map file should go: the rename cannot replace it.
        val target = storage.generationFile(7, GENERATION)
        target.mkdirs()
        File(target, "keep").writeText("x")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(1_000))))
        val e = expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertEquals("Не удалось сохранить файл карты", e.message)
        assertEquals(1, validateCalls)
        assertFalse(storage.partFile(7).exists())
        assertTrue(File(target, "keep").exists())
        assertNull(storage.file(7))
    }

    @Test
    fun contentLengthEqualToUsableSpaceIsAllowed() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(10_000))))
        downloader(space = 10_000).download(url(), 7) { _, _ -> }
        assertEquals(10_000L, storage.file(7)!!.length())
    }

    @Test
    fun connectionRefusedMapsToNoConnection() {
        // A port nothing listens on: grab a free one, then release it.
        val port = ServerSocket(0).use { it.localPort }
        val dead = "http://127.0.0.1:$port/maps/7.mbtiles"
        val e = expectIOException { downloader().download(dead, 7) { _, _ -> } }
        assertEquals("Нет соединения", e.message)
        assertFalse(storage.partFile(7).exists())
    }

    @Test
    fun networkErrorMessageIsRussian() {
        assertEquals("Нет соединения", networkErrorMessage(UnknownHostException("Unable to resolve host")))
        assertEquals("Нет соединения", networkErrorMessage(SocketTimeoutException("timeout")))
        assertEquals("Нет соединения", networkErrorMessage(ConnectException("Failed to connect")))
        assertEquals("Ошибка сети", networkErrorMessage(IOException("unexpected end of stream")))
    }

    private companion object {
        const val GENERATION = 1_000L
    }
}
