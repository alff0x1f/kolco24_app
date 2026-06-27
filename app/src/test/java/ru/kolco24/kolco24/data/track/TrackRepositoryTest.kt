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
import ru.kolco24.kolco24.data.db.UploadCounts
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
        onOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> },
        onCleared: (TrackScope) -> Unit = { },
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
            onUploadOutcome = onOutcome,
            onScopeCleared = onCleared,
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
        val points = dao.observeForTeam(7, 1).first()
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

    @Test
    fun deleteForTeam_reportsScopeCleared() = runTest {
        val dao = FakeTrackDao()
        val cleared = mutableListOf<TrackScope>()
        val r = repo(dao, onCleared = { cleared.add(it) })
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.deleteForTeam(7, 1)

        assertEquals(listOf(TrackScope(raceId = 1, teamId = 7)), cleared)
    }

    @Test
    fun deleteForTeam_afterInFlightFlush_outcomeRemovedAndNotReaddedBySubsequentFlush() = runTest {
        // Regression: without the uploadMutex lock in deleteForTeam, a flush's onUploadOutcome could
        // fire after onScopeCleared, re-adding a stale outcome. This test covers the sequential slice
        // of that race: flush completes (writes outcome) → deleteForTeam (clears outcome) → new flush
        // on empty table (must NOT re-add outcome).
        val dao = FakeTrackDao()
        val scope = TrackScope(raceId = 1, teamId = 7)
        val outcomes = mutableMapOf<Pair<TrackScope, UploadTarget>, UploadResultKind>()
        val r = repo(
            dao,
            cloud = FakeUploader(),
            local = FakeUploader(),
            onOutcome = { s, target, kind -> outcomes[s to target] = kind },
            onCleared = { s ->
                outcomes.remove(s to UploadTarget.Local)
                outcomes.remove(s to UploadTarget.Cloud)
            },
        )
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7) // flush writes Ok outcomes
        assertEquals(UploadResultKind.Ok, outcomes[scope to UploadTarget.Cloud])
        assertEquals(UploadResultKind.Ok, outcomes[scope to UploadTarget.Local])

        r.deleteForTeam(7, 1) // must clear both outcomes
        assertNull(outcomes[scope to UploadTarget.Cloud])
        assertNull(outcomes[scope to UploadTarget.Local])
        assertTrue(r.observeTrack(7, 1).first().isEmpty())

        // A subsequent flush on the now-empty scope must NOT re-add any outcome (null path).
        r.uploadPending(raceId = 1, teamId = 7)
        assertNull(outcomes[scope to UploadTarget.Cloud])
        assertNull(outcomes[scope to UploadTarget.Local])
    }

    @Test
    fun deleteForTeam_guardFalse_skipsDeleteAndScopeCleared() = runTest {
        // Regression for the queued-clear-races-new-recording bug: if a new recording starts while
        // deleteForTeam is waiting on the mutex, the guard will return false and the delete must be
        // a no-op — new points from the fresh session must not be wiped.
        val dao = FakeTrackDao()
        val cleared = mutableListOf<TrackScope>()
        val r = repo(dao, onCleared = { cleared.add(it) })
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.deleteForTeam(7, 1, guard = { false }) // simulate: new recording started, guard rejects

        // Rows must survive and onScopeCleared must NOT fire (guard aborted before DB wipe).
        assertEquals(1, r.observeTrack(7, 1).first().size)
        assertTrue(cleared.isEmpty())
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
        val rec = OutcomeRecorder()
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local, onOutcome = rec.sink)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        r.insertAll(listOf(rawFix(61_000L)), raceId = 2, teamId = 8, segmentId = "seg")

        r.uploadAllPending()

        // Both distinct (raceId, teamId) scopes fully flushed.
        assertTrue(dao.observeForTeam(7, 1).first().all { it.uploadedLocal && it.uploadedCloud })
        assertTrue(dao.observeForTeam(8, 2).first().all { it.uploadedLocal && it.uploadedCloud })
        // Outcomes reported against the correct scope for each target.
        val scope1Reports = rec.reports.filter { it.first == TrackScope(1, 7) }
        val scope2Reports = rec.reports.filter { it.first == TrackScope(2, 8) }
        assertTrue(scope1Reports.any { it.second == UploadTarget.Cloud && it.third == UploadResultKind.Ok })
        assertTrue(scope1Reports.any { it.second == UploadTarget.Local && it.third == UploadResultKind.Ok })
        assertTrue(scope2Reports.any { it.second == UploadTarget.Cloud && it.third == UploadResultKind.Ok })
        assertTrue(scope2Reports.any { it.second == UploadTarget.Local && it.third == UploadResultKind.Ok })
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

    // ---- Task 3: per-target outcome reporting ----

    /** Records every (scope, target, kind) reported via onUploadOutcome. */
    private class OutcomeRecorder {
        val reports = mutableListOf<Triple<TrackScope, UploadTarget, UploadResultKind>>()
        val sink: (TrackScope, UploadTarget, UploadResultKind) -> Unit =
            { scope, target, kind -> reports.add(Triple(scope, target, kind)) }

        fun kindFor(target: UploadTarget): UploadResultKind? =
            reports.lastOrNull { it.second == target }?.third
    }

    @Test
    fun outcome_offlineBothTargets_reportedOffline() = runTest {
        val dao = FakeTrackDao()
        val rec = OutcomeRecorder()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Offline },
            local = FakeUploader { PostResult.Offline },
            onOutcome = rec.sink,
        )
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Cloud))
        // Reported against the correct scope.
        assertTrue(rec.reports.all { it.first == TrackScope(1, 7) })
    }

    @Test
    fun outcome_successDrain_reportedOk() = runTest {
        val dao = FakeTrackDao()
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader(), local = FakeUploader(), onOutcome = rec.sink)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Ok, rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Ok, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_forbidden_reportedError() = runTest {
        val dao = FakeTrackDao()
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader { PostResult.Forbidden }, onOutcome = rec.sink)
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_noForwardProgress_reportedError_notOk() = runTest {
        // Success but empty accepted → no progress → Error (must NOT route through uploadResultKind→Ok).
        val dao = FakeTrackDao()
        val rec = OutcomeRecorder()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Success(TrackUploadResponse(emptyList())) },
            onOutcome = rec.sink,
        )
        r.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_noPending_notReportedForThatTarget() = runTest {
        // Local has nothing pending (already uploaded), cloud is offline. Only cloud must be reported.
        val dao = FakeTrackDao()
        val rec = OutcomeRecorder()
        // First pass: both succeed and drain.
        val r1 = repo(dao, cloud = FakeUploader(), local = FakeUploader())
        r1.insertAll(listOf(rawFix(60_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        r1.uploadPending(raceId = 1, teamId = 7)
        // Now flip cloud back to pending by adding a fresh point that only local will accept.
        // Instead: directly assert the null path — re-flush with local already done, cloud offline.
        val rows = dao.observeForTeam(7, 1).first()
        assertTrue(rows.all { it.uploadedLocal && it.uploadedCloud })

        // Add a new point so cloud has pending again; local also pending for the new point.
        r1.insertAll(listOf(rawFix(61_000L)), raceId = 1, teamId = 7, segmentId = "seg")
        // Mark the new point's local as done out-of-band so local has nothing pending, cloud does.
        val newId = dao.observeForTeam(7, 1).first().first { it.elapsedRealtimeAt == 61_000L }.id
        dao.markUploadedLocal(listOf(newId))

        val r2 = repo(
            dao,
            cloud = FakeUploader { PostResult.Offline },
            local = FakeUploader(),
            onOutcome = rec.sink,
        )
        r2.uploadPending(raceId = 1, teamId = 7)

        // Local had nothing pending → no report; cloud had a pending point and was offline.
        assertNull(rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Cloud))
    }
}

/** Minimal in-memory [TrackDao]; only the methods exercised by [TrackRepository] are real. */
private class FakeTrackDao : TrackDao {
    private val rows = MutableStateFlow<List<TrackPointEntity>>(emptyList())

    override fun observeForTeam(teamId: Int, raceId: Int): Flow<List<TrackPointEntity>> =
        rows.map { list -> sortedTrackPoints(list.filter { it.teamId == teamId && it.raceId == raceId }) }

    override fun countForTeam(teamId: Int, raceId: Int): Flow<Int> =
        rows.map { list -> list.count { it.teamId == teamId && it.raceId == raceId } }

    override fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts> =
        rows.map { list ->
            val scoped = list.filter { it.teamId == teamId && it.raceId == raceId }
            UploadCounts(
                total = scoped.size,
                local = scoped.count { it.uploadedLocal },
                cloud = scoped.count { it.uploadedCloud },
            )
        }

    override suspend fun insertAll(points: List<TrackPointEntity>) {
        val existingIds = rows.value.mapTo(HashSet()) { it.id }
        rows.value = rows.value + points.filterNot { it.id in existingIds } // OnConflict.IGNORE
    }

    override suspend fun deleteForTeam(teamId: Int, raceId: Int) {
        rows.value = rows.value.filterNot { it.teamId == teamId && it.raceId == raceId }
    }

    override suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedLocal }
            .let(::sortedTrackPoints).take(limit)

    override suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<TrackPointEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedCloud }
            .let(::sortedTrackPoints).take(limit)

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
