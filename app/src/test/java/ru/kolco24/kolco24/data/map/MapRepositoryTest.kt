package ru.kolco24.kolco24.data.map

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MapRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storage: MapFileStorage

    /** Gate the fake download waits on; complete to finish, completeExceptionally to fail. */
    private var gate = CompletableDeferred<Unit>()
    private val calls = mutableListOf<Pair<String, Int>>()
    private var progressToReport: List<Pair<Long, Long>> = emptyList()

    @Before
    fun setUp() {
        storage = MapFileStorage(File(tmp.root, "maps"))
        storage.ensureRoot()
    }

    /** Commits a map of [size] bytes for [raceId] through the real `.part` → generation path. */
    private fun writeMap(raceId: Int, size: Int) {
        storage.partFile(raceId).writeBytes(ByteArray(size))
        check(storage.commit(raceId))
    }

    private val defaultDownload: suspend (String, Int, (Long, Long) -> Unit) -> Unit = { url, raceId, onProgress ->
        calls += url to raceId
        progressToReport.forEach { (read, total) -> onProgress(read, total) }
        gate.await()
        writeMap(raceId, 10)
    }

    private fun TestScope.repo(
        readMetadata: (File) -> MbtilesMetadata? = { null },
        download: suspend (String, Int, (Long, Long) -> Unit) -> Unit = defaultDownload,
    ) = MapRepository(
        storage = storage,
        download = download,
        scope = this,
        readMetadata = readMetadata,
    )

    @Test
    fun successGoesIdleDownloadingIdleAndAddsRace() = runTest {
        val r = repo()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertNull(r.downloaded.value) // seeded asynchronously, not at construction
        runCurrent()
        assertEquals(emptySet<Int>(), r.downloaded.value?.keys)

        r.download(5, "https://x/5.mbtiles")
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        runCurrent()
        assertEquals(listOf("https://x/5.mbtiles" to 5), calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(5), r.downloaded.value?.keys)
    }

    @Test
    fun progressIsReportedAsFraction() = runTest {
        progressToReport = listOf(50L to 200L)
        val r = repo()
        r.download(5, "u")
        runCurrent()
        assertEquals(MapDownloadState.Downloading(5, 0.25f), r.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun unknownLengthKeepsIndeterminateProgress() = runTest {
        progressToReport = listOf(50L to -1L)
        val r = repo()
        r.download(5, "u")
        runCurrent()
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun failureGoesFailedThenConsumeFailureIdle() = runTest {
        writeMap(1, 3)
        val r = repo()
        r.download(5, "u")
        runCurrent()
        gate.completeExceptionally(IOException("HTTP 404"))
        advanceUntilIdle()
        assertEquals(MapDownloadState.Failed(5, "HTTP 404"), r.state.value)
        assertEquals(setOf(1), r.downloaded.value?.keys)

        r.consumeFailure()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(1), r.downloaded.value?.keys)
    }

    @Test
    fun downloadWhileDownloadingIsIgnored() = runTest {
        val r = repo()
        r.download(5, "a")
        runCurrent()
        r.download(5, "b")
        r.download(6, "c")
        runCurrent()
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        assertEquals(listOf("a" to 5), calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(5), r.downloaded.value?.keys)
    }

    @Test
    fun downloadAfterFailureIsAllowed() = runTest {
        val r = repo()
        r.download(5, "a")
        runCurrent()
        gate.completeExceptionally(IOException("boom"))
        advanceUntilIdle()
        assertTrue(r.state.value is MapDownloadState.Failed)

        gate = CompletableDeferred()
        r.download(5, "a")
        runCurrent()
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(5), r.downloaded.value?.keys)
    }

    @Test
    fun cancelGoesIdleAndLeavesDownloadedUnchanged() = runTest {
        val r = repo()
        r.download(5, "a")
        runCurrent()
        r.cancel()
        assertEquals(MapDownloadState.Idle, r.state.value)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(emptySet<Int>(), r.downloaded.value?.keys)

        // A new download after cancel works.
        gate = CompletableDeferred()
        r.download(6, "b")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(6), r.downloaded.value?.keys)
    }

    @Test
    fun deleteRemovesRaceFromDownloaded() = runTest {
        writeMap(5, 3)
        writeMap(6, 3)
        val r = repo()
        runCurrent()
        assertEquals(setOf(5, 6), r.downloaded.value?.keys)
        r.delete(5)
        assertEquals(setOf(6), r.downloaded.value?.keys)
        assertNull(storage.file(5))
    }

    @Test
    fun deleteDuringDownloadOfSameRaceIsNoOp() = runTest {
        writeMap(5, 3)
        writeMap(6, 3)
        val r = repo()
        r.download(5, "a")
        runCurrent()
        r.delete(5)
        assertNotNull(storage.file(5))
        assertEquals(setOf(5, 6), r.downloaded.value?.keys)

        // Another race can still be deleted.
        r.delete(6)
        assertEquals(setOf(5), r.downloaded.value?.keys)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun downloadedIsSeededFromDiskAfterPartSweep() = runTest {
        writeMap(3, 1)
        storage.partFile(4).writeBytes(ByteArray(1))
        val r = repo()
        // Construction does no I/O.
        assertNull(r.downloaded.value)
        assertTrue(storage.partFile(4).isFile)
        runCurrent()
        assertEquals(setOf(3), r.downloaded.value?.keys)
        assertFalse(storage.partFile(4).isFile)
    }

    @Test
    fun downloadStartedBeforeSeedWaitsForPartSweep() = runTest {
        storage.partFile(4).writeBytes(ByteArray(1))
        val r = repo(
            download = { _, raceId, _ ->
                // The startup sweep already ran — a .part written now survives.
                storage.partFile(raceId).writeBytes(ByteArray(5))
                gate.await()
                assertTrue(storage.partFile(raceId).isFile)
                storage.commit(raceId)
            },
        )
        r.download(4, "u")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(4), r.downloaded.value?.keys)
        assertEquals(MapDownloadState.Idle, r.state.value)
    }

    @Test
    fun metadataPathSize() = runTest {
        val meta = MbtilesMetadata(Bounds(1.0, 2.0, 3.0, 4.0), 10, 15)
        val seen = mutableListOf<File>()
        val r = repo(readMetadata = { seen += it; meta })
        assertNull(r.metadata(File(tmp.root, "maps/5-1.mbtiles").absolutePath))
        assertNull(r.size(5))
        assertTrue(seen.isEmpty())

        writeMap(5, 7)
        advanceUntilIdle()
        val path = r.downloaded.value!!.getValue(5)
        assertEquals(storage.file(5)!!.absolutePath, path)
        assertEquals(meta, r.metadata(path))
        assertEquals(listOf(File(path)), seen)
        assertEquals(7L, r.size(5))
    }

    @Test
    fun redownloadOverExistingMapChangesPath() = runTest {
        writeMap(5, 3)
        val r = repo()
        advanceUntilIdle()
        val before = r.downloaded.value!!.getValue(5)
        r.download(5, "u")
        gate.complete(Unit)
        advanceUntilIdle()
        val after = r.downloaded.value!!.getValue(5)
        assertNotEquals(before, after) // MapLibre caches SQLite handles by path: a new URL is a must
        assertFalse(File(before).exists())
        assertEquals(10L, File(after).length())
    }

    @Test
    fun deleteThenRedownloadNeverReusesPath() = runTest {
        writeMap(5, 3)
        val r = repo()
        advanceUntilIdle()
        val before = r.downloaded.value!!.getValue(5)
        r.delete(5)
        assertEquals(emptySet<Int>(), r.downloaded.value?.keys)
        r.download(5, "u")
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotEquals(before, r.downloaded.value!!.getValue(5))
    }

    @Test
    fun cancelThenImmediateRedownloadWaitsForOldJobAndNeverFails() = runTest {
        // Real OkHttp: call.cancel() surfaces as IOException("Canceled") once the blocked read wakes up.
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val r = repo(
            download = { _, raceId, _ ->
                started += raceId
                if (raceId == 5) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                    throw IOException("Canceled")
                }
                gate.await()
                writeMap(raceId, 10)
            },
        )
        val states = mutableListOf<MapDownloadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.state.toList(states) }

        r.download(5, "a")
        runCurrent()
        r.cancel()
        r.download(6, "b")
        runCurrent()
        assertEquals(listOf(5), started) // race 6 waits for the cancelled job to unwind
        assertEquals(MapDownloadState.Downloading(6, null), r.state.value)

        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(5, 6), started)
        assertEquals(MapDownloadState.Downloading(6, null), r.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(6), r.downloaded.value?.keys)
        assertTrue(states.none { it is MapDownloadState.Failed })
    }

    @Test
    fun cancelLandingAfterCommitStillRefreshesDownloaded() = runTest {
        val r = repo(
            download = { _, raceId, _ ->
                writeMap(raceId, 10) // committed…
                gate.await() // …then cancelled before returning
            },
        )
        r.download(5, "a")
        runCurrent()
        r.cancel()
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(5), r.downloaded.value?.keys)
    }

    @Test
    fun progressIsThrottledToWholePercentSteps() = runTest {
        progressToReport = listOf(1L to 1000L, 2L to 1000L, 10L to 1000L, 11L to 1000L, 25L to 1000L)
        val r = repo()
        val states = mutableListOf<MapDownloadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.state.toList(states) }
        r.download(5, "u")
        runCurrent()
        assertEquals(
            listOf(
                MapDownloadState.Idle,
                MapDownloadState.Downloading(5, null),
                MapDownloadState.Downloading(5, 0.001f), // 0 %
                MapDownloadState.Downloading(5, 0.01f), // 1 %
                MapDownloadState.Downloading(5, 0.025f), // 2 %
            ),
            states,
        )
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun failureWithoutMessageFallsBackToExceptionName() = runTest {
        val r = repo()
        r.download(5, "u")
        runCurrent()
        gate.completeExceptionally(IOException())
        advanceUntilIdle()
        assertEquals(MapDownloadState.Failed(5, "IOException"), r.state.value)
    }

    @Test
    fun cancelWhileFailedKeepsFailure() = runTest {
        val r = repo()
        r.download(5, "u")
        runCurrent()
        gate.completeExceptionally(IOException("boom"))
        advanceUntilIdle()
        r.cancel()
        assertEquals(MapDownloadState.Failed(5, "boom"), r.state.value)
    }

    @Test
    fun consumeFailureWhileDownloadingIsNoOp() = runTest {
        val r = repo()
        r.download(5, "u")
        runCurrent()
        r.consumeFailure()
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
