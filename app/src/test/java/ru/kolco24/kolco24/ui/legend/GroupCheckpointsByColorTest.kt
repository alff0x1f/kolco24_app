package ru.kolco24.kolco24.ui.legend

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity

class GroupCheckpointsByColorTest {

    private fun cp(id: Int, color: String): CheckpointEntity =
        CheckpointEntity(id = id, raceId = 1, number = id, cost = id, type = "kp", description = "д", color = color)

    @Test
    fun contiguousSameColor_groupsIntoOneCard() {
        val groups = groupCheckpointsByColor(
            listOf(cp(1, "red"), cp(2, "red"), cp(3, "red")),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2, 3), groups[0].map { it.id })
    }

    @Test
    fun colorChange_startsNewGroup() {
        val groups = groupCheckpointsByColor(
            listOf(cp(1, "red"), cp(2, "red"), cp(3, "blue"), cp(4, "green"), cp(5, "green")),
        )
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4, 5)), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun blankAndUnknownTokens_foldIntoOneNeutralGroup() {
        // "" and an unknown token both parse to null, so they belong to the same neutral card.
        val groups = groupCheckpointsByColor(
            listOf(cp(1, ""), cp(2, "mauve"), cp(3, "")),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2, 3), groups[0].map { it.id })
    }

    @Test
    fun recurringColor_inSeparateRuns_staysTwoCards() {
        // КП are laid out in route order, not sorted by color: a color that comes back later is its
        // own card rather than being merged with the earlier run.
        val groups = groupCheckpointsByColor(
            listOf(cp(1, "red"), cp(2, "blue"), cp(3, "red")),
        )
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun caseAndWhitespace_normalizeBeforeGrouping() {
        // parseCheckpointColor trims + lowercases, so " RED " and "red" are the same group.
        val groups = groupCheckpointsByColor(
            listOf(cp(1, "red"), cp(2, " RED ")),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun emptyInput_yieldsNoGroups() {
        assertEquals(emptyList<List<CheckpointEntity>>(), groupCheckpointsByColor(emptyList()))
    }
}
