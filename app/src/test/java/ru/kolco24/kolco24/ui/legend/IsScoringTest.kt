package ru.kolco24.kolco24.ui.legend

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity

class IsScoringTest {

    private fun cp(cost: Int?, locked: Boolean): CheckpointEntity =
        CheckpointEntity(id = 1, raceId = 1, number = 1, cost = cost, type = "kp", description = "д", locked = locked)

    @Test
    fun lockedWithUnknownCost_countsAsScoring() {
        assertEquals(true, cp(cost = null, locked = true).isScoring())
    }

    @Test
    fun lockedWithZeroCost_countsAsScoring() {
        // A locked CP's real cost is hidden client-side, so it's assumed scoring until reveal.
        assertEquals(true, cp(cost = 0, locked = true).isScoring())
    }

    @Test
    fun openWithZeroCost_isTechnical_notScoring() {
        assertEquals(false, cp(cost = 0, locked = false).isScoring())
    }

    @Test
    fun openWithNullCost_notScoring() {
        assertEquals(false, cp(cost = null, locked = false).isScoring())
    }

    @Test
    fun openWithPositiveCost_isScoring() {
        assertEquals(true, cp(cost = 5, locked = false).isScoring())
    }
}
