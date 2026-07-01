package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity

class ResolvePhotoCheckpointTest {

    private fun cp(
        id: Int,
        number: Int,
        cost: Int? = 5,
        locked: Boolean = false,
    ) = CheckpointEntity(
        id = id,
        raceId = 1,
        number = number,
        cost = cost,
        type = "kp",
        description = if (locked) null else "desc",
        locked = locked,
    )

    private val legend = listOf(
        cp(id = 10, number = 1),
        cp(id = 20, number = 2),
        cp(id = 30, number = 3, cost = null, locked = true),
    )

    @Test
    fun `known number resolves to its checkpoint`() {
        assertEquals(legend[1], resolvePhotoCheckpoint(2, legend))
    }

    @Test
    fun `unknown number resolves to null`() {
        assertNull(resolvePhotoCheckpoint(99, legend))
    }

    @Test
    fun `empty legend resolves to null`() {
        assertNull(resolvePhotoCheckpoint(1, emptyList()))
    }

    @Test
    fun `locked checkpoint still resolves (метку сорвали scenario)`() {
        val resolved = resolvePhotoCheckpoint(3, legend)

        assertEquals(legend[2], resolved)
        assertNull(resolved?.cost)
    }
}
