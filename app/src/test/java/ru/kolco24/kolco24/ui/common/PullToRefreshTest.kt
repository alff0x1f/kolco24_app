package ru.kolco24.kolco24.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.RefreshResult

class PullToRefreshTest {
    @Test
    fun `success outcomes are silent`() {
        assertNull(refreshErrorMessage(RefreshResult.Updated))
        assertNull(refreshErrorMessage(RefreshResult.NotModified))
    }

    @Test
    fun `offline maps to no-network message`() {
        assertEquals("Нет сети — не удалось обновить", refreshErrorMessage(RefreshResult.Offline))
    }

    @Test
    fun `forbidden maps to access-denied message`() {
        assertEquals("Доступ запрещён", refreshErrorMessage(RefreshResult.Forbidden))
    }

    @Test
    fun `http error includes the status code`() {
        assertEquals("Ошибка сервера (500)", refreshErrorMessage(RefreshResult.HttpError(500)))
    }
}
