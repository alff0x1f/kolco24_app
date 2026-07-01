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
import ru.kolco24.kolco24.data.marks.encodePhotoPaths
import ru.kolco24.kolco24.data.marks.frameIdOf
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.UploadTarget

/** Upload-loop coverage for [MarkRepository] — a mirror of `TrackRepositoryTest`'s dual-batch tests. */
class MarkRepositoryUploadTest {

    private fun repo(
        dao: MarkDao,
        installId: String = "install-1",
        cloud: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
        local: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
        cloudPhoto: PhotoFrameUploader = PhotoFrameUploader { _, _, _, _ -> PostResult.Offline },
        localPhoto: PhotoFrameUploader = PhotoFrameUploader { _, _, _, _ -> PostResult.Offline },
        reader: PhotoFrameReader = PhotoFrameReader { null },
        onOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> },
    ) = MarkRepository(
        markDao = dao,
        sourceInstallId = installId,
        cloudUploader = cloud,
        localUploader = local,
        cloudPhotoUploader = cloudPhoto,
        localPhotoUploader = localPhoto,
        photoFrameReader = reader,
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

    /** Records `(markId, frameId)` calls and replies with [reply]; defaults to accepting every frame. */
    private class FakePhotoFrameUploader(
        val reply: suspend (markId: String, frameId: String) -> PostResult<Unit> = { _, _ -> PostResult.Success(Unit) },
    ) : PhotoFrameUploader {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun upload(raceId: Int, markId: String, frameId: String, bytes: ByteArray): PostResult<Unit> {
            calls.add(markId to frameId)
            return reply(markId, frameId)
        }
    }

    /** Reads whatever bytes were [put] for a path; a path never [put] reads as `null` (missing file). */
    private class FakeFileReader : PhotoFrameReader {
        private val files = mutableMapOf<String, ByteArray>()

        fun put(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
            files[path] = bytes
        }

        override fun read(relPath: String): ByteArray? = files[relPath]
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
    fun photoMark_metadataDrainedAlongsideNfcMark() = runTest {
        // Phase 2: photo-mark metadata now shares the drain with NFC marks (the method != 'photo'
        // filter is dropped); the real SQL is guarded by the instrumented MarkDaoTest.
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7) // nfc
        dao.seedPhoto(raceId = 1, teamId = 7) // photo
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local)

        r.uploadPending(raceId = 1, teamId = 7)

        val rows = dao.observeForTeam(7).first()
        val nfc = rows.single { it.method == "nfc" }
        val photo = rows.single { it.method == "photo" }
        assertTrue(nfc.uploadedLocal && nfc.uploadedCloud)
        assertTrue(photo.uploadedLocal && photo.uploadedCloud)
        // Both marks' metadata reach the uploader in the same batch.
        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun photoOnlyScope_walkedByUploadAllPending() = runTest {
        // Phase 2: a scope holding only a photo mark is no longer excluded from pendingUploadScopes —
        // its metadata now drains like any other mark.
        val dao = FakeMarkUploadDao()
        dao.seedPhoto(raceId = 9, teamId = 3)
        val cloud = FakeUploader()
        val local = FakeUploader()
        val r = repo(dao, cloud = cloud, local = local)

        r.uploadAllPending()

        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun uploadCounts_countsPhotoMarkMetadataWithNoFrames() = runTest {
        val dao = FakeMarkUploadDao()
        dao.seed(1, raceId = 1, teamId = 7) // nfc, drains
        dao.seedPhoto(raceId = 1, teamId = 7) // photo, no photoPath attached yet
        repo(dao, cloud = FakeUploader(), local = FakeUploader()).uploadPending(raceId = 1, teamId = 7)

        val counts = dao.uploadCounts(7, 1).first()
        // A photo mark with no photoPath (frames not attached) counts as uploaded once its metadata
        // lands, mirroring the real uploadCounts formula: uploadedX AND (photoPath IS NULL OR photosUploadedX).
        assertEquals(UploadCounts(total = 2, local = 2, cloud = 2), counts)
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

    @Test
    fun frameDrain_metadataFirstOrdering_noFramePostWhileMetadataPending() = runTest {
        // Metadata not yet uploaded (uploadedLocal/Cloud = false) — the metadata loop's own uploader
        // fails, so it never flips. The frame-pending DAO query gates on uploadedX = 1, so the photo
        // uploader must never be invoked this trigger.
        val dao = FakeMarkUploadDao()
        val id = dao.seedPhotoWithFrames(
            raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"), uploadedLocal = false, uploadedCloud = false,
        )
        val reader = FakeFileReader().apply { put("marks/m/f1.jpg") }
        val cloudPhoto = FakePhotoFrameUploader()
        val localPhoto = FakePhotoFrameUploader()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Offline },
            local = FakeUploader { PostResult.Offline },
            cloudPhoto = cloudPhoto,
            localPhoto = localPhoto,
            reader = reader,
        )

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(cloudPhoto.calls.isEmpty())
        assertTrue(localPhoto.calls.isEmpty())
        assertFalse(dao.rowById(id).photosUploadedCloud)
        assertFalse(dao.rowById(id).photosUploadedLocal)
    }

    @Test
    fun frameDrain_allFramesAccepted_flipsPhotosUploadedBothTargets() = runTest {
        val dao = FakeMarkUploadDao()
        val paths = listOf("marks/m/f1.jpg", "marks/m/f2.jpg")
        val id = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = paths)
        val reader = FakeFileReader().apply { paths.forEach { put(it) } }
        val cloudPhoto = FakePhotoFrameUploader()
        val localPhoto = FakePhotoFrameUploader()
        val r = repo(dao, cloudPhoto = cloudPhoto, localPhoto = localPhoto, reader = reader)

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(dao.rowById(id).photosUploadedCloud)
        assertTrue(dao.rowById(id).photosUploadedLocal)
        assertEquals(paths.map { frameIdOf(it) }.toSet(), cloudPhoto.calls.map { it.second }.toSet())
        assertEquals(paths.map { frameIdOf(it) }.toSet(), localPhoto.calls.map { it.second }.toSet())
    }

    @Test
    fun frameDrain_transientFailure_stopsTargetBeforeLaterMarks_retriedNextTrigger() = runTest {
        val dao = FakeMarkUploadDao()
        val idA = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/a/f1.jpg"))
        val idB = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/b/f1.jpg"))
        val reader = FakeFileReader().apply {
            put("marks/a/f1.jpg")
            put("marks/b/f1.jpg")
        }
        var offline = true
        val cloudPhoto = FakePhotoFrameUploader { _, _ -> if (offline) PostResult.Offline else PostResult.Success(Unit) }
        val r = repo(dao, cloudPhoto = cloudPhoto, reader = reader)

        r.uploadPending(raceId = 1, teamId = 7)

        // First (ordering: idA before idB) mark's frame fails transiently — the whole cloud target
        // stops immediately; idB is never attempted this trigger.
        assertEquals(1, cloudPhoto.calls.size)
        assertFalse(dao.rowById(idA).photosUploadedCloud)
        assertFalse(dao.rowById(idB).photosUploadedCloud)

        offline = false
        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(dao.rowById(idA).photosUploadedCloud)
        assertTrue(dao.rowById(idB).photosUploadedCloud)
    }

    @Test
    fun frameDrain_hardFailureOnOneMark_leavesItPending_laterGoodMarkStillFlips() = runTest {
        val dao = FakeMarkUploadDao()
        val idBad = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/bad/f1.jpg"))
        val idGood = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/good/f1.jpg"))
        val reader = FakeFileReader().apply {
            put("marks/bad/f1.jpg")
            put("marks/good/f1.jpg")
        }
        val cloudPhoto = FakePhotoFrameUploader { markId, _ ->
            if (markId == idBad) PostResult.BadRequest else PostResult.Success(Unit)
        }
        val r = repo(dao, cloudPhoto = cloudPhoto, reader = reader)

        r.uploadPending(raceId = 1, teamId = 7)

        assertFalse(dao.rowById(idBad).photosUploadedCloud)
        assertTrue(dao.rowById(idGood).photosUploadedCloud)
    }

    @Test
    fun frameDrain_missingFile_keepsMarkPending_drainTerminatesWithoutSpinning() = runTest {
        val dao = FakeMarkUploadDao()
        val id = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"))
        val cloudPhoto = FakePhotoFrameUploader()
        // Reader never has the file → null read.
        val r = repo(dao, cloudPhoto = cloudPhoto, reader = FakeFileReader())

        r.uploadPending(raceId = 1, teamId = 7) // must terminate, not spin forever

        assertFalse(dao.rowById(id).photosUploadedCloud)
        assertTrue(cloudPhoto.calls.isEmpty()) // never even attempted the POST for an unreadable frame
    }

    @Test
    fun frameDrain_dualTargetIndependence_lanOfflineCloudStillFlips() = runTest {
        val dao = FakeMarkUploadDao()
        val id = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"))
        val reader = FakeFileReader().apply { put("marks/m/f1.jpg") }
        val cloudPhoto = FakePhotoFrameUploader()
        val localPhoto = FakePhotoFrameUploader { _, _ -> PostResult.Offline }
        val r = repo(dao, cloudPhoto = cloudPhoto, localPhoto = localPhoto, reader = reader)

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(dao.rowById(id).photosUploadedCloud)
        assertFalse(dao.rowById(id).photosUploadedLocal)
    }

    @Test
    fun frameDrain_attachPhotos_requeuesFrames() = runTest {
        val dao = FakeMarkUploadDao()
        val id = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"))
        val reader = FakeFileReader().apply {
            put("marks/m/f1.jpg")
            put("marks/m/f2.jpg")
        }
        val cloudPhoto = FakePhotoFrameUploader()
        val localPhoto = FakePhotoFrameUploader()
        val r = repo(dao, cloudPhoto = cloudPhoto, localPhoto = localPhoto, reader = reader)
        r.uploadPending(raceId = 1, teamId = 7)
        assertTrue(dao.rowById(id).photosUploadedCloud)

        r.attachPhotos(id, listOf("marks/m/f2.jpg"), now = dao.rowById(id).updatedAt + 1_000L)

        assertFalse(dao.rowById(id).photosUploadedCloud)
        assertFalse(dao.rowById(id).photosUploadedLocal)

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(dao.rowById(id).photosUploadedCloud)
        assertTrue(dao.rowById(id).photosUploadedLocal)
        assertTrue(cloudPhoto.calls.any { it.second == frameIdOf("marks/m/f2.jpg") })
    }

    @Test
    fun frameDrain_attachPhotosRacingMidDrainFlip_doesNotStrandNewFrame() = runTest {
        // Reproduce the version-guard race: the drain fetches the mark (capturing its stale
        // updatedAt), then — while uploading that mark's one captured frame — attachPhotos appends a
        // new frame and bumps updatedAt. setPhotosUploadedCloudIfUnchanged(id, staleUpdatedAt) must
        // no-op (guard fails on the mismatch) rather than falsely flip the mark to "done" with f2
        // unsent; the drain loop's own re-fetch then picks the still-pending mark back up and sends
        // f1 (idempotent) plus the new f2 — nothing is stranded.
        val dao = FakeMarkUploadDao()
        val id = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"))
        val reader = FakeFileReader().apply {
            put("marks/m/f1.jpg")
            put("marks/m/f2.jpg")
        }
        var raced = false
        val cloudPhoto = FakePhotoFrameUploader { _, _ ->
            if (!raced) {
                raced = true
                dao.simulateAttachPhotos(id, listOf("marks/m/f2.jpg"), now = dao.rowById(id).updatedAt + 1_000L)
            }
            PostResult.Success(Unit)
        }
        val r = repo(dao, cloudPhoto = cloudPhoto, reader = reader)

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(raced)
        // Converges without stranding: both the racing mid-drain frame (f2) and the original (f1)
        // ultimately reach the uploader, and the mark ends up correctly flipped (not left stuck).
        assertTrue(dao.rowById(id).photosUploadedCloud)
        assertTrue(cloudPhoto.calls.any { it.second == frameIdOf("marks/m/f1.jpg") })
        assertTrue(cloudPhoto.calls.any { it.second == frameIdOf("marks/m/f2.jpg") })
    }

    @Test
    fun frameDrain_combinedOutcome_metadataErrorFrameOk_finalOutcomeNotOk() = runTest {
        val dao = FakeMarkUploadDao()
        // A plain nfc mark whose metadata upload fails outright (Forbidden → Error).
        dao.seed(1, raceId = 1, teamId = 7)
        // A photo mark whose metadata is already uploaded and whose one frame succeeds.
        val photoId = dao.seedPhotoWithFrames(raceId = 1, teamId = 7, paths = listOf("marks/m/f1.jpg"))
        val reader = FakeFileReader().apply { put("marks/m/f1.jpg") }
        val rec = OutcomeRecorder()
        val r = repo(
            dao,
            cloud = FakeUploader { PostResult.Forbidden },
            cloudPhoto = FakePhotoFrameUploader(),
            reader = reader,
            onOutcome = rec.sink,
        )

        r.uploadPending(raceId = 1, teamId = 7)

        assertTrue(dao.rowById(photoId).photosUploadedCloud) // the frame itself did succeed...
        // ...but the combined per-target outcome must not read Ok: metadata Error takes precedence.
        assertEquals(UploadResultKind.Error, rec.kindFor(UploadTarget.Cloud))
    }
}

