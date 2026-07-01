package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.db.MarkMemberSnapshot
import ru.kolco24.kolco24.data.db.PhotoFrameRow
import ru.kolco24.kolco24.data.db.TrackScope
import ru.kolco24.kolco24.data.db.UploadCounts
import ru.kolco24.kolco24.data.marks.photoPaths
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.data.track.RawFix

class MarkRepositoryTest {

    private lateinit var markDao: FakeMarkDao
    private lateinit var repository: MarkRepository

    @Before
    fun setUp() {
        markDao = FakeMarkDao()
        repository = MarkRepository(markDao)
    }

    private fun sample(
        wall: Long = 1_000L,
        elapsed: Long = 5_000L,
        trusted: Long? = 2_000L,
        boot: Int? = 42,
    ) = TimeSample(wallMs = wall, elapsedMs = elapsed, trustedMs = trusted, bootCount = boot)

    private fun mem(n: Int, uid: String? = null, number: Int = 0, code: String? = null) =
        MarkMemberSnapshot(numberInTeam = n, nfcUid = uid, number = number, code = code)

    private suspend fun startTake(
        point: Int = 10,
        cost: Int = 5,
        expectedCount: Int = 3,
        buffered: Set<Int> = emptySet(),
        bufferedSnapshots: Collection<MarkMemberSnapshot> = buffered.map { mem(it) },
        sample: TimeSample = sample(),
    ): String = repository.startKpTake(
        raceId = 1,
        teamId = 7,
        checkpointId = point,
        number = point,
        cost = cost,
        cpUid = "CPUID$point",
        cpCode = "CODE$point",
        expectedCount = expectedCount,
        bufferedMembers = bufferedSnapshots,
        sample = sample,
    )

    @Test
    fun startKpTake_generatesUniqueIds() = runTest {
        val a = startTake(point = 10)
        val b = startTake(point = 11)
        assertNotEquals(a, b)
        assertEquals(2, repository.observeMarks(7).first().size)
    }

    @Test
    fun startKpTake_storesSnapshotAndCpLog() = runTest {
        val id = startTake(point = 10, cost = 5, buffered = setOf(1))
        val mark = markDao.getById(id)!!
        assertEquals(10, mark.checkpointId)
        assertEquals(5, mark.cost)
        assertEquals("nfc", mark.method)
        assertEquals("CPUID10", mark.cpUid)
        assertEquals("CODE10", mark.cpCode)
        assertEquals(listOf(1), mark.present)
        assertFalse(mark.complete)
    }

    @Test
    fun startKpTake_writesAllTimesFromSample() = runTest {
        val id = startTake(
            point = 10,
            sample = sample(wall = 1_000L, elapsed = 5_000L, trusted = 2_000L, boot = 42),
        )
        val mark = markDao.getById(id)!!
        // wall drives both takenAt and updatedAt; trusted/elapsed/boot persist verbatim.
        assertEquals(1_000L, mark.takenAt)
        assertEquals(1_000L, mark.updatedAt)
        assertEquals(2_000L, mark.trustedTakenAt)
        assertEquals(5_000L, mark.elapsedRealtimeAt)
        assertEquals(42, mark.bootCount)
    }

    @Test
    fun startKpTake_nullTrustedAndBoot_persistAsNull() = runTest {
        // NoSync (no clock anchor): trustedMs/bootCount are null and the columns stay null.
        val id = startTake(
            point = 10,
            sample = sample(wall = 1_000L, elapsed = 5_000L, trusted = null, boot = null),
        )
        val mark = markDao.getById(id)!!
        assertEquals(1_000L, mark.takenAt)
        assertNull(mark.trustedTakenAt)
        assertEquals(5_000L, mark.elapsedRealtimeAt)
        assertNull(mark.bootCount)
    }

    @Test
    fun addMember_bumpsUpdatedAtFromSampleWall() = runTest {
        val id = startTake(point = 10, expectedCount = 3, sample = sample(wall = 1_000L))
        repository.addMember(
            id,
            checkpointId = 10,
            member = mem(1),
            expectedCount = 3,
            sample = sample(wall = 1_100L),
        )
        assertEquals(1_100L, markDao.getById(id)!!.updatedAt)
    }

