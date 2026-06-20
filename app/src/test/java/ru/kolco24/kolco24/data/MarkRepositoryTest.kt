package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity

class MarkRepositoryTest {

    private lateinit var markDao: FakeMarkDao
    private lateinit var checkpointDao: MarkFakeCheckpointDao
    private lateinit var repository: MarkRepository

    @Before
    fun setUp() {
        markDao = FakeMarkDao()
        checkpointDao = MarkFakeCheckpointDao()
        repository = MarkRepository(markDao, checkpointDao)
    }

    private suspend fun startTake(
        point: Int = 10,
        cost: Int = 5,
        expectedCount: Int = 3,
        buffered: Set<Int> = emptySet(),
        now: Long = 1_000L,
    ): String = repository.startKpTake(
        raceId = 1,
        teamId = 7,
        point = point,
        number = point,
        cost = cost,
        cpUid = "CPUID$point",
        cpCode = "CODE$point",
        expectedCount = expectedCount,
        bufferedMembers = buffered,
        now = now,
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
        assertEquals(10, mark.point)
        assertEquals(5, mark.cost)
        assertEquals("nfc", mark.method)
        assertEquals("CPUID10", mark.cpUid)
        assertEquals("CODE10", mark.cpCode)
        assertEquals(listOf(1), mark.present)
        assertFalse(mark.complete)
        assertFalse(checkpointDao.isTaken(10))
    }

    @Test
    fun startKpTake_completeWhenBufferCoversRoster_marksTaken() = runTest {
        val id = startTake(point = 10, expectedCount = 2, buffered = setOf(1, 2))
        assertTrue(markDao.getById(id)!!.complete)
        assertTrue(checkpointDao.isTaken(10))
    }

    @Test
    fun addMember_accumulatesAndScoresOnFullRoster() = runTest {
        val id = startTake(point = 10, expectedCount = 3)
        repository.addMember(id, point = 10, numberInTeam = 1, expectedCount = 3, now = 1_100L)
        assertFalse(checkpointDao.isTaken(10))
        repository.addMember(id, point = 10, numberInTeam = 2, expectedCount = 3, now = 1_200L)
        // Idempotent rescan of an already-present member does not advance the count.
        repository.addMember(id, point = 10, numberInTeam = 2, expectedCount = 3, now = 1_300L)
        assertFalse(checkpointDao.isTaken(10))
        repository.addMember(id, point = 10, numberInTeam = 3, expectedCount = 3, now = 1_400L)
        assertTrue(checkpointDao.isTaken(10))
        val finalMark = markDao.getById(id)!!
        assertEquals(listOf(1, 2, 3), finalMark.present)
        assertTrue(finalMark.complete)
    }

    @Test
    fun addMember_missingRow_isNoOp() = runTest {
        repository.addMember("nope", point = 10, numberInTeam = 1, expectedCount = 1, now = 1L)
        assertFalse(checkpointDao.isTaken(10))
    }

    @Test
    fun repeatTakeOfSamePoint_createsNewRow() = runTest {
        val first = startTake(point = 10, expectedCount = 1, buffered = setOf(1), now = 1_000L)
        val second = startTake(point = 10, expectedCount = 1, buffered = setOf(1), now = 5_000L)
        assertNotEquals(first, second)
        assertEquals(2, repository.observeMarks(7).first().count { it.point == 10 })
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
        rows.map { list -> list.filter { it.teamId == teamId }.sortedByDescending { it.takenAt } }

    override suspend fun getById(id: String): MarkEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsert(mark: MarkEntity) {
        rows.value = rows.value.filterNot { it.id == mark.id } + mark
    }
}

private class MarkFakeCheckpointDao : CheckpointDao {
    private val taken = mutableSetOf<Int>()

    fun isTaken(id: Int): Boolean = id in taken

    override suspend fun markTaken(id: Int) {
        taken += id
    }

    override fun observeCheckpointsForRace(raceId: Int) = throw UnsupportedOperationException()
    override suspend fun insertCheckpoints(checkpoints: List<CheckpointEntity>) =
        throw UnsupportedOperationException()
    override suspend fun deleteCheckpointsForRace(raceId: Int) = throw UnsupportedOperationException()
    override suspend fun revealedForRace(raceId: Int): List<CheckpointEntity> =
        throw UnsupportedOperationException()
    override suspend fun reveal(id: Int, cost: Int, description: String?) =
        throw UnsupportedOperationException()
    override suspend fun takenIdsForRace(raceId: Int): List<Int> =
        throw UnsupportedOperationException()
    override suspend fun getCheckpointsForRace(raceId: Int): List<CheckpointEntity> =
        throw UnsupportedOperationException()
}
