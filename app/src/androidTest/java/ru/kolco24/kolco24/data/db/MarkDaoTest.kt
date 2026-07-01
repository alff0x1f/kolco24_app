package ru.kolco24.kolco24.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.kolco24.kolco24.data.marks.photoPaths

/**
 * Exercises [MarkDao]'s photo-related contracts against **real Room** — the frame-drain queries and the
 * [MarkDao.attachPhotos] `@Transaction` can't be covered by the JVM fakes (Robolectric is not on the
 * classpath, and the fakes only mirror the SQL). Guards Phase 2's invariants: photo-mark metadata now
 * shares the `/marks/` drain with NFC marks, frames drain separately once metadata lands, and
 * `photosUploaded*` tracks per-mark frame completion independently per target.
 */
@RunWith(AndroidJUnit4::class)
class MarkDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MarkDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.markDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun mark(
        id: String,
        method: String,
        raceId: Int = 1,
        teamId: Int = 7,
        photoPath: String? = null,
        uploadedLocal: Boolean = false,
        uploadedCloud: Boolean = false,
        photosUploadedLocal: Boolean = false,
        photosUploadedCloud: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = MarkEntity(
        id = id,
        raceId = raceId,
        teamId = teamId,
        checkpointId = 10,
        checkpointNumber = 10,
        cost = if (method == "photo") 0 else 5,
        method = method,
        cpUid = if (method == "photo") "" else "CPUID",
        cpCode = if (method == "photo") "" else "CODE",
        present = if (method == "photo") emptyList() else listOf(1),
        expectedCount = if (method == "photo") 3 else 1,
        complete = true,
        photoPath = photoPath,
        takenAt = 1_000L,
        updatedAt = updatedAt,
        uploadedLocal = uploadedLocal,
        uploadedCloud = uploadedCloud,
        photosUploadedLocal = photosUploadedLocal,
        photosUploadedCloud = photosUploadedCloud,
    )

    @Test
    fun unuploadedLocalAndCloud_includePhotoMarks() = runBlocking {
        // Phase 2: photo-mark metadata now shares the drain with NFC marks (filter dropped).
        dao.upsert(mark("nfc-1", method = "nfc"))
        dao.upsert(mark("photo-1", method = "photo"))

        val local = dao.unuploadedLocal(raceId = 1, teamId = 7, limit = 100).map { it.id }.toSet()
        val cloud = dao.unuploadedCloud(raceId = 1, teamId = 7, limit = 100).map { it.id }.toSet()

        assertEquals(setOf("nfc-1", "photo-1"), local)
        assertEquals(setOf("nfc-1", "photo-1"), cloud)
    }

    @Test
    fun uploadCounts_photoMarkCountsOnlyWhenMetadataAndFramesUploaded() = runBlocking {
        dao.upsert(mark("nfc-1", method = "nfc", uploadedLocal = true, uploadedCloud = true))
        // Metadata uploaded but frames not yet — must not count as uploaded for either target.
        dao.upsert(
            mark(
                "photo-1", method = "photo", photoPath = "[\"marks/photo-1/a.jpg\"]",
                uploadedLocal = true, uploadedCloud = true,
                photosUploadedLocal = false, photosUploadedCloud = false,
            ),
        )

        val counts = dao.uploadCounts(teamId = 7, raceId = 1).first()
        assertEquals(2, counts.total)
        assertEquals(1, counts.local)
        assertEquals(1, counts.cloud)

        dao.setPhotosUploadedLocalIfUnchanged("photo-1", updatedAt = 1_000L)
        dao.setPhotosUploadedCloudIfUnchanged("photo-1", updatedAt = 1_000L)

        val countsAfter = dao.uploadCounts(teamId = 7, raceId = 1).first()
        assertEquals(2, countsAfter.local)
        assertEquals(2, countsAfter.cloud)
    }

    @Test
    fun pendingUploadScopes_includesPhotoOnlyScope() = runBlocking {
        // Phase 2: a scope with only a photo mark is no longer excluded (filter dropped).
        dao.upsert(mark("nfc-1", method = "nfc", raceId = 1, teamId = 7))
        dao.upsert(mark("photo-1", method = "photo", raceId = 9, teamId = 3))

        val scopes = dao.pendingUploadScopes()

        assertTrue(scopes.contains(TrackScope(1, 7)))
        assertTrue(scopes.contains(TrackScope(9, 3)))
    }

    @Test
    fun pendingUploadScopes_widenedForPendingFramesOnly() = runBlocking {
        // Metadata fully uploaded but frames still pending — must still be returned so the frame
        // drain keeps re-triggering for this scope.
        dao.upsert(
            mark(
                "photo-1", method = "photo", raceId = 5, teamId = 2,
                photoPath = "[\"marks/photo-1/a.jpg\"]",
                uploadedLocal = true, uploadedCloud = true,
                photosUploadedLocal = true, photosUploadedCloud = false,
            ),
        )

        val scopes = dao.pendingUploadScopes()

        assertTrue(scopes.contains(TrackScope(5, 2)))
    }

    @Test
    fun framePending_filtersByMetadataUploadedFlagAndFlagAndPath() = runBlocking {
        // Eligible: metadata uploaded, frames not uploaded, has a photoPath.
        dao.upsert(
            mark(
                "eligible", method = "photo", photoPath = "[\"marks/eligible/a.jpg\"]",
                uploadedLocal = true, photosUploadedLocal = false,
            ),
        )
        // Excluded: metadata not yet uploaded (uploadedLocal = 0).
        dao.upsert(
            mark(
                "no-metadata", method = "photo", photoPath = "[\"marks/no-metadata/a.jpg\"]",
                uploadedLocal = false, photosUploadedLocal = false,
            ),
        )
        // Excluded: frames already uploaded (photosUploadedLocal = 1).
        dao.upsert(
            mark(
                "frames-done", method = "photo", photoPath = "[\"marks/frames-done/a.jpg\"]",
                uploadedLocal = true, photosUploadedLocal = true,
            ),
        )
        // Excluded: no photoPath at all.
        dao.upsert(mark("no-photo", method = "nfc", uploadedLocal = true, photosUploadedLocal = false))

        val pending = dao.framePendingLocal(raceId = 1, teamId = 7, limit = 100).map { it.id }

        assertEquals(listOf("eligible"), pending)
    }

    @Test
    fun framePending_zeroFrameRowIsStillSelected() = runBlocking {
        // An empty-but-non-null photoPath ("[]") is a photo-frame-drain candidate at the DAO level —
        // MarkRepository's drain loop is responsible for immediately flipping it without a spin.
        dao.upsert(
            mark(
                "zero-frames", method = "photo", photoPath = "[]",
                uploadedLocal = true, photosUploadedLocal = false,
            ),
        )

        val pending = dao.framePendingLocal(raceId = 1, teamId = 7, limit = 100).map { it.id }

        assertEquals(listOf("zero-frames"), pending)
    }

    @Test
    fun setPhotosUploadedIfUnchanged_flipsOnMatchingUpdatedAt_noOpsOnStale() = runBlocking {
        dao.upsert(
            mark(
                "photo-1", method = "photo", photoPath = "[\"marks/photo-1/a.jpg\"]",
                uploadedLocal = true, uploadedCloud = true, updatedAt = 1_000L,
            ),
        )

        // Stale updatedAt (simulates an attachPhotos race) — must no-op.
        dao.setPhotosUploadedLocalIfUnchanged("photo-1", updatedAt = 999L)
        assertFalse(dao.getById("photo-1")!!.photosUploadedLocal)

        // Matching updatedAt — flips.
        dao.setPhotosUploadedLocalIfUnchanged("photo-1", updatedAt = 1_000L)
        dao.setPhotosUploadedCloudIfUnchanged("photo-1", updatedAt = 1_000L)
        val row = dao.getById("photo-1")!!
        assertTrue(row.photosUploadedLocal)
        assertTrue(row.photosUploadedCloud)
    }

    @Test
    fun attachPhotos_mergesPaths_onlyTouchesPhotoPathAndUpdatedAt() = runBlocking {
        // An already-uploaded NFC row: attaching photos must merge paths and bump updatedAt, but leave
        // uploaded* and present intact (photoPath is not in the marks DTO).
        dao.upsert(mark("nfc-1", method = "nfc", uploadedLocal = true, uploadedCloud = true))

        dao.attachPhotos("nfc-1", listOf("marks/nfc-1/a.jpg"), now = 2_000L)
        dao.attachPhotos("nfc-1", listOf("marks/nfc-1/b.jpg"), now = 3_000L)

        val row = dao.getById("nfc-1")!!
        assertEquals(listOf("marks/nfc-1/a.jpg", "marks/nfc-1/b.jpg"), photoPaths(row.photoPath))
        assertEquals(3_000L, row.updatedAt)
        // uploaded* must be unchanged — photoPath is not in the upload DTO.
        assertTrue(row.uploadedLocal)
        assertTrue(row.uploadedCloud)
        // All other columns must be untouched by the column-scoped UPDATE.
        assertEquals(listOf(1), row.present)
        assertTrue(row.complete)
        assertEquals("CPUID", row.cpUid)
        assertEquals("CODE", row.cpCode)
        assertEquals(1_000L, row.takenAt)
    }

    @Test
    fun attachPhotos_missingRow_isNoOp() = runBlocking {
        dao.attachPhotos("nope", listOf("marks/nope/a.jpg"), now = 2_000L)
        assertEquals(null, dao.getById("nope"))
    }
}