    @Test
    fun startKpTake_completeWhenBufferCoversRoster_scores() = runTest {
        val id = startTake(point = 10, expectedCount = 2, buffered = setOf(1, 2))
        assertTrue(markDao.getById(id)!!.complete)
        assertEquals(setOf(10), takenPoints(repository.observeMarks(7).first()))
    }

    @Test
    fun addMember_accumulatesAndScoresOnFullRoster() = runTest {
        val id = startTake(point = 10, expectedCount = 3)
        repository.addMember(id, checkpointId = 10, member = mem(1), expectedCount = 3, sample = sample(wall = 1_100L))
        assertFalse(markDao.getById(id)!!.complete)
        repository.addMember(id, checkpointId = 10, member = mem(2), expectedCount = 3, sample = sample(wall = 1_200L))
        // Idempotent rescan of an already-present member does not advance the count.
        repository.addMember(id, checkpointId = 10, member = mem(2), expectedCount = 3, sample = sample(wall = 1_300L))
        assertFalse(markDao.getById(id)!!.complete)
        repository.addMember(id, checkpointId = 10, member = mem(3), expectedCount = 3, sample = sample(wall = 1_400L))
        val finalMark = markDao.getById(id)!!
        assertEquals(listOf(1, 2, 3), finalMark.present)
        assertTrue(finalMark.complete)
        assertEquals(setOf(10), takenPoints(repository.observeMarks(7).first()))
    }

    @Test
    fun addMember_resetsUploadFlags_soUpdatedTakeGetsReUploaded() = runTest {
        // Simulate a track-piggyback upload completing during an active scan window: the partial
        // take (member 1 only) was uploaded and marked done before member 2 arrived.
        val id = startTake(point = 10, expectedCount = 2, buffered = setOf(1))
        markDao.markUploadedLocal(listOf(id))
        markDao.markUploadedCloud(listOf(id))
        assertTrue(markDao.getById(id)!!.uploadedLocal)
        assertTrue(markDao.getById(id)!!.uploadedCloud)

        // Adding the second member must reset both flags so the completed take is re-queued.
        repository.addMember(id, checkpointId = 10, member = mem(2), expectedCount = 2, sample = sample(wall = 1_200L))

        val mark = markDao.getById(id)!!
        assertEquals(listOf(1, 2), mark.present)
        assertTrue(mark.complete)
        assertFalse(mark.uploadedLocal)
        assertFalse(mark.uploadedCloud)
    }

    @Test
    fun addMember_missingRow_isNoOp() = runTest {
        repository.addMember("nope", checkpointId = 10, member = mem(1), expectedCount = 1, sample = sample(wall = 1L))
        assertTrue(repository.observeMarks(7).first().isEmpty())
    }

    @Test
    fun repeatTakeOfSamePoint_createsNewRow() = runTest {
        val first = startTake(point = 10, expectedCount = 1, buffered = setOf(1), sample = sample(wall = 1_000L))
        val second = startTake(point = 10, expectedCount = 1, buffered = setOf(1), sample = sample(wall = 5_000L))
        assertNotEquals(first, second)
        assertEquals(2, repository.observeMarks(7).first().count { it.checkpointId == 10 })
    }

    @Test
    fun startKpTake_writesPresentDetailsFromBuffer_dedupedByNumberInTeam() = runTest {
        val id = startTake(
            point = 10,
            expectedCount = 3,
            bufferedSnapshots = listOf(
                mem(1, uid = "AA", number = 101),
                mem(2, uid = "BB", number = 102),
                // Duplicate slot 1 (e.g. a double-tap): must collapse, not inflate present/complete.
                mem(1, uid = "AA", number = 101),
            ),
        )
        val mark = markDao.getById(id)!!
        assertEquals(listOf(1, 2), mark.present)
        assertEquals(
            listOf(mem(1, uid = "AA", number = 101), mem(2, uid = "BB", number = 102)),
            mark.presentDetails,
        )
        // distinct collapsed the doubled slot, so a 3-person roster is not yet complete.
        assertFalse(mark.complete)
    }

