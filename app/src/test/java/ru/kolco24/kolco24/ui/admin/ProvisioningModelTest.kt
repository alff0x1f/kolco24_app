package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.ui.legend.CheckpointColor

class ProvisioningModelTest {

    private fun cp(id: Int, number: Int, color: String = ""): CheckpointEntity =
        CheckpointEntity(
            id = id,
            raceId = 1,
            number = number,
            cost = 5,
            type = "kp",
            description = null,
            color = color,
        )

    @Test
    fun railTicks_empty_noChips_allHollowNoneCurrent() {
        val cps = listOf(cp(10, 1), cp(20, 2))
        val ticks = railTicks(cps, emptyMap(), currentIndex = -1)
        assertEquals(2, ticks.size)
        assertTrue(ticks.none { it.filled })
        assertTrue(ticks.none { it.current })
    }

    @Test
    fun railTicks_filled_reflectsBoundCounts() {
        val cps = listOf(cp(10, 1), cp(20, 2), cp(30, 3))
        val counts = mapOf(10 to 2, 30 to 0)
        val ticks = railTicks(cps, counts, currentIndex = 0)
        assertTrue(ticks[0].filled) // count 2 > 0
        assertFalse(ticks[1].filled) // missing → 0
        assertFalse(ticks[2].filled) // count 0
    }

    @Test
    fun railTicks_current_marksTheSettledIndexOnly() {
        val cps = listOf(cp(10, 1), cp(20, 2), cp(30, 3))
        val ticks = railTicks(cps, emptyMap(), currentIndex = 1)
        assertFalse(ticks[0].current)
        assertTrue(ticks[1].current)
        assertFalse(ticks[2].current)
    }

    @Test
    fun railTicks_color_parsedFromToken() {
        val cps = listOf(cp(10, 1, color = "red"), cp(20, 2, color = ""), cp(30, 3, color = "teal"))
        val ticks = railTicks(cps, emptyMap(), currentIndex = 0)
        assertEquals(CheckpointColor.RED, ticks[0].color)
        assertEquals(null, ticks[1].color) // empty → null
        assertEquals(null, ticks[2].color) // unknown → null
    }

    @Test
    fun provisionErrorMessage_conflict_isGeneric() {
        assertEquals(
            "Этот тег уже привязан к другому КП",
            provisionErrorMessage(PostResult.Conflict),
        )
    }

    @Test
    fun provisionErrorMessage_forbidden_combinesAdminAndSigning() {
        assertEquals(
            "Нет прав администратора этой гонки или ошибка подписи/часов",
            provisionErrorMessage(PostResult.Forbidden),
        )
    }

    @Test
    fun provisionErrorMessage_notFound_isCheckpointMissing() {
        assertEquals("КП не найдено", provisionErrorMessage(PostResult.Error(404)))
    }

    @Test
    fun provisionErrorMessage_eachStatus() {
        assertEquals("Сессия истекла, войдите снова", provisionErrorMessage(PostResult.Unauthorized))
        assertEquals("Неверный запрос", provisionErrorMessage(PostResult.BadRequest))
        assertEquals("Слишком часто, подождите немного", provisionErrorMessage(PostResult.RateLimited))
        assertEquals("Нет сети, попробуйте снова", provisionErrorMessage(PostResult.Offline))
        assertEquals("Ошибка сервера", provisionErrorMessage(PostResult.Error(500)))
        assertEquals("Ошибка сервера", provisionErrorMessage(PostResult.Error(null)))
    }

    @Test
    fun chipTokenLabel_returnsUidTail() {
        assertEquals("ABCD", chipTokenLabel("0411223344ABCD"))
    }

    @Test
    fun chipTokenLabel_shortUid_returnedWhole() {
        assertEquals("AB", chipTokenLabel("AB"))
        assertEquals("ABCD", chipTokenLabel("ABCD"))
    }

    @Test
    fun chipTokenLabel_fiveChars_truncatesToLastFour() {
        // length == 5 is the first case where takeLast(4) differs from the uid itself.
        assertEquals("BCDE", chipTokenLabel("ABCDE"))
    }
}
