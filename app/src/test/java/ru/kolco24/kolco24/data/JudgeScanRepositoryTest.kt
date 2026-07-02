package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.JudgeScanDto
import ru.kolco24.kolco24.data.api.dto.JudgeScanUploadResponse
import ru.kolco24.kolco24.data.db.JudgeScanDao
import ru.kolco24.kolco24.data.db.JudgeScanEntity
import ru.kolco24.kolco24.data.time.TimeSample

class JudgeScanRepositoryTest {

    private lateinit var dao: FakeJudgeScanDao
    private lateinit var repository: JudgeScanRepository

    @Before
    fun setUp() {
        dao = FakeJudgeScanDao()
        repository = JudgeScanRepository(dao, sourceInstallId = "install-1")
    }

    private fun sample(wall: Long = 1_000L, trusted: Long? = 2_000L) =
        TimeSample(wallMs = wall, elapsedMs = 5_000L, trustedMs = trusted, bootCount = 42)

    @Test
    fun record_insertsWriteOnceRowMappingSampleAndInstallId() = runTest {
        val id = repository.record(
            raceId = 1,
            eventType = "start",
            participantNumber = 7,
            nfcUid = "UID7",
            sample = sample(wall = 10L, trusted = 20L),
        )

        val row = dao.getById(id)!!
        assertEquals(1, row.raceId)
        assertEquals("start", row.eventType)
        assertEquals(7, row.participantNumber)
        assertEquals("UID7", row.nfcUid)
        assertEquals(10L, row.takenAt)
        assertEquals(20L, row.trustedTakenAt)
        assertEquals(5_000L, row.elapsedRealtimeAt)
        assertEquals(42, row.bootCount)
        assertEquals("install-1", row.sourceInstallId)
        assertFalse(row.uploadedLocal)
        assertFalse(row.uploadedCloud)
    }

    @Test
    fun uploadPending_acceptedSubset_marksOnlyAcceptedRowsLeavesRestPending() = runTest {
        val a = repository.record(1, "start", 1, "UID1", sample(wall = 1_000L, trusted = 2_000L))
        val b = repository.record(1, "start", 2, "UID2", sample(wall = 1_500L, trusted = 2_500L))
        val cloud = FakeUploader { scans -> PostResult.Success(JudgeScanUploadResponse(listOf(a))) }
        repository = JudgeScanRepository(dao, cloudUploader = cloud, localUploader = FakeUploader { PostResult.Offline })

        repository.uploadPending(raceId = 1)

        // The cloud loop stops after no-progress on the second (rejected) row; local target still offline.
        assertTrue(dao.getById(a)!!.uploadedCloud)
        assertFalse(dao.getById(b)!!.uploadedCloud)
        assertFalse(dao.getById(a)!!.uploadedLocal)
        assertFalse(dao.getById(b)!!.uploadedLocal)
    }

    @Test
    fun uploadPending_offlineOrError_leavesRowsPendingForNextTick() = runTest {
        val id = repository.record(1, "finish", 3, "UID3", sample())
        repository = JudgeScanRepository(
            dao,
            cloudUploader = FakeUploader { PostResult.Offline },
            localUploader = FakeUploader { PostResult.Error(500) },
        )

        repository.uploadPending(raceId = 1)

        val row = dao.getById(id)!!
        assertFalse(row.uploadedCloud)
        assertFalse(row.uploadedLocal)
    }

    @Test
    fun uploadPending_dualTargetIndependence_oneTargetErrorDoesNotBlockTheOther() = runTest {
        val id = repository.record(1, "start", 4, "UID4", sample())
        repository = JudgeScanRepository(
            dao,
            cloudUploader = FakeUploader { PostResult.Offline },
            localUploader = FakeUploader { scans -> PostResult.Success(JudgeScanUploadResponse(scans.map { it.id })) },
        )

        repository.uploadPending(raceId = 1)

        val row = dao.getById(id)!!
        assertTrue(row.uploadedLocal)
        assertFalse(row.uploadedCloud)
    }

    @Test
    fun uploadPending_reentrantUnderHeldMutex_isNoOp() = runTest {
        repository.record(1, "start", 5, "UID5", sample())
        var reentered = false
        val cloud = FakeUploader { scans ->
            if (!reentered) {
                reentered = true
                repository.uploadAllPending() // held mutex -> tryLock fails, no-op
            }
            PostResult.Success(JudgeScanUploadResponse(scans.map { it.id }))
        }
        repository = JudgeScanRepository(dao, cloudUploader = cloud, localUploader = FakeUploader())

        repository.uploadPending(raceId = 1)

        assertTrue(reentered)
        assertEquals(1, cloud.calls) // re-entrant call added nothing
    }

    @Test
    fun uploadAllPending_walksEveryPendingRace() = runTest {
        repository.record(1, "start", 1, "UID1", sample())
        repository.record(2, "finish", 2, "UID2", sample())
        val cloud = FakeUploader { scans -> PostResult.Success(JudgeScanUploadResponse(scans.map { it.id })) }
        val local = FakeUploader { scans -> PostResult.Success(JudgeScanUploadResponse(scans.map { it.id })) }
        repository = JudgeScanRepository(dao, cloudUploader = cloud, localUploader = local)

        repository.uploadAllPending()

        assertTrue(dao.rowsFor(1).all { it.uploadedLocal && it.uploadedCloud })
        assertTrue(dao.rowsFor(2).all { it.uploadedLocal && it.uploadedCloud })
    }

    private class FakeUploader(
        private val respond: suspend (List<JudgeScanDto>) -> PostResult<JudgeScanUploadResponse> =
            { scans -> PostResult.Success(JudgeScanUploadResponse(scans.map { it.id })) },
    ) : JudgeScanUploader {
        var calls = 0

        override suspend fun upload(
            raceId: Int,
            sourceInstallId: String,
            scans: List<JudgeScanDto>,
        ): PostResult<JudgeScanUploadResponse> {
            calls++
            return respond(scans)
        }
    }
}

private class FakeJudgeScanDao : JudgeScanDao {
    private val rows = MutableStateFlow<List<JudgeScanEntity>>(emptyList())

    fun getById(id: String): JudgeScanEntity? = rows.value.find { it.id == id }

    fun rowsFor(raceId: Int): List<JudgeScanEntity> = rows.value.filter { it.raceId == raceId }

    override suspend fun insert(scan: JudgeScanEntity) {
        rows.value = rows.value + scan
    }

    override fun observeRecent(raceId: Int, eventType: String, limit: Int): Flow<List<JudgeScanEntity>> =
        rows.map { list ->
            list.filter { it.raceId == raceId && it.eventType == eventType }
                .sortedWith(compareByDescending { it.trustedTakenAt ?: it.takenAt })
                .take(limit)
        }

    override suspend fun unuploadedLocal(raceId: Int, limit: Int): List<JudgeScanEntity> =
        rows.value.filter { it.raceId == raceId && !it.uploadedLocal }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id }))
            .take(limit)

    override suspend fun unuploadedCloud(raceId: Int, limit: Int): List<JudgeScanEntity> =
        rows.value.filter { it.raceId == raceId && !it.uploadedCloud }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id }))
            .take(limit)

    override suspend fun markUploadedLocal(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedLocal = true) else it }
    }

    override suspend fun markUploadedCloud(ids: List<String>) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(uploadedCloud = true) else it }
    }

    override suspend fun pendingUploadRaces(): List<Int> =
        rows.value.filter { !it.uploadedLocal || !it.uploadedCloud }.map { it.raceId }.distinct()
}
