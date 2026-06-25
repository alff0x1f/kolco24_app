package ru.kolco24.kolco24.data.track

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.TrackPointDto
import ru.kolco24.kolco24.data.api.dto.TrackUploadResponse
import ru.kolco24.kolco24.data.db.TrackDao
import ru.kolco24.kolco24.data.db.TrackPointEntity
import ru.kolco24.kolco24.data.db.TrackScope
import ru.kolco24.kolco24.data.time.ClockAnchor
import ru.kolco24.kolco24.data.time.TrustedClock

class TrackRepositoryTest {

    // Anchor: server epoch pinned to monotonic 50_000 ms in boot session 7.
    private val anchorServerMs = 1_700_000_000_000L
    private val anchorElapsedMs = 50_000L

    private fun trustedClock(boot: Int? = 7) = TrustedClock(
        elapsedProvider = { 100_000L },
        wallProvider = { 0L },
        bootCountProvider = { boot },
        persisted = ClockAnchor(
            serverEpochMs = anchorServerMs,
            anchorElapsedMs = anchorElapsedMs,
            capturedWallMs = anchorServerMs,
            bootCount = boot,
        ),
    )

    /** A repo whose batch clocks (`wallNow`/`elapsedNow`) and boot/id are deterministic. */
    private fun repo(
        dao: TrackDao,
        clock: TrustedClock = trustedClock(),
        boot: Int? = 7,
        wallNow: Long = 2_000_000L,
        elapsedNow: Long = 100_000L,
        cloud: TrackUploader = TrackUploader { _, _, _ -> PostResult.Offline },
        local: TrackUploader = TrackUploader { _, _, _ -> PostResult.Offline },
    ): TrackRepository {
        var n = 0
        return TrackRepository(
            trackDao = dao,
            trustedClock = clock,
            bootCountProvider = { boot },
            idFactory = { "id-${n++}" },
            wallProvider = { wallNow },
            elapsedProvider = { elapsedNow },
            cloudUploader = cloud,
            localUploader = local,
        )
    }

    /** Records calls and replies with [reply]; defaults to accepting every id in the batch. */
    private class FakeUploader(
        val reply: suspend (List<TrackPointDto>) -> PostResult<TrackUploadResponse> = { pts ->
            PostResult.Success(TrackUploadResponse(pts.map { it.id }))
        },
    ) : TrackUploader {
        var calls = 0
            private set

        override suspend fun upload(
            raceId: Int,
            teamId: Int,
            points: List<TrackPointDto>,
        ): PostResult<TrackUploadResponse> {
            calls++
            return reply(points)
        }
    }

    private fun rawFix(
        elapsedMs: Long,
        lat: Double = 55.0,
        lon: Double = 37.0,
        accuracy: Float = 10f,
        gpsTimeMs: Long = 1_718_900_000_000L,
    ) = RawFix(
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        altitude = null,
        verticalAccuracyMeters = null,
        gpsTimeMs = gpsTimeMs,
        elapsedRealtimeNanos = elapsedMs * 1_000_000L,
    )

