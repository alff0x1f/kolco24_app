package ru.kolco24.kolco24.data.map

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
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
        storage = MapFileStorage(File(tmp.root, "maps"))
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

        assertTrue(storage.exists(7))
        assertFalse(storage.partFile(7).exists())
        assertArrayEquals(payload, storage.file(7).readBytes())
        assertEquals(1, validateCalls)
        assertTrue(progress.isNotEmpty())
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b.first > a.first) }
        assertEquals(payload.size.toLong(), progress.last().first)
        progress.forEach { assertEquals(payload.size.toLong(), it.second) }
    }

    @Test
    fun http404ThrowsAndLeavesNoFile() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertFalse(storage.exists(7))
        assertFalse(storage.partFile(7).exists())
        assertEquals(0, validateCalls)
    }

    @Test
    fun disconnectMidBodyDeletesPartAndKeepsOldMap() {
        storage.ensureRoot()
        storage.file(7).writeText("old map")
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes(500_000)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        expectIOException { downloader().download(url(), 7) { _, _ -> } }
        assertFalse(storage.partFile(7).exists())
        assertEquals("old map", storage.file(7).readText())
        assertEquals(0, validateCalls)
    }

    @Test
    fun invalidFileThrowsAndLeavesNoFile() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        val e = expectIOException { downloader(valid = false).download(url(), 7) { _, _ -> } }
        assertEquals("Файл карты повреждён", e.message)
        assertEquals(1, validateCalls)
        assertFalse(storage.exists(7))
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
        assertFalse(storage.exists(7))
        assertFalse(storage.partFile(7).exists())
    }

    @Test
    fun cancellationDeletesPartAndKeepsOldMap() = runBlocking {
        storage.ensureRoot()
        storage.file(7).writeText("old map")
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
        assertEquals("old map", storage.file(7).readText())
        assertEquals(0, validateCalls)
    }
}
