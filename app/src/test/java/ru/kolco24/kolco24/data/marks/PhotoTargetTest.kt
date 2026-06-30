package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity

class PhotoTargetTest {

    private fun mark(
        id: String,
        point: Int = 1,
        number: Int = 11,
        method: String = "nfc",
        complete: Boolean = true,
        takenAt: Long = 1_000L,
        trustedTakenAt: Long? = null,
    ) = MarkEntity(
        id = id,
        raceId = 1,
        teamId = 7,
        checkpointId = point,
        checkpointNumber = number,
        cost = 5,
        method = method,
        cpUid = "UID",
        cpCode = "CODE",
        present = emptyList(),
        expectedCount = 0,
        complete = complete,
        takenAt = takenAt,
        updatedAt = takenAt,
        trustedTakenAt = trustedTakenAt,
    )

    @Test
    fun `empty list asks for number`() {
        assertEquals(PhotoTarget.AskNumber, decidePhotoTarget(emptyList(), nowMs = 10_000L))
    }

    @Test
    fun `recent complete take attaches`() {
        val marks = listOf(mark("a", point = 3, number = 42, takenAt = 100_000L))

        val target = decidePhotoTarget(marks, nowMs = 150_000L)

        assertEquals(PhotoTarget.AttachTo(markId = "a", cpNumber = 42, checkpointId = 3), target)
    }

    @Test
    fun `take older than the window asks for number`() {
        val marks = listOf(mark("a", takenAt = 100_000L))

        val target = decidePhotoTarget(marks, nowMs = 100_000L + PHOTO_ATTACH_WINDOW_MS + 1)

        assertEquals(PhotoTarget.AskNumber, target)
    }

    @Test
    fun `exactly three minutes still attaches (inclusive boundary)`() {
        val marks = listOf(mark("a", point = 3, number = 42, takenAt = 100_000L))

        val target = decidePhotoTarget(marks, nowMs = 100_000L + PHOTO_ATTACH_WINDOW_MS)

        assertEquals(PhotoTarget.AttachTo(markId = "a", cpNumber = 42, checkpointId = 3), target)
    }

    @Test
    fun `newest complete take is chosen`() {
        val marks = listOf(
            mark("old", point = 1, number = 11, takenAt = 100_000L),
            mark("new", point = 2, number = 22, takenAt = 140_000L),
        )

        val target = decidePhotoTarget(marks, nowMs = 150_000L)

        assertEquals(PhotoTarget.AttachTo(markId = "new", cpNumber = 22, checkpointId = 2), target)
    }

    @Test
    fun `trustedTakenAt is preferred over wall takenAt`() {
        // Wall time is recent but the trusted time is stale → out of window → ask.
        val marks = listOf(mark("a", takenAt = 149_000L, trustedTakenAt = 100_000L))

        val target = decidePhotoTarget(marks, nowMs = 100_000L + PHOTO_ATTACH_WINDOW_MS + 1)

        assertEquals(PhotoTarget.AskNumber, target)
    }

    @Test
    fun `incomplete takes are ignored`() {
        val marks = listOf(
            mark("partial", point = 2, number = 22, complete = false, takenAt = 145_000L),
            mark("done", point = 1, number = 11, complete = true, takenAt = 120_000L),
        )

        val target = decidePhotoTarget(marks, nowMs = 150_000L)

        assertEquals(PhotoTarget.AttachTo(markId = "done", cpNumber = 11, checkpointId = 1), target)
    }

    @Test
    fun `only incomplete recent takes ask for number`() {
        val marks = listOf(mark("partial", complete = false, takenAt = 149_000L))

        assertEquals(PhotoTarget.AskNumber, decidePhotoTarget(marks, nowMs = 150_000L))
    }
}