    @Test
    fun startKpTake_snapshotWithNullUid_storedVerbatim() = runTest {
        val id = startTake(
            point = 10,
            expectedCount = 1,
            bufferedSnapshots = listOf(mem(1, uid = null, number = 0)),
        )
        val mark = markDao.getById(id)!!
        assertEquals(listOf(mem(1, uid = null, number = 0)), mark.presentDetails)
        assertTrue(mark.complete)
    }

    @Test
    fun addMember_appendsSnapshotWithSetSemantics() = runTest {
        val id = startTake(point = 10, expectedCount = 3)
        repository.addMember(id, checkpointId = 10, member = mem(1, uid = "AA", number = 101), expectedCount = 3, sample = sample(wall = 1_100L))
        repository.addMember(id, checkpointId = 10, member = mem(2, uid = "BB", number = 102), expectedCount = 3, sample = sample(wall = 1_200L))
        // Re-scan of slot 2 is a no-op (set semantics) — does not duplicate the snapshot.
        repository.addMember(id, checkpointId = 10, member = mem(2, uid = "BB", number = 102), expectedCount = 3, sample = sample(wall = 1_300L))
        val mark = markDao.getById(id)!!
        assertEquals(listOf(1, 2), mark.present)
        assertEquals(
            listOf(mem(1, uid = "AA", number = 101), mem(2, uid = "BB", number = 102)),
            mark.presentDetails,
        )
        assertFalse(mark.complete)
    }

