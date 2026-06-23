package ru.kolco24.kolco24.data.track

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
    ): TrackRepository {
        var n = 0
        return TrackRepository(
            trackDao = dao,
            trustedClock = clock,
            bootCountProvider = { boot },
            idFactory = { "id-${n++}" },
            wallProvider = { wallNow },
            elapsedProvider = { elapsedNow },
        )
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
        gpsTimeMs = gpsTimeMs,
        elapsedRealtimeNanos = elapsedMs * 1_000_000L,
    )

    @Test
    fun insertAll_mapsAndStores_withInjectedProviders() = runTest {
        val dao = FakeTrackDao()
        repo(dao).insertAll(listOf(rawFix(elapsedMs = 60_000L)), raceId = 1, teamId = 7)

        val points = dao.observeForTeam(7).first()
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
    }

    @Test
    fun insertAll_emptyBatch_isNoOp() = runTest {
        val dao = FakeTrackDao()
        repo(dao).insertAll(emptyList(), raceId = 1, teamId = 7)
        assertTrue(dao.observeForTeam(7).first().isEmpty())
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
        )
        val points = dao.observeForTeam(7).first() // ordered by elapsedRealtimeAt ASC
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
            .insertAll(listOf(rawFix(elapsedMs = 60_000L)), raceId = 1, teamId = 7)
        val p = dao.observeForTeam(7).first().single()
        assertNull(p.trustedMs)
        assertNull(p.bootCount)
        assertEquals(1_960_000L, p.wallMs) // wall fallback still honest per-point
    }

    @Test
    fun count_observe_reflectInserts() = runTest {
        val dao = FakeTrackDao()
        val r = repo(dao)
        r.insertAll(listOf(rawFix(60_000L), rawFix(61_000L)), raceId = 1, teamId = 7)
        assertEquals(2, r.countForTeam(7).first())
        assertEquals(2, r.observeTrack(7).first().size)
    }

    @Test
    fun length_overObservedPoints_isCorrect() = runTest {
        val dao = FakeTrackDao()
        val r = repo(dao)
        // ~0.001° latitude apart ≈ 111.2 m per step.
        r.insertAll(
            listOf(
                rawFix(elapsedMs = 60_000L, lat = 55.000, lon = 37.0),
                rawFix(elapsedMs = 61_000L, lat = 55.001, lon = 37.0),
            ),
            raceId = 1,
            teamId = 7,
        )
        val length = trackLengthMeters(r.observeTrack(7).first())
        assertEquals(111.2, length, 1.0)
    }

    @Test
    fun deleteForTeam_clearsOnlyThatTeam() = runTest {
        val dao = FakeTrackDao()
        val r = repo(dao)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7)
        r.insertAll(listOf(rawFix(61_000L)), raceId = 1, teamId = 8)
        r.deleteForTeam(7)
        assertTrue(r.observeTrack(7).first().isEmpty())
        assertEquals(1, r.observeTrack(8).first().size)
    }
}

/** Minimal in-memory [TrackDao]; only the methods exercised by [TrackRepository] are real. */
private class FakeTrackDao : TrackDao {
    private val rows = MutableStateFlow<List<TrackPointEntity>>(emptyList())

    override fun observeForTeam(teamId: Int): Flow<List<TrackPointEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId }.sortedBy { it.elapsedRealtimeAt } }

    override fun countForTeam(teamId: Int): Flow<Int> =
        rows.map { list -> list.count { it.teamId == teamId } }

    override suspend fun insertAll(points: List<TrackPointEntity>) {
        val existingIds = rows.value.mapTo(HashSet()) { it.id }
        rows.value = rows.value + points.filterNot { it.id in existingIds } // OnConflict.IGNORE
    }

    override suspend fun deleteForTeam(teamId: Int) {
        rows.value = rows.value.filterNot { it.teamId == teamId }
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
