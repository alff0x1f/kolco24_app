package ru.kolco24.kolco24.data

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
import ru.kolco24.kolco24.data.api.dto.MarkDto
import ru.kolco24.kolco24.data.api.dto.MarkUploadResponse
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.db.TrackScope
import ru.kolco24.kolco24.data.db.UploadCounts
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.UploadTarget

/** Upload-loop coverage for [MarkRepository] — a mirror of `TrackRepositoryTest`'s dual-batch tests. */
class MarkRepositoryUploadTest {

    private fun repo(
        dao: MarkDao,
        installId: String = "install-1",
        cloud: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
        local: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
        onOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> },
    ) = MarkRepository(
        markDao = dao,
        sourceInstallId = installId,
        cloudUploader = cloud,
        localUploader = local,
        onUploadOutcome = onOutcome,
    )

    /** Records calls and replies with [reply]; defaults to accepting every id in the batch. */
    private class FakeUploader(
        val reply: suspend (List<MarkDto>) -> PostResult<MarkUploadResponse> = { marks ->
            PostResult.Success(MarkUploadResponse(marks.map { it.id }))
        },
    ) : MarkUploader {
        var calls = 0
            private set
        var lastInstallId: String? = null
            private set

        override suspend fun upload(
            raceId: Int,
            teamId: Int,
            sourceInstallId: String,
            marks: List<MarkDto>,
        ): PostResult<MarkUploadResponse> {
            calls++
            lastInstallId = sourceInstallId
            return reply(marks)
        }
    }

    private class OutcomeRecorder {
        val reports = mutableListOf<Triple<TrackScope, UploadTarget, UploadResultKind>>()
        val sink: (TrackScope, UploadTarget, UploadResultKind) -> Unit =
            { scope, target, kind -> reports.add(Triple(scope, target, kind)) }

        fun kindFor(target: UploadTarget): UploadResultKind? =
            reports.lastOrNull { it.second == target }?.third
    }

    @Test
    fun uploadPending_marksPerTargetIndependently() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(2, raceId = 1, teamId = 7)
        val local = FakeUploader()
        val cloud = FakeUploader { PostResult.Offline }
        val r = repo(dao, cloud = cloud, local = local)

        r.uploadPending(raceId = 1, teamId = 7)

        val rows = dao.observeForTeam(7).first()
        assertTrue(rows.all { it.uploadedLocal })
        assertFalse(rows.any { it.uploadedCloud })
        assertEquals(1, local.calls)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun uploadPending_doesNotRetryAlreadyUploaded() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local)

        r.uploadPending(raceId = 1, teamId = 7)
        r.uploadPending(raceId = 1, teamId = 7) // second pass: nothing pending

        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
        val row = dao.observeForTeam(7).first().single()
        assertTrue(row.uploadedLocal && row.uploadedCloud)
    }

    @Test
    fun uploadPending_partialAccepted_marksOnlyAccepted_thenBreaks() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(2, raceId = 1, teamId = 7)
        var firstId: String? = null
        val cloud = FakeUploader { marks ->
            if (firstId == null) firstId = marks.first().id
            PostResult.Success(MarkUploadResponse(listOf(firstId!!)))
        }
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = cloud, onOutcome = rec.sink)

        r.uploadPending(raceId = 1, teamId = 7)

        val rows = dao.observeForTeam(7).first()
        assertEquals(1, rows.count { it.uploadedCloud })
        assertEquals(1, rows.count { !it.uploadedCloud })
        assertEquals(2, cloud.calls) // batch1 makes progress; batch2 server accepts same id → no forward progress
        // No forward progress on the second call means the loop reports Error (not Ok).
        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun uploadPending_emptyAccepted_breaksWithoutLooping() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val cloud = FakeUploader { PostResult.Success(MarkUploadResponse(emptyList())) }
        val r = repo(dao, cloud = cloud)

        r.uploadPending(raceId = 1, teamId = 7)

        assertFalse(dao.observeForTeam(7).first().single().uploadedCloud)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun uploadAllPending_walksEveryScope() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        dao.seed(1, raceId = 2, teamId = 8)
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader(), local = FakeUploader(), onOutcome = rec.sink)

        r.uploadAllPending()

        assertTrue(dao.observeForTeam(7).first().all { it.uploadedLocal && it.uploadedCloud })
        assertTrue(dao.observeForTeam(8).first().all { it.uploadedLocal && it.uploadedCloud })
        val s1 = rec.reports.filter { it.first == TrackScope(1, 7) }
        val s2 = rec.reports.filter { it.first == TrackScope(2, 8) }
        assertTrue(s1.any { it.second == UploadTarget.Cloud && it.third == UploadResultKind.Ok })
        assertTrue(s1.any { it.second == UploadTarget.Local && it.third == UploadResultKind.Ok })
        assertTrue(s2.any { it.second == UploadTarget.Cloud && it.third == UploadResultKind.Ok })
        assertTrue(s2.any { it.second == UploadTarget.Local && it.third == UploadResultKind.Ok })
    }

    @Test
    fun upload_reentrantUnderHeldMutex_isNoOp() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        lateinit var r: MarkRepository
        var reentered = false
        val cloud = FakeUploader { marks ->
            if (!reentered) {
                reentered = true
                r.uploadAllPending() // held mutex → no-op
            }
            PostResult.Success(MarkUploadResponse(marks.map { it.id }))
        }
        r = repo(dao, cloud = cloud)

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(reentered)
        assertEquals(1, cloud.calls)
        assertTrue(dao.observeForTeam(7).first().single().uploadedCloud)
    }

    @Test
    fun outcome_offlineBothTargets_reportedOffline() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val rec = OutcomeRecorder()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Offline },
            local = FakeUploader { PostResult.Offline },
            onOutcome = rec.sink,
        )

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Cloud))
        assertTrue(rec.reports.all { it.first == TrackScope(1, 7) })
    }

    @Test
    fun outcome_successDrain_reportedOk() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader(), local = FakeUploader(), onOutcome = rec.sink)

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Ok, rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Ok, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_forbidden_reportedError() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader { PostResult.Forbidden }, onOutcome = rec.sink)

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_noForwardProgress_reportedError_notOk() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val rec = OutcomeRecorder()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Success(MarkUploadResponse(emptyList())) },
            onOutcome = rec.sink,
        )

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun outcome_noPending_notReportedForThatTarget() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        // First pass drains both.
        repo(dao, cloud = FakeUploader(), local = FakeUploader()).uploadPending(raceId = 1, teamId = 7)
        assertTrue(dao.observeForTeam(7).first().all { it.uploadedLocal && it.uploadedCloud })

        // Add a fresh row, mark its local done out-of-band so only cloud is pending.
        dao.seed(1, raceId = 1, teamId = 7)
        val newId = dao.observeForTeam(7).first().first { !it.uploadedLocal }.id
        dao.markUploadedLocal(listOf(newId))

        val rec = OutcomeRecorder()
        val r = repo(dao, cloud = FakeUploader { PostResult.Offline }, local = FakeUploader(), onOutcome = rec.sink)
        r.uploadPending(raceId = 1, teamId = 7)

        assertNull(rec.kindFor(UploadTarget.Local))
        assertEquals(UploadResultKind.Offline, rec.kindFor(UploadTarget.Cloud))
    }

    @Test
    fun uploadPending_gpsArrivesAfterFetchBeforeMark_retryDeliversGpsToServer() = runTest {
        // Reproduce the GPS-arrives-during-upload race: the row had locLat=null when the batch
        // was fetched and the DTO was serialized (so location=null was sent), but attachLocation
        // ran during the HTTP call. The conditional markUploadedCloudIfNoLocation must skip the
        // row (locLat is now non-null) → the loop re-fetches, re-uploads WITH GPS, then marks.
        // Net: server receives the GPS coordinate; the row is eventually marked uploaded.
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val cloud = FakeUploader { marks ->
            // GPS arrives during the HTTP call (after DTO creation, before mark).
            dao.simulateGpsArrival(marks.first().id)
            PostResult.Success(MarkUploadResponse(marks.map { it.id }))
        }
        val r = repo(dao, cloud = cloud)

        r.uploadPending(raceId = 1, teamId = 7)

        // Two cloud calls: first sent location=null (conditional mark skipped, GPS arrived during
        // the call), second re-fetched locLat≠null (unconditional mark succeeded).
        // Server ultimately received GPS data; row is marked uploaded.
        assertEquals(2, cloud.calls)
        assertTrue(dao.observeForTeam(7).first().single().uploadedCloud)
    }

    @Test
    fun uploadPending_memberAddsAfterFetchBeforeMark_retryDeliversUpdatedPresent() = runTest {
        // Reproduce the addMember-during-upload race: the row had present=[1] when the batch
        // was fetched and the DTO was serialized, but addMember ran during the HTTP call (bumping
        // updatedAt). The updatedAt version guard in markUploadedCloud*IfUnchanged* must skip the
        // row (updatedAt mismatch) → the loop re-fetches, re-uploads the updated present list,
        // then marks. Net: server eventually receives the complete present list; no member lost.
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        var addedMember = false
        val cloud = FakeUploader { marks ->
            if (!addedMember) {
                addedMember = true
                dao.simulateMemberAdded(marks.first().id)
            }
            PostResult.Success(MarkUploadResponse(marks.map { it.id }))
        }
        val r = repo(dao, cloud = cloud)

        r.uploadPending(raceId = 1, teamId = 7)

        // Two cloud calls: first sent present=[1] (conditional mark skipped, updatedAt changed),
        // second re-fetched the updated row (updatedAt version guard now passes → marked).
        // Row is eventually marked uploaded.
        assertEquals(2, cloud.calls)
        assertTrue(dao.observeForTeam(7).first().single().uploadedCloud)
    }

    @Test
    fun uploadPending_gpsAlreadyPresentAtFetchTime_singleUploadMarksUnconditionally() = runTest {
        // When locLat was already set before the batch was fetched, the DTO carries the GPS
        // coordinate. A single upload should suffice; the unconditional mark path is taken.
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        dao.simulateGpsArrival(dao.observeForTeam(7).first().single().id) // GPS before fetch
        val cloud = FakeUploader()
        val r = repo(dao, cloud = cloud)

        r.uploadPending(raceId = 1, teamId = 7)

        assertEquals(1, cloud.calls)
        assertTrue(dao.observeForTeam(7).first().single().uploadedCloud)
    }

    @Test
    fun sourceInstallId_reachesUploader() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7)
        val cloud = FakeUploader()
        val local = FakeUploader()
        repo(dao, installId = "device-XYZ", cloud = cloud, local = local).uploadPending(raceId = 1, teamId = 7)

        assertEquals("device-XYZ", cloud.lastInstallId)
        assertEquals("device-XYZ", local.lastInstallId)
    }

    @Test
    fun uploadCounts_reflectsPerTargetProgress() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(2, raceId = 1, teamId = 7)
        repo(dao, cloud = FakeUploader { PostResult.Offline }, local = FakeUploader())
            .uploadPending(raceId = 1, teamId = 7)

        val counts = dao.uploadCounts(7, 1).first()
        assertEquals(UploadCounts(total = 2, local = 2, cloud = 0), counts)
    }
}

