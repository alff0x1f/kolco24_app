package ru.kolco24.kolco24.ui.marks

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore
import ru.kolco24.kolco24.ui.legend.CheckpointColor

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
    fun `tile per event reverses to oldest-first so new takes append to the end`() {
        // Input is newest-first (as observeMarks delivers); tiles come back oldest-first.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, takenAt = 3_000L), // newest
            mark("b", point = 2, number = 4, cost = 3, takenAt = 2_000L),
            mark("c", point = 3, number = 7, cost = 2, takenAt = 1_000L), // oldest
        )
        val tiles = marksToTiles(marks)
        assertEquals(3, tiles.size)
        assertEquals(listOf("07", "04", "01"), tiles.map { it.number })
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
        // Oldest-first: the photo take (1_000L) comes first, the nfc take (2_000L) last.
        assertEquals(MarkKind.PHOTO, tiles[0].kind)
        assertEquals(MarkKind.NFC, tiles[1].kind)
    }

    @Test
    fun `time formats takenAt as HH colon mm`() {
        val epoch = 5_000_000L
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = epoch)))
        assertEquals(hhmm(epoch), tiles.single().time)
    }

    @Test
    fun `empty marks yield no tiles`() {
        assertTrue(marksToTiles(emptyList()).isEmpty())
    }

    @Test
    fun `incomplete takes are not tiled`() {
        // КП scanned without the whole team: an empty (0 members) and a partial take both stay
        // complete=false and must not appear as tiles.
        val marks = listOf(
            mark("empty", point = 1, number = 1, cost = 2, complete = false, takenAt = 3_000L),
            mark("partial", point = 2, number = 4, cost = 3, complete = false, takenAt = 2_000L),
        )
        assertTrue(marksToTiles(marks).isEmpty())
    }

    @Test
    fun `only completed takes are tiled in oldest-first order`() {
        // Newest event is incomplete and filtered out; the rest are tiled oldest-first.
        val marks = listOf(
            mark("incomplete", point = 1, number = 1, cost = 2, complete = false, takenAt = 4_000L),
            mark("done-new", point = 2, number = 4, cost = 3, complete = true, takenAt = 3_000L),
            mark("done-old", point = 3, number = 7, cost = 5, complete = true, takenAt = 2_000L),
        )
        val tiles = marksToTiles(marks)
        assertEquals(listOf("07", "04"), tiles.map { it.number })
    }

    @Test
    fun `colorOf resolves the per-take checkpoint color`() {
        val tiles = marksToTiles(
            listOf(
                mark("a", point = 1, number = 1, cost = 1, takenAt = 2_000L),
                mark("b", point = 2, number = 2, cost = 1, takenAt = 1_000L),
            ),
        ) { if (it.point == 1) CheckpointColor.BLUE else null }
        // Oldest-first: point 2 (null) first, point 1 (BLUE) last.
        assertEquals(null, tiles[0].color)
        assertEquals(CheckpointColor.BLUE, tiles[1].color)
    }

    @Test
    fun `color defaults to null without a resolver`() {
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1)))
        assertEquals(null, tiles.single().color)
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
