package ru.kolco24.kolco24.ui.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadSummaryTest {

    private fun status(total: Int, cloud: Int, local: Int = 0) =
        TrackUploadStatus(total, local = TargetLine(local, null), cloud = TargetLine(cloud, null))

    @Test
    fun allNull_isEmpty() {
        assertEquals(
            UploadSummary("Пока нечего загружать", UploadSummaryState.Empty),
            uploadSummary(null, null, null, null),
        )
    }

    @Test
    fun allZeroTotals_isEmpty() {
        val zero = status(0, 0)
        assertEquals(UploadSummaryState.Empty, uploadSummary(zero, zero, zero, zero).state)
    }

    @Test
    fun cloudCaughtUp_isAllSent_evenWithoutLan() {
        val s = uploadSummary(status(3, 3, local = 0), status(2, 2), status(500, 500), null)
        assertEquals(UploadSummary("Всё отправлено", UploadSummaryState.AllSent), s)
    }

    @Test
    fun lanOnly_stillPending() {
        val s = uploadSummary(status(3, 0, local = 3), null, null, null)
        assertEquals(UploadSummary("Не отправлено: 3 отметки", UploadSummaryState.Pending), s)
    }

    @Test
    fun mixed_keepsScreenOrder_andTrackHasNoCount() {
        val s = uploadSummary(status(5, 2), status(4, 2), status(1250, 0), status(1, 0))
        assertEquals("Не отправлено: 3 отметки, 2 фото, трек, 1 суд. отметка", s.label)
        assertEquals(UploadSummaryState.Pending, s.state)
    }

    @Test
    fun skipsCaughtUpScopes() {
        val s = uploadSummary(status(5, 5), status(4, 4), status(100, 90), null)
        assertEquals("Не отправлено: трек", s.label)
    }

    @Test
    fun declension() {
        fun label(n: Int) = uploadSummary(status(n, 0), null, null, null).label
        assertEquals("Не отправлено: 1 отметка", label(1))
        assertEquals("Не отправлено: 2 отметки", label(2))
        assertEquals("Не отправлено: 5 отметок", label(5))
        assertEquals("Не отправлено: 11 отметок", label(11))
        assertEquals("Не отправлено: 21 отметка", label(21))
        assertEquals("Не отправлено: 112 отметок", label(112))
    }

    @Test
    fun cloudAboveTotal_neverNegative() {
        val s = uploadSummary(status(2, 5), null, null, null)
        assertEquals(UploadSummaryState.AllSent, s.state)
    }
}