/** Minimal in-memory [MarkDao]; only the methods exercised by [MarkRepository]'s upload loop are real. */
private class FakeMarkUploadDao : MarkDao {
    private val rows = MutableStateFlow<List<MarkEntity>>(emptyList())
    private var seq = 0

    /** Seed [n] fresh take rows for one scope (newest-last by id). */
    fun seed(n: Int, raceId: Int, teamId: Int) {
        val fresh = (0 until n).map {
            val i = seq++
            MarkEntity(
                id = "mark-$i",
                raceId = raceId,
                teamId = teamId,
                checkpointId = 10,
                checkpointNumber = 10,
                cost = 5,
                method = "nfc",
                cpUid = "CPUID",
                cpCode = "CODE",
                present = listOf(1),
                expectedCount = 1,
                complete = true,
                takenAt = 1_000L + i,
                updatedAt = 1_000L + i,
            )
        }
        rows.value = rows.value + fresh
    }

    override fun observeForTeam(teamId: Int): Flow<List<MarkEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId } }

    override suspend fun getById(id: String): MarkEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsert(mark: MarkEntity) {
        rows.value = rows.value.filterNot { it.id == mark.id } + mark
    }

    override suspend fun addMember(
        id: String,
        numberInTeam: Int,
        nfcUid: String?,
        number: Int,
        code: String?,
        now: Long,
        expectedCount: Int,
    ) = error("unused")

    override suspend fun attachLocation(
        id: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        altitude: Double?,
        verticalAccuracy: Float?,
        gpsTimeMs: Long?,
        elapsedRealtimeAt: Long,
    ) = error("unused")

    override fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts> =
        rows.map { list ->
            val scoped = list.filter { it.teamId == teamId && it.raceId == raceId }
            UploadCounts(
                total = scoped.size,
                local = scoped.count { it.uploadedLocal },
                cloud = scoped.count { it.uploadedCloud },
            )
        }

    override suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedLocal }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedCloud }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun markUploadedLocal(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedLocal = true) else it }
    }

    override suspend fun markUploadedCloud(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedCloud = true) else it }
    }

    override suspend fun markUploadedLocalIfUnchanged(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt) it.copy(uploadedLocal = true) else it
        }
    }

    override suspend fun markUploadedCloudIfUnchanged(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt) it.copy(uploadedCloud = true) else it
        }
    }

    override suspend fun markUploadedLocalIfUnchangedAndNoLocation(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt && it.locLat == null)
                it.copy(uploadedLocal = true) else it
        }
    }

    override suspend fun markUploadedCloudIfUnchangedAndNoLocation(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt && it.locLat == null)
                it.copy(uploadedCloud = true) else it
        }
    }

    /** Simulate a GPS fix arriving for [id] (writes locLat so the conditional mark skips it). */
    fun simulateGpsArrival(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(locLat = 55.75) else it }
    }

    /** Simulate [addMember] arriving during an upload (bumps [MarkEntity.updatedAt]). */
    fun simulateMemberAdded(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(updatedAt = it.updatedAt + 1_000L) else it }
    }

    override suspend fun pendingUploadScopes(): List<TrackScope> =
        rows.value.filter { !it.uploadedLocal || !it.uploadedCloud }
            .map { TrackScope(it.raceId, it.teamId) }.distinct()
}
