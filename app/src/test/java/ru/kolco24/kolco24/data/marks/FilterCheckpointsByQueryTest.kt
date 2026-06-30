package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity

class FilterCheckpointsByQueryTest {

    private fun cp(
        id: Int,
        number: Int,
        description: String? = "desc",
        locked: Boolean = false,
    ) = CheckpointEntity(
        id = id,
        raceId = 1,
        number = number,
        cost = if (locked) null else 5,
        type = "kp",
        description = description,
        locked = locked,
    )

    private val legend = listOf(
        cp(id = 10, number = 1, description = "Развилка у озера"),
        cp(id = 20, number = 12, description = "Мост"),
        cp(id = 30, number = 23, description = null, locked = true),
    )

    @Test
    fun `blank query returns whole legend in order`() {
        assertEquals(legend, filterCheckpointsByQuery(legend, ""))
        assertEquals(legend, filterCheckpointsByQuery(legend, "   "))
    }

    @Test
    fun `number substring matches`() {
        // "2" appears in 12 and 23, not in 1.
        assertEquals(listOf(legend[1], legend[2]), filterCheckpointsByQuery(legend, "2"))
    }

    @Test
    fun `query 1 is a substring match hitting two checkpoints`() {
        // "1" is a substring of both 1 and 12 — this tests that the result size is correct, not just
        // that one desired item is present (a broken impl returning all 3 would still pass a find-based check).
        val result = filterCheckpointsByQuery(legend, "1")
        assertEquals(2, result.size)
        assertEquals(listOf(legend[0], legend[1]), result)
    }

    @Test
    fun `query 12 matches only checkpoint 12`() {
        assertEquals(listOf(legend[1]), filterCheckpointsByQuery(legend, "12"))
    }

    @Test
    fun `description matches case-insensitively`() {
        assertEquals(listOf(legend[1]), filterCheckpointsByQuery(legend, "мост"))
    }

    @Test
    fun `locked checkpoint without description still matches on number`() {
        assertEquals(listOf(legend[2]), filterCheckpointsByQuery(legend, "23"))
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(emptyList<CheckpointEntity>(), filterCheckpointsByQuery(legend, "999"))
    }
}
