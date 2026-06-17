package ru.kolco24.kolco24.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [CheckpointDao.replaceAllForRace]'s preserve-on-resync (option A) against **real Room** —
 * the `@Transaction` body can't be covered by a JVM fake (Robolectric is not on the classpath).
 */
@RunWith(AndroidJUnit4::class)
class CheckpointDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CheckpointDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.checkpointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun locked(id: Int, raceId: Int = 1, number: Int = id) = CheckpointEntity(
        id = id,
        raceId = raceId,
        number = number,
        cost = null,
        type = "kp",
        description = null,
        locked = true,
        encIv = "iv$id",
        encCt = "ct$id",
    )

    private fun open(id: Int, raceId: Int = 1, number: Int = id, cost: Int = 10) = CheckpointEntity(
        id = id,
        raceId = raceId,
        number = number,
        cost = cost,
        type = "kp",
        description = "Открытый $id",
        locked = false,
    )

    @Test
    fun reveal_thenResyncWithSameLockedPayload_keepsRevealedContent() = runBlocking {
        dao.replaceAllForRace(1, listOf(locked(10), open(20)))

        // User unlocks CP 10 offline.
        dao.reveal(10, cost = 40, description = "Под мостом")

        // A 200 refresh re-sends CP 10 still locked (server never sees the plaintext).
        dao.replaceAllForRace(1, listOf(locked(10), open(20)))

        val rows = dao.revealedForRace(1).associateBy { it.id }
        val cp10 = rows[10]!!
        assertEquals(40, cp10.cost)
        assertEquals("Под мостом", cp10.description)
        // reveal() clears locked; the enc envelope is retained for reference.
        assertFalse(cp10.locked)
        assertEquals("iv10", cp10.encIv)
    }

    @Test
    fun resync_doesNotRevealCheckpointsThatWereNeverUnlocked() = runBlocking {
        dao.replaceAllForRace(1, listOf(locked(10), locked(11)))
        dao.reveal(10, cost = 40, description = "Под мостом")

        dao.replaceAllForRace(1, listOf(locked(10), locked(11)))

        val revealed = dao.revealedForRace(1).map { it.id }
        assertEquals(listOf(10), revealed)
    }

    @Test
    fun resync_openRowOverwritesCleanly() = runBlocking {
        dao.replaceAllForRace(1, listOf(open(20, cost = 10)))
        // Server changes the open CP's cost — the fresh value wins (no stale preserve for open rows).
        dao.replaceAllForRace(1, listOf(open(20, cost = 25)))

        val cp20 = dao.revealedForRace(1).single { it.id == 20 }
        assertEquals(25, cp20.cost)
        assertEquals(false, cp20.locked)
    }

    @Test
    fun resync_droppingACheckpoint_removesIt() = runBlocking {
        dao.replaceAllForRace(1, listOf(open(20), open(21)))
        dao.replaceAllForRace(1, listOf(open(20)))

        val ids = dao.revealedForRace(1).map { it.id }
        assertEquals(listOf(20), ids)
        assertNull(dao.revealedForRace(1).firstOrNull { it.id == 21 })
    }
}
