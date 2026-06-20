package ru.kolco24.kolco24.ui.marks

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore

class MarksMappingTest {

    private fun mark(
        id: String,
        point: Int,
        number: Int,
        cost: Int,
        method: String = "nfc",
        complete: Boolean = true,
        takenAt: Long = 1_000L,
    ) = MarkEntity(
        id = id,
        raceId = 1,
        teamId = 7,
        point = point,
        checkpointNumber = number,
        cost = cost,
        method = method,
        cpUid = "UID",
        cpCode = "CODE",
        present = emptyList(),
        expectedCount = 0,
        complete = complete,
        takenAt = takenAt,
        updatedAt = takenAt,
    )

    private fun hhmm(epoch: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(epoch))

    @Test
    fun `tile per event preserves order and count`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, takenAt = 3_000L),
            mark("b", point = 2, number = 4, cost = 3, takenAt = 2_000L),
            mark("c", point = 1, number = 1, cost = 2, takenAt = 1_000L), // repeat of point 1
        )
        val tiles = marksToTiles(marks)
        assertEquals(3, tiles.size)
        assertEquals(listOf("01", "04", "01"), tiles.map { it.number })
    }

    @Test
    fun `number is zero-padded to two digits`() {
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 5, cost = 1)))
        assertEquals("05", tiles.single().number)
    }

    @Test
    fun `method maps to kind`() {
        val tiles = marksToTiles(
            listOf(
                mark("a", point = 1, number = 1, cost = 1, method = "nfc", takenAt = 2_000L),
                mark("b", point = 2, number = 2, cost = 1, method = "photo", takenAt = 1_000L),
            ),
        )
        assertEquals(MarkKind.NFC, tiles[0].kind)
        assertEquals(MarkKind.PHOTO, tiles[1].kind)
    }

    @Test
    fun `time formats takenAt as HH colon mm`() {
        val epoch = 5_000_000L
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = epoch)))
        assertEquals(hhmm(epoch), tiles.single().time)
    }

    @Test
    fun `only the newest event is flagged recent`() {
        val tiles = marksToTiles(
            listOf(
                mark("a", point = 1, number = 1, cost = 1, takenAt = 3_000L),
                mark("b", point = 2, number = 2, cost = 1, takenAt = 2_000L),
                mark("c", point = 3, number = 3, cost = 1, takenAt = 1_000L),
            ),
        )
        assertTrue(tiles[0].isRecent)
        assertFalse(tiles[1].isRecent)
        assertFalse(tiles[2].isRecent)
    }

    @Test
    fun `empty marks yield no tiles`() {
        assertTrue(marksToTiles(emptyList()).isEmpty())
    }

    @Test
    fun `taken count is distinct complete points and repeat does not double score`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, complete = true),
            mark("b", point = 1, number = 1, cost = 2, complete = true), // repeat same point
            mark("c", point = 2, number = 4, cost = 3, complete = true),
            mark("d", point = 3, number = 7, cost = 5, complete = false), // partial, not scored
        )
        assertEquals(2, takenPointCount(marks))
        assertEquals(5, totalScore(marks)) // 2 + 3, repeat of point 1 not double-counted
    }
}
