package ru.kolco24.kolco24.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [JudgeScanDao] against **real Room** — scoping by `raceId`, the trusted-then-wall
 * ordering, and the write-once upload flags can't be covered by the JVM fakes.
 */
@RunWith(AndroidJUnit4::class)
class JudgeScanDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: JudgeScanDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.judgeScanDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scan(
        id: String,
        raceId: Int = 1,
        eventType: String = "start",
        participantNumber: Int = 42,
        nfcUid: String = "AABBCC",
        takenAt: Long = 1_000L,
        trustedTakenAt: Long? = null,
        elapsedRealtimeAt: Long = 500L,
        bootCount: Int? = 3,
        sourceInstallId: String = "install-1",
        uploadedLocal: Boolean = false,
        uploadedCloud: Boolean = false,
    ) = JudgeScanEntity(
        id = id,
        raceId = raceId,
        eventType = eventType,
        participantNumber = participantNumber,
        nfcUid = nfcUid,
        takenAt = takenAt,
        trustedTakenAt = trustedTakenAt,
        elapsedRealtimeAt = elapsedRealtimeAt,
        bootCount = bootCount,
        sourceInstallId = sourceInstallId,
        uploadedLocal = uploadedLocal,
        uploadedCloud = uploadedCloud,
    )

    @Test
    fun unuploadedLocalAndCloud_scopedByRaceId() = runBlocking {
        dao.insert(scan("race1-a", raceId = 1))
        dao.insert(scan("race1-b", raceId = 1, uploadedLocal = true, uploadedCloud = true))
        dao.insert(scan("race2-a", raceId = 2))

        val local = dao.unuploadedLocal(raceId = 1, limit = 100).map { it.id }
        val cloud = dao.unuploadedCloud(raceId = 1, limit = 100).map { it.id }

        assertEquals(listOf("race1-a"), local)
        assertEquals(listOf("race1-a"), cloud)
    }

    @Test
    fun unuploadedLocal_orderedByTrustedThenWallTime() = runBlocking {
        // "later" has no trusted time and a later wall time than "earlier-trusted" — but since
        // "earlier-trusted"'s trusted time is small, it must still sort first via COALESCE.
        dao.insert(scan("later", takenAt = 5_000L, trustedTakenAt = null))
        dao.insert(scan("earlier-trusted", takenAt = 9_000L, trustedTakenAt = 1_000L))
        dao.insert(scan("middle", takenAt = 3_000L, trustedTakenAt = 3_000L))

        val ordered = dao.unuploadedLocal(raceId = 1, limit = 100).map { it.id }

        assertEquals(listOf("earlier-trusted", "middle", "later"), ordered)
    }

    @Test
    fun markUploadedLocalAndCloud_flipsOnlyGivenRows() = runBlocking {
        dao.insert(scan("a"))
        dao.insert(scan("b"))
        dao.insert(scan("c"))

        dao.markUploadedLocal(listOf("a", "b"))
        dao.markUploadedCloud(listOf("a"))

        val remainingLocal = dao.unuploadedLocal(raceId = 1, limit = 100).map { it.id }.toSet()
        val remainingCloud = dao.unuploadedCloud(raceId = 1, limit = 100).map { it.id }.toSet()

        assertEquals(setOf("c"), remainingLocal)
        assertEquals(setOf("b", "c"), remainingCloud)
    }

    @Test
    fun pendingUploadRaces_returnsDistinctRacesWithAnyPendingTarget() = runBlocking {
        dao.insert(scan("r1-fully-uploaded", raceId = 1, uploadedLocal = true, uploadedCloud = true))
        dao.insert(scan("r2-local-pending", raceId = 2, uploadedLocal = false, uploadedCloud = true))
        dao.insert(scan("r2-second", raceId = 2, uploadedLocal = true, uploadedCloud = true))
        dao.insert(scan("r3-cloud-pending", raceId = 3, uploadedLocal = true, uploadedCloud = false))

        val pending = dao.pendingUploadRaces()

        assertEquals(setOf(2, 3), pending.toSet())
        assertTrue(!pending.contains(1))
    }
}