    @Test
    fun addMember_onLegacyRowWithNullPresentDetails_startsFreshList() = runTest {
        // Simulate a legacy/seed row: present is populated but presentDetails is NULL.
        val id = UUID.randomUUID().toString()
        markDao.upsert(
            MarkEntity(
                id = id,
                raceId = 1,
                teamId = 7,
                checkpointId = 10,
                checkpointNumber = 10,
                cost = 5,
                method = "nfc",
                cpUid = "CPUID10",
                cpCode = "CODE10",
                present = listOf(1),
                presentDetails = null,
                expectedCount = 3,
                complete = false,
                takenAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        repository.addMember(id, checkpointId = 10, member = mem(2, uid = "BB", number = 102), expectedCount = 3, sample = sample(wall = 1_200L))
        val mark = markDao.getById(id)!!
        assertEquals(listOf(1, 2), mark.present)
        // The null presentDetails is seeded fresh with the new snapshot (no NPE, slot 1 has no snapshot).
        assertEquals(listOf(mem(2, uid = "BB", number = 102)), mark.presentDetails)
    }

    private fun fix(
        lat: Double = 55.75,
        lon: Double = 37.61,
        accuracy: Float = 4.2f,
        altitude: Double? = 150.0,
        verticalAccuracy: Float? = 3.0f,
        gpsTimeMs: Long = 1_700_000_000_000L,
        elapsedNanos: Long = 5_000_000_000L,
    ) = RawFix(
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        altitude = altitude,
        verticalAccuracyMeters = verticalAccuracy,
        gpsTimeMs = gpsTimeMs,
        elapsedRealtimeNanos = elapsedNanos,
    )

    @Test
    fun attachLocation_writesAllLocColumns_withoutTouchingTakeState() = runTest {
        val id = startTake(point = 10, expectedCount = 2, buffered = setOf(1, 2))
        val before = markDao.getById(id)!!
        assertTrue(before.complete)

        repository.attachLocation(id, fix(elapsedNanos = 5_000_000_000L))

        val mark = markDao.getById(id)!!
        assertEquals(55.75, mark.locLat!!, 0.0)
        assertEquals(37.61, mark.locLon!!, 0.0)
        assertEquals(4.2f, mark.locAccuracy!!, 0.0f)
        assertEquals(150.0, mark.locAltitude!!, 0.0)
        assertEquals(3.0f, mark.locVerticalAccuracy!!, 0.0f)
        assertEquals(1_700_000_000_000L, mark.locGpsTimeMs)
        // elapsedRealtimeNanos / 1_000_000
        assertEquals(5_000L, mark.locElapsedRealtimeAt)
        // Take state is untouched.
        assertEquals(before.present, mark.present)
        assertEquals(before.complete, mark.complete)
        assertEquals(before.takenAt, mark.takenAt)
        assertEquals(before.trustedTakenAt, mark.trustedTakenAt)
        assertEquals(before.elapsedRealtimeAt, mark.elapsedRealtimeAt)
    }

    @Test
    fun attachLocation_nullFix_isNoOp() = runTest {
        val id = startTake(point = 10)
        repository.attachLocation(id, null)
        val mark = markDao.getById(id)!!
        assertNull(mark.locLat)
        assertNull(mark.locLon)
        assertNull(mark.locAccuracy)
        assertNull(mark.locAltitude)
        assertNull(mark.locVerticalAccuracy)
        assertNull(mark.locGpsTimeMs)
        assertNull(mark.locElapsedRealtimeAt)
    }

    @Test
    fun attachLocation_nullableFixFields_persistAsNull() = runTest {
        val id = startTake(point = 10)
        repository.attachLocation(id, fix(altitude = null, verticalAccuracy = null))
        val mark = markDao.getById(id)!!
        assertNull(mark.locAltitude)
        assertNull(mark.locVerticalAccuracy)
        // The mandatory coordinate is still written.
        assertEquals(55.75, mark.locLat!!, 0.0)
    }

    @Test
    fun attachLocation_repeatTakeOfSamePoint_eachRowGetsOwnFix() = runTest {
        val first = startTake(point = 10, expectedCount = 1, buffered = setOf(1), sample = sample(wall = 1_000L))
        val second = startTake(point = 10, expectedCount = 1, buffered = setOf(1), sample = sample(wall = 5_000L))
        repository.attachLocation(first, fix(lat = 55.10, elapsedNanos = 1_000_000_000L))
        repository.attachLocation(second, fix(lat = 55.20, elapsedNanos = 9_000_000_000L))
        assertEquals(55.10, markDao.getById(first)!!.locLat!!, 0.0)
        assertEquals(1_000L, markDao.getById(first)!!.locElapsedRealtimeAt)
        assertEquals(55.20, markDao.getById(second)!!.locLat!!, 0.0)
        assertEquals(9_000L, markDao.getById(second)!!.locElapsedRealtimeAt)
    }

    @Test
    fun attachLocation_noAccuracySentinel_writesNullAccuracy() = runTest {
        val id = startTake(point = 10)
        repository.attachLocation(id, fix(accuracy = Float.MAX_VALUE))
        val mark = markDao.getById(id)!!
        assertNull(mark.locAccuracy)
        // Coordinate is still written; only accuracy is null.
        assertEquals(55.75, mark.locLat!!, 0.0)
    }

    @Test
    fun attachLocation_zeroGpsTimeMs_persistsAsNull() = runTest {
        // Location.time is 0L when the provider has not set a valid satellite time (e.g. network fixes).
        // A zero gpsTimeMs is indistinguishable from "epoch zero" at the server; store null instead.
        val id = startTake(point = 10)
        repository.attachLocation(id, fix(gpsTimeMs = 0L))
        assertNull(markDao.getById(id)!!.locGpsTimeMs)
        // The coordinate itself is still written.
        assertEquals(55.75, markDao.getById(id)!!.locLat!!, 0.0)
    }

    @Test
    fun attachLocation_missingRow_doesNotCorruptExistingRow() = runTest {
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        repository.attachLocation("nope", fix(lat = 55.0))
        assertNull(markDao.getById(id)!!.locLat)
    }

    @Test
    fun attachLocation_resetsUploadFlagsOnAlreadyUploadedRow() = runTest {
        // Simulate a race where the row was uploaded before the GPS fix arrived.
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        markDao.markUploadedLocal(listOf(id))
        markDao.markUploadedCloud(listOf(id))
        assertTrue(markDao.getById(id)!!.uploadedLocal)
        assertTrue(markDao.getById(id)!!.uploadedCloud)

        // A late-arriving GPS fix must re-queue the row so the server eventually gets the coordinate.
        repository.attachLocation(id, fix(lat = 55.0))

        val mark = markDao.getById(id)!!
        assertEquals(55.0, mark.locLat!!, 0.0)
        assertFalse(mark.uploadedLocal)
        assertFalse(mark.uploadedCloud)
    }

    private fun cp(id: Int, number: Int = id, cost: Int? = 7, locked: Boolean = false) =
        CheckpointEntity(
            id = id,
            raceId = 1,
            number = number,
            cost = cost,
            type = "kp",
            description = null,
            locked = locked,
        )

    @Test
    fun createPhotoMark_isScoredHybridTake() = runTest {
        repository.createPhotoMark(
            markId = "photo-1",
            cp = cp(30, cost = 7),
            raceId = 1,
            teamId = 7,
            paths = listOf("marks/photo-1/a.jpg"),
            expectedCount = 3,
            sample = sample(),
        )
        val mark = markDao.getById("photo-1")!!
        assertEquals("photo", mark.method)
        assertTrue(mark.complete)
        assertEquals(emptyList<Int>(), mark.present)
        assertEquals("", mark.cpUid)
        assertEquals("", mark.cpCode)
        assertEquals(listOf("marks/photo-1/a.jpg"), photoPaths(mark.photoPath))
        // Scored locally: counts as a distinct complete КП for both metrics.
        val marks = repository.observeMarks(7).first()
        assertEquals(1, takenPointCount(marks))
        assertEquals(7, totalScore(marks))
    }

    @Test
    fun createPhotoMark_persistsAllTimeSampleFields() = runTest {
        val s = sample(wall = 9_000L, elapsed = 20_000L, trusted = 10_000L, boot = 7)
        repository.createPhotoMark(
            markId = "photo-t",
            cp = cp(30, cost = 7),
            raceId = 1,
            teamId = 7,
            paths = listOf("marks/photo-t/a.jpg"),
            expectedCount = 3,
            sample = s,
        )
        val mark = markDao.getById("photo-t")!!
        assertEquals(s.wallMs, mark.takenAt)
        assertEquals(s.trustedMs, mark.trustedTakenAt)
        assertEquals(s.elapsedMs, mark.elapsedRealtimeAt)
        assertEquals(s.bootCount, mark.bootCount)
    }

    @Test
    fun createPhotoMark_nullTrustedAndBoot_persistAsNull() = runTest {
        val s = sample(wall = 5_000L, elapsed = 10_000L, trusted = null, boot = null)
        repository.createPhotoMark(
            markId = "photo-nosync",
            cp = cp(30, cost = 7),
            raceId = 1,
            teamId = 7,
            paths = listOf("marks/photo-nosync/a.jpg"),
            expectedCount = 3,
            sample = s,
        )
        val mark = markDao.getById("photo-nosync")!!
        assertEquals(s.wallMs, mark.takenAt)
        assertNull(mark.trustedTakenAt)
        assertNull(mark.bootCount)
        assertTrue(mark.complete)
    }

    @Test
    fun createPhotoMark_lockedCheckpoint_costFallsBackToZero() = runTest {
        // The core "метку сорвали" case: a still-locked КП has cost = null. The hybrid take is scored
        // with cost 0 until a later reveal; the live-cost resolver picks up the real value afterwards.
        repository.createPhotoMark(
            markId = "photo-2",
            cp = cp(31, cost = null, locked = true),
            raceId = 1,
            teamId = 7,
            paths = listOf("marks/photo-2/a.jpg"),
            expectedCount = 3,
            sample = sample(),
        )
        val mark = markDao.getById("photo-2")!!
        assertEquals(0, mark.cost)
        assertTrue(mark.complete)
        assertEquals(setOf(31), takenPoints(repository.observeMarks(7).first()))
    }

    @Test
    fun attachPhotos_appendsPaths_withoutNewRow_orResetUploadFlags() = runTest {
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        // Pretend the NFC row was already uploaded before the photo was attached.
        markDao.markUploadedLocal(listOf(id))
        markDao.markUploadedCloud(listOf(id))

        repository.attachPhotos(id, listOf("marks/$id/a.jpg"), now = 2_000L)
        repository.attachPhotos(id, listOf("marks/$id/b.jpg"), now = 3_000L)

        val mark = markDao.getById(id)!!
        assertEquals(listOf("marks/$id/a.jpg", "marks/$id/b.jpg"), photoPaths(mark.photoPath))
        assertEquals(3_000L, mark.updatedAt)
        // No new rows spawned by attaching.
        assertEquals(1, repository.observeMarks(7).first().size)
        // photoPath is not in the marks DTO → attaching must NOT re-queue the already-uploaded row.
        assertTrue(mark.uploadedLocal)
        assertTrue(mark.uploadedCloud)
    }

    @Test
    fun attachPhotos_missingRow_isNoOp() = runTest {
        repository.attachPhotos("nope", listOf("marks/nope/a.jpg"), now = 2_000L)
        assertTrue(repository.observeMarks(7).first().isEmpty())
    }

    @Test
    fun attachPhotos_unsafeNewPaths_areDropped() = runTest {
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        repository.attachPhotos(
            id,
            listOf(
                "/absolute/path.jpg",   // absolute path — rejected by traversal guard
                "../escape.jpg",        // path traversal — rejected
                "marks/$id/valid.jpg",  // valid relative path — kept
            ),
            now = 2_000L,
        )
        val mark = markDao.getById(id)!!
        assertEquals(listOf("marks/$id/valid.jpg"), photoPaths(mark.photoPath))
    }

    @Test
    fun attachPhotos_duplicatePaths_areDeduped() = runTest {
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        repository.attachPhotos(id, listOf("marks/$id/a.jpg"), now = 2_000L)
        // Second call with the same path must not duplicate it (e.g. on a retry).
        repository.attachPhotos(id, listOf("marks/$id/a.jpg"), now = 3_000L)
        val mark = markDao.getById(id)!!
        assertEquals(listOf("marks/$id/a.jpg"), photoPaths(mark.photoPath))
    }

    @Test
    fun attachPhotos_corruptedExistingPhotoPath_onlyStoresNewPaths() = runTest {
        // If the existing photoPath column holds corrupted JSON, photoPaths() returns emptyList().
        // The merge result is then only newPaths — the corrupted data is silently dropped.
        val id = startTake(point = 10, expectedCount = 1, buffered = setOf(1))
        markDao.setPhotoPath(id, "not-valid-json")

        repository.attachPhotos(id, listOf("marks/$id/a.jpg"), now = 2_000L)

        val mark = markDao.getById(id)!!
        assertEquals(listOf("marks/$id/a.jpg"), photoPaths(mark.photoPath))
    }

    @Test
    fun derivation_distinctScoredPointsAndScore() = runTest {
        startTake(point = 10, cost = 5, expectedCount = 1, buffered = setOf(1))
        startTake(point = 11, cost = 8, expectedCount = 1, buffered = setOf(1))
        // Partial take of 12 — stored but not scored.
        startTake(point = 12, cost = 13, expectedCount = 2, buffered = setOf(1))
        // Repeat take of 10 — must not double-count.
        startTake(point = 10, cost = 5, expectedCount = 1, buffered = setOf(1))

        val marks = repository.observeMarks(7).first()
        assertEquals(2, takenPointCount(marks))
        assertEquals(13, totalScore(marks))
    }
}

private class FakeMarkDao : MarkDao {
    private val rows = MutableStateFlow<List<MarkEntity>>(emptyList())

    override fun observeForTeam(teamId: Int): Flow<List<MarkEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId }.sortedByDescending { it.trustedTakenAt ?: it.takenAt } }