    @Test
    fun insertAll_mapsAndStores_withInjectedProviders() = runTest {
        val dao = FakeTrackDao()
        repo(dao).insertAll(listOf(rawFix(elapsedMs = 60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        val points = dao.observeForTeam(7, 1).first()
        assertEquals(1, points.size)
        val p = points.single()
        assertEquals("id-0", p.id)
        assertEquals(1, p.raceId)
        assertEquals(7, p.teamId)
        assertEquals(60_000L, p.elapsedRealtimeAt) // nanos / 1e6
        assertEquals(7, p.bootCount)
        // trustedMs = serverEpochMs + (elapsedAt − anchorElapsedMs) = 1_700_000_000_000 + 10_000
        assertEquals(anchorServerMs + 10_000L, p.trustedMs)
        // wallMs = wallNow + (elapsedAt − elapsedNow) = 2_000_000 + (60_000 − 100_000)
        assertEquals(1_960_000L, p.wallMs)
        assertEquals("seg", p.segmentId)
    }

    @Test
    fun insertAll_stampsSegmentIdOnEveryRow() = runTest {
        val dao = FakeTrackDao()
        repo(dao).insertAll(
            listOf(rawFix(60_000L), rawFix(61_000L), rawFix(62_000L)),
            raceId = 1,
            teamId = 7,
            segmentId = "session-xyz",
        )
        val rows = dao.observeForTeam(7, 1).first()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.segmentId == "session-xyz" })
    }

    @Test
    fun insertAll_twoSessions_rowsRetainDistinctSegmentIds() = runTest {
        // The core stop→start guarantee: two insertAll calls with different segmentIds for the same
        // (raceId, teamId) scope must keep their own segmentId — no cross-contamination.
        val dao = FakeTrackDao()
        val r = repo(dao)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg-A")
        r.insertAll(listOf(rawFix(61_000L)), raceId = 1, teamId = 7, segmentId = "seg-B")
        val rows = dao.observeForTeam(7, 1).first()
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.segmentId == "seg-A" })
        assertEquals(1, rows.count { it.segmentId == "seg-B" })
    }

    @Test
    fun insertAll_emptyBatch_isNoOp() = runTest {
        val dao = FakeTrackDao()
        repo(dao).insertAll(emptyList(), raceId = 1, teamId = 7, segmentId = "seg")
        assertTrue(dao.observeForTeam(7, 1).first().isEmpty())
    }

    @Test
    fun insertAll_batchOfTwo_eachGetsOwnBackProjectedWallMs() = runTest {
        // The critical batching criterion: two fixes with DIFFERENT elapsedRealtimeNanos must get
        // wallMs differing by exactly Δelapsed, not the single batch-insert wall instant.
        val dao = FakeTrackDao()
        repo(dao).insertAll(
            listOf(rawFix(elapsedMs = 60_000L), rawFix(elapsedMs = 64_000L)),
            raceId = 1,
            teamId = 7,
            segmentId = "seg",
        )
        val points = dao.observeForTeam(7, 1).first() // ordered by elapsedRealtimeAt ASC
        assertEquals(listOf(60_000L, 64_000L), points.map { it.elapsedRealtimeAt })

        val (a, b) = points
        // Δelapsed = 4_000 → wall differs by exactly 4_000, trusted differs by exactly 4_000.
        assertEquals(4_000L, b.wallMs - a.wallMs)
        assertEquals(4_000L, b.trustedMs!! - a.trustedMs!!)
        assertEquals(1_960_000L, a.wallMs)
        assertEquals(1_964_000L, b.wallMs)
    }

    @Test
    fun insertAll_noClockAnchor_trustedMsNull_wallStillBackProjected() = runTest {
        // bootCountProvider null on the clock side → not verified → trustedAt returns null.
        val dao = FakeTrackDao()
        repo(dao, clock = trustedClock(boot = null), boot = null)
            .insertAll(listOf(rawFix(elapsedMs = 60_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        val p = dao.observeForTeam(7, 1).first().single()
        assertNull(p.trustedMs)
        assertNull(p.bootCount)
        assertEquals(1_960_000L, p.wallMs) // wall fallback still honest per-point
    }

    @Test
    fun count_observe_reflectInserts() = runTest {
        val dao = FakeTrackDao()
        val r = repo(dao)
        r.insertAll(listOf(rawFix(60_000L), rawFix(61_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        assertEquals(2, r.countForTeam(7, 1).first())
        assertEquals(2, r.observeTrack(7, 1).first().size)
    }

    @Test
    fun deleteForTeam_clearsOnlyThatTeam() = runTest {
        val dao = FakeTrackDao()
        val r = repo(dao)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        r.insertAll(listOf(rawFix(61_000L)), raceId = 1, teamId = 8, segmentId = "seg")
        r.deleteForTeam(7, 1)
        assertTrue(r.observeTrack(7, 1).first().isEmpty())
        assertEquals(1, r.observeTrack(8, 1).first().size)
    }

    // ---- Task 12: dual batch upload ----

    @Test
    fun uploadPending_marksPerTargetIndependently() = runTest {
        // Local succeeds, cloud is offline → only uploadedLocal flips; cloud stays pending.
        val dao = FakeTrackDao()
        val local = FakeUploader()
        val cloud = FakeUploader { PostResult.Offline }
        val r = repo(dao, cloud = cloud, local = local)
        r.insertAll(listOf(rawFix(60_000L), rawFix(61_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        val rows = dao.observeForTeam(7, 1).first()
        assertTrue(rows.all { it.uploadedLocal })
        assertFalse(rows.any { it.uploadedCloud })
        assertEquals(1, local.calls) // one batch drained then empty
        assertEquals(1, cloud.calls) // cloud was attempted once, returned Offline
    }

    @Test
    fun uploadPending_doesNotRetryAlreadyUploaded() = runTest {
        val dao = FakeTrackDao()
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)
        r.uploadPending(raceId = 1, teamId = 7) // second pass: nothing pending

        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
        val row = dao.observeForTeam(7, 1).first().single()
        assertTrue(row.uploadedLocal && row.uploadedCloud)
    }

    @Test
    fun uploadPending_partialAccepted_marksOnlyAccepted_thenBreaks() = runTest {
        // Two points; the server accepts only the first id, ever. The second must stay pending and the
        // loop must not spin: once the first is marked, the next batch's id isn't accepted → break.
        val dao = FakeTrackDao()
        var firstId: String? = null
        val cloud = FakeUploader { pts ->
            if (firstId == null) firstId = pts.first().id
            PostResult.Success(TrackUploadResponse(listOf(firstId!!)))
        }
        val r = repo(dao, cloud = cloud)
        r.insertAll(listOf(rawFix(60_000L), rawFix(61_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        val rows = dao.observeForTeam(7, 1).first()
        assertEquals(1, rows.count { it.uploadedCloud })
        assertEquals(1, rows.count { !it.uploadedCloud })
        assertEquals(2, cloud.calls) // batch1 (marks first), batch2 (no progress → break)
    }

    @Test
    fun uploadPending_emptyAccepted_breaksWithoutLooping() = runTest {
        val dao = FakeTrackDao()
        val cloud = FakeUploader { PostResult.Success(TrackUploadResponse(emptyList())) }
        val r = repo(dao, cloud = cloud)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertFalse(dao.observeForTeam(7, 1).first().single().uploadedCloud)
        assertEquals(1, cloud.calls) // one attempt, then break — no infinite loop
    }

    @Test
    fun uploadAllPending_walksEveryScope() = runTest {
        val dao = FakeTrackDao()
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        r.insertAll(listOf(rawFix(61_000L)), raceId = 2, teamId = 8, segmentId = "seg")

        r.uploadAllPending()

        // Both distinct (raceId, teamId) scopes fully flushed.
        assertTrue(dao.observeForTeam(7, 1).first().all { it.uploadedLocal && it.uploadedCloud })
        assertTrue(dao.observeForTeam(8, 2).first().all { it.uploadedLocal && it.uploadedCloud })
    }

    @Test
    fun upload_reentrantUnderHeldMutex_isNoOp() = runTest {
        // While the outer flush holds the mutex, a re-entrant call must tryLock-fail and do nothing.
        val dao = FakeTrackDao()
        lateinit var r: TrackRepository
        var reentered = false
        val cloud = FakeUploader { pts ->
            if (!reentered) {
                reentered = true
                r.uploadAllPending() // held mutex → no-op; must not produce extra upload calls
            }
            PostResult.Success(TrackUploadResponse(pts.map { it.id }))
        }
        r = repo(dao, cloud = cloud)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(reentered)
        assertEquals(1, cloud.calls) // re-entrant call added nothing
        assertTrue(dao.observeForTeam(7, 1).first().single().uploadedCloud)
    }
}

/** Minimal in-memory [TrackDao]; only the methods exercised by [TrackRepository] are real. */
private class FakeTrackDao : TrackDao {
    private val rows = MutableStateFlow<List<TrackPointEntity>>(emptyList())

    override fun observeForTeam(teamId: Int, raceId: Int): Flow<List<TrackPointEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId && it.raceId == raceId }.sortedBy { it.elapsedRealtimeAt } }

    override fun countForTeam(teamId: Int, raceId: Int): Flow<Int> =
        rows.map { list -> list.count { it.teamId == teamId && it.raceId == raceId } }

    override suspend fun insertAll(points: List<TrackPointEntity>) {
        val existingIds = rows.value.mapTo(HashSet()) { it.id }
        rows.value = rows.value + points.filterNot { it.id in existingIds } // OnConflict.IGNORE
    }

    override suspend fun deleteForTeam(teamId: Int, raceId: Int) {
        rows.value = rows.value.filterNot { it.teamId == teamId && it.raceId == raceId }
    }

    override suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedLocal }
            .sortedBy { it.elapsedRealtimeAt }.take(limit)

    override suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedCloud }
            .sortedBy { it.elapsedRealtimeAt }.take(limit)

    override suspend fun markUploadedLocal(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedLocal = true) else it }
    }

    override suspend fun markUploadedCloud(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedCloud = true) else it }
    }

    override suspend fun pendingUploadScopes(): List<TrackScope> =
        rows.value.filter { !it.uploadedLocal || !it.uploadedCloud }
            .map { TrackScope(it.raceId, it.teamId) }.distinct()
}
