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
 * Exercises [MarkDao]'s photo-related contracts against **real Room** — the `method != 'photo'` drain/
 * counter/scope filters and the [MarkDao.attachPhotos] `@Transaction` can't be covered by the JVM fakes
 * (Robolectric is not on the classpath, and the fakes only mirror the SQL). Guards Phase 1's invariant
 * that photo marks are stored/scored locally but never enter the marks-upload loop.
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
        updatedAt = 1_000L,
        uploadedLocal = uploadedLocal,
        uploadedCloud = uploadedCloud,
    )

    @Test
    fun unuploadedLocalAndCloud_excludePhotoMarks() = runBlocking {
        dao.upsert(mark("nfc-1", method = "nfc"))
        dao.upsert(mark("photo-1", method = "photo"))

        val local = dao.unuploadedLocal(raceId = 1, teamId = 7, limit = 100).map { it.id }
        val cloud = dao.unuploadedCloud(raceId = 1, teamId = 7, limit = 100).map { it.id }

        assertEquals(listOf("nfc-1"), local)
        assertEquals(listOf("nfc-1"), cloud)
    }

    @Test
    fun uploadCounts_excludePhotoMarks() = runBlocking {
        dao.upsert(mark("nfc-1", method = "nfc"))
        dao.upsert(mark("photo-1", method = "photo"))

        val counts = dao.uploadCounts(teamId = 7, raceId = 1).first()
        // total counts only the NFC mark; the photo mark is invisible to the status row.
        assertEquals(1, counts.total)
    }

    @Test
    fun pendingUploadScopes_excludePhotoOnlyScope() = runBlocking {
        // Scope (1,7) has an NFC mark; scope (9,3) has only a photo mark — it must not be returned.
        dao.upsert(mark("nfc-1", method = "nfc", raceId = 1, teamId = 7))
        dao.upsert(mark("photo-1", method = "photo", raceId = 9, teamId = 3))

        val scopes = dao.pendingUploadScopes()

        assertTrue(scopes.contains(TrackScope(1, 7)))
        assertFalse(scopes.contains(TrackScope(9, 3)))
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