    override suspend fun getById(id: String): MarkEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun allIds(): List<String> = rows.value.map { it.id }

    override suspend fun upsert(mark: MarkEntity) {
        rows.value = rows.value.filterNot { it.id == mark.id } + mark
    }

    override suspend fun attachLocation(
        id: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        altitude: Double?,
        verticalAccuracy: Float?,
        gpsTimeMs: Long?,
        elapsedRealtimeAt: Long,
    ) {
        // Column-scoped: only loc* and uploaded* columns change; a missing row is a no-op.
        // Mirrors the SQL: resets upload flags so a late-arriving location re-queues the row.
        rows.value = rows.value.map { row ->
            if (row.id == id) {
                row.copy(
                    locLat = lat,
                    locLon = lon,
                    locAccuracy = accuracy,
                    locAltitude = altitude,
                    locVerticalAccuracy = verticalAccuracy,
                    locGpsTimeMs = gpsTimeMs,
                    locElapsedRealtimeAt = elapsedRealtimeAt,
                    uploadedLocal = false,
                    uploadedCloud = false,
                )
            } else {
                row
            }
        }
    }

    override fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts> =
        rows.map { list ->
            val scoped = list.filter { it.teamId == teamId && it.raceId == raceId }
            UploadCounts(
                total = scoped.size,
                local = scoped.count { it.uploadedLocal && (it.photoPath == null || it.photosUploadedLocal) },
                cloud = scoped.count { it.uploadedCloud && (it.photoPath == null || it.photosUploadedCloud) },
            )
        }

