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
        val expected = mapOf(
            1 to "1 отметка", 2 to "2 отметки", 4 to "4 отметки", 5 to "5 отметок",
            10 to "10 отметок", 11 to "11 отметок", 14 to "14 отметок", 19 to "19 отметок",
            20 to "20 отметок", 21 to "21 отметка", 22 to "22 отметки", 101 to "101 отметка",
            111 to "111 отметок", 112 to "112 отметок", 114 to "114 отметок", 121 to "121 отметка",
        )
        expected.forEach { (n, words) -> assertEquals("Не отправлено: $words", label(n)) }
    }

    @Test
    fun judgeDeclension() {
        fun label(n: Int) = uploadSummary(null, null, null, status(n, 0)).label
        assertEquals("Не отправлено: 1 суд. отметка", label(1))
        assertEquals("Не отправлено: 3 суд. отметки", label(3))
        assertEquals("Не отправлено: 12 суд. отметок", label(12))
    }

    @Test
    fun judgeOnly_pending() {
        val s = uploadSummary(null, null, null, status(4, 1))
        assertEquals(UploadSummary("Не отправлено: 3 суд. отметки", UploadSummaryState.Pending), s)
    }

    @Test
    fun photoOnly_pending() {
        val s = uploadSummary(status(2, 2), status(6, 1), null, null)
        assertEquals(UploadSummary("Не отправлено: 5 фото", UploadSummaryState.Pending), s)
    }

    @Test
    fun notReady_isLoading_evenWhenLoadedScopesAreSent() {
        // Team switch within a race: judge counter already loaded and fully sent, team counters not yet.
        val s = uploadSummary(null, null, null, status(3, 3), ready = false)
        assertEquals(UploadSummary("Проверяем…", UploadSummaryState.Loading), s)
    }

    @Test
    fun notReady_allNull_isLoading_notEmpty() {
        assertEquals(UploadSummaryState.Loading, uploadSummary(null, null, null, null, ready = false).state)
    }

    @Test
    fun readyAfterLoading_revealsPending() {
        val s = uploadSummary(status(2, 0), null, null, status(3, 3), ready = true)
        assertEquals(UploadSummary("Не отправлено: 2 отметки", UploadSummaryState.Pending), s)
    }

    @Test
    fun cloudAboveTotal_neverNegative() {
        val s = uploadSummary(status(2, 5), null, null, null)
        assertEquals(UploadSummaryState.AllSent, s.state)
    }
}
