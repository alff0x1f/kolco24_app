package ru.kolco24.kolco24.data.map

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun TestScope.repo(
        readMetadata: (File) -> MbtilesMetadata? = { null },
    ) = MapRepository(
        storage = storage,
        download = { url, raceId, onProgress ->
            calls += url to raceId
            progressToReport.forEach { (read, total) -> onProgress(read, total) }
            gate.await()
            storage.file(raceId).writeBytes(ByteArray(10))
        },
        scope = this,
        readMetadata = readMetadata,
    )

    @Test
    fun successGoesIdleDownloadingIdleAndAddsRace() = runTest {
        val r = repo()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(emptySet<Int>(), r.downloaded.value)

        r.download(5, "https://x/5.mbtiles")
        assertEquals(MapDownloadState.Downloading(5, null), r.state.value)
        runCurrent()
        assertEquals(listOf("https://x/5.mbtiles" to 5), calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(5), r.downloaded.value)
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
        storage.file(1).writeBytes(ByteArray(3))
        val r = repo()
        r.download(5, "u")
        runCurrent()
        gate.completeExceptionally(IOException("HTTP 404"))
        advanceUntilIdle()
        assertEquals(MapDownloadState.Failed(5, "HTTP 404"), r.state.value)
        assertEquals(setOf(1), r.downloaded.value)

        r.consumeFailure()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(1), r.downloaded.value)
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
        assertEquals(setOf(5), r.downloaded.value)
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
        assertEquals(setOf(5), r.downloaded.value)
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
        assertEquals(emptySet<Int>(), r.downloaded.value)

        // A new download after cancel works.
        gate = CompletableDeferred()
        r.download(6, "b")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapDownloadState.Idle, r.state.value)
        assertEquals(setOf(6), r.downloaded.value)
    }

    @Test
    fun deleteRemovesRaceFromDownloaded() = runTest {
        storage.file(5).writeBytes(ByteArray(3))
        storage.file(6).writeBytes(ByteArray(3))
        val r = repo()
        assertEquals(setOf(5, 6), r.downloaded.value)
        assertTrue(r.delete(5))
        assertEquals(setOf(6), r.downloaded.value)
        assertFalse(storage.exists(5))
    }

    @Test
    fun deleteDuringDownloadOfSameRaceIsNoOp() = runTest {
        storage.file(5).writeBytes(ByteArray(3))
        storage.file(6).writeBytes(ByteArray(3))
        val r = repo()
        r.download(5, "a")
        runCurrent()
        assertFalse(r.delete(5))
        assertTrue(storage.exists(5))
        assertEquals(setOf(5, 6), r.downloaded.value)

        // Another race can still be deleted.
        assertTrue(r.delete(6))
        assertEquals(setOf(5), r.downloaded.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun downloadedIsSeededFromDisk() = runTest {
        storage.file(3).writeBytes(ByteArray(1))
        storage.partFile(4).writeBytes(ByteArray(1))
        val r = repo()
        assertEquals(setOf(3), r.downloaded.value)
    }

    @Test
    fun metadataPathSize() = runTest {
        val meta = MbtilesMetadata(Bounds(1.0, 2.0, 3.0, 4.0), 10, 15)
        val seen = mutableListOf<File>()
        val r = repo(readMetadata = { seen += it; meta })
        assertNull(r.metadata(5))
        assertNull(r.size(5))
        assertTrue(seen.isEmpty())

        storage.file(5).writeBytes(ByteArray(7))
        assertEquals(meta, r.metadata(5))
        assertEquals(listOf(storage.file(5)), seen)
        assertEquals(7L, r.size(5))
        assertEquals(storage.file(5).absolutePath, r.path(5))
    }
}