/** Pure precedence coverage for [combineOutcome]: `Error` > `Offline` > `Ok` > `null`. */
class CombineOutcomeTest {
    private val kinds: List<UploadResultKind?> = listOf(UploadResultKind.Error, UploadResultKind.Offline, UploadResultKind.Ok, null)

    @Test
    fun allSixteenOrderedCombinations_matchFixedPrecedence() {
        for (metadata in kinds) {
            for (frame in kinds) {
                val expected = when {
                    metadata == UploadResultKind.Error || frame == UploadResultKind.Error -> UploadResultKind.Error
                    metadata == UploadResultKind.Offline || frame == UploadResultKind.Offline -> UploadResultKind.Offline
                    metadata == UploadResultKind.Ok || frame == UploadResultKind.Ok -> UploadResultKind.Ok
                    else -> null
                }
                assertEquals(
                    "combineOutcome($metadata, $frame)",
                    expected,
                    combineOutcome(metadata, frame),
                )
            }
        }
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

    /** Seed one local-only photo mark (method="photo", uploaded*=0) — must be excluded from the drain. */
    fun seedPhoto(raceId: Int, teamId: Int) {
        val i = seq++
        rows.value = rows.value + MarkEntity(
            id = "photo-$i",
            raceId = raceId,
            teamId = teamId,
            checkpointId = 20,
            checkpointNumber = 20,
            cost = 0,
            method = "photo",
            cpUid = "",
            cpCode = "",
            present = emptyList(),
            expectedCount = 3,
            complete = true,
            takenAt = 2_000L + i,
            updatedAt = 2_000L + i,
        )
    }

    /**
     * Seed a photo mark carrying [paths] as its frame list, with metadata upload flags pre-set (so the
     * frame drain's `uploadedX = 1` gate is already satisfied) and `photosUploaded*` at false.
     */
    fun seedPhotoWithFrames(
        raceId: Int,
        teamId: Int,
        paths: List<String>,
        uploadedLocal: Boolean = true,
        uploadedCloud: Boolean = true,
    ): String {
        val i = seq++
        val id = "photo-$i"
        rows.value = rows.value + MarkEntity(
            id = id,
            raceId = raceId,
            teamId = teamId,
            checkpointId = 20,
            checkpointNumber = 20,
            cost = 0,
            method = "photo",
            cpUid = "",
            cpCode = "",
            present = emptyList(),
            expectedCount = 3,
            complete = true,
            photoPath = encodePhotoPaths(paths),
            uploadedLocal = uploadedLocal,
            uploadedCloud = uploadedCloud,
            takenAt = 2_000L + i,
            updatedAt = 2_000L + i,
        )
        return id
    }

    /** Bump [MarkEntity.updatedAt] and append [newPaths] — simulates `attachPhotos` racing a drain. */
    suspend fun simulateAttachPhotos(id: String, newPaths: List<String>, now: Long) {
        attachPhotos(id, newPaths, now)
    }

    fun rowById(id: String): MarkEntity = rows.value.single { it.id == id }

    override fun observeForTeam(teamId: Int): Flow<List<MarkEntity>> =
        rows.map { list -> list.filter { it.teamId == teamId } }

    override suspend fun getById(id: String): MarkEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun allIds(): List<String> = rows.value.map { it.id }

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
                local = scoped.count { it.uploadedLocal && (it.photoPath == null || it.photosUploadedLocal) },
                cloud = scoped.count { it.uploadedCloud && (it.photoPath == null || it.photosUploadedCloud) },
            )
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
            if (it.id == id) {
                it.copy(
                    photoPath = photoPath,
                    updatedAt = now,
                    photosUploadedLocal = false,
                    photosUploadedCloud = false,
                )
            } else it
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

    /** Simulate a GPS fix arriving for [id] (writes locLat so the conditional mark skips it). */
    fun simulateGpsArrival(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(locLat = 55.75) else it }
    }

    /** Simulate [addMember] arriving during an upload (bumps [MarkEntity.updatedAt]). */
    fun simulateMemberAdded(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(updatedAt = it.updatedAt + 1_000L) else it }
    }

    override suspend fun pendingUploadScopes(): List<TrackScope> =
        rows.value.filter {
            !it.uploadedLocal || !it.uploadedCloud ||
                (it.photoPath != null && (!it.photosUploadedLocal || !it.photosUploadedCloud))
        }.map { TrackScope(it.raceId, it.teamId) }.distinct()
}