    override fun uploadCountsMetadata(teamId: Int, raceId: Int): Flow<UploadCounts> =
        rows.map { list ->
            val scoped = list.filter { it.teamId == teamId && it.raceId == raceId }
            UploadCounts(
                total = scoped.size,
                local = scoped.count { it.uploadedLocal },
                cloud = scoped.count { it.uploadedCloud },
            )
        }

    override fun photoFrameRows(teamId: Int, raceId: Int): Flow<List<PhotoFrameRow>> =
        rows.map { list ->
            list.filter { it.teamId == teamId && it.raceId == raceId && it.photoPath != null }
                .map { PhotoFrameRow(it.photoPath, it.photosUploadedLocal, it.photosUploadedCloud) }
        }

    // Phase 2: photo-mark metadata shares the drain with NFC marks (filter dropped).
    override suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedLocal }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter { it.raceId == raceId && it.teamId == teamId && !it.uploadedCloud }
            .sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun framePendingLocal(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter {
            it.raceId == raceId && it.teamId == teamId &&
                it.uploadedLocal && !it.photosUploadedLocal && it.photoPath != null
        }.sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun framePendingCloud(raceId: Int, teamId: Int, limit: Int): List<MarkEntity> =
        rows.value.filter {
            it.raceId == raceId && it.teamId == teamId &&
                it.uploadedCloud && !it.photosUploadedCloud && it.photoPath != null
        }.sortedWith(compareBy({ it.trustedTakenAt ?: it.takenAt }, { it.id })).take(limit)

    override suspend fun setPhotosUploadedLocalIfUnchanged(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt) it.copy(photosUploadedLocal = true) else it
        }
    }

    override suspend fun setPhotosUploadedCloudIfUnchanged(id: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id && it.updatedAt == updatedAt) it.copy(photosUploadedCloud = true) else it
        }
    }

    override suspend fun updatePhotoPath(id: String, photoPath: String, now: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(photoPath = photoPath, updatedAt = now) else it
        }
    }

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

    override suspend fun pendingUploadScopes(): List<TrackScope> =
        rows.value.filter {
            !it.uploadedLocal || !it.uploadedCloud ||
                (it.photoPath != null && (!it.photosUploadedLocal || !it.photosUploadedCloud))
        }.map { TrackScope(it.raceId, it.teamId) }.distinct()

    /** Test helper: directly set photoPath without going through attachPhotos merge logic. */
    suspend fun setPhotoPath(id: String, raw: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(photoPath = raw) else it }
    }
}
