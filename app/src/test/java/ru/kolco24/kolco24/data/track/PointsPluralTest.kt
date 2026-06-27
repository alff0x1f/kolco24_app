package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Test

class PointsPluralTest {

    @Test
    fun word_lastDigitOne_isTochka() {
        assertEquals("точка", pointsWord(1))
        assertEquals("точка", pointsWord(21))
        assertEquals("точка", pointsWord(41))
        assertEquals("точка", pointsWord(101))
    }

    @Test
    fun word_lastDigitTwoToFour_isTochki() {
        assertEquals("точки", pointsWord(2))
        assertEquals("точки", pointsWord(3))
        assertEquals("точки", pointsWord(4))
        assertEquals("точки", pointsWord(22))
        assertEquals("точки", pointsWord(44))
    }

    @Test
    fun word_zeroAndFiveToTwenty_isTochek() {
        assertEquals("точек", pointsWord(0))
        assertEquals("точек", pointsWord(5))
        assertEquals("точек", pointsWord(20))
        assertEquals("точек", pointsWord(100))
    }

    @Test
    fun word_teens_areTochek() {
        assertEquals("точек", pointsWord(11))
        assertEquals("точек", pointsWord(12))
        assertEquals("точек", pointsWord(13))
        assertEquals("точек", pointsWord(14))
        assertEquals("точек", pointsWord(111))
        assertEquals("точек", pointsWord(112))
    }

    @Test
    fun word_negative_usesMagnitude() {
        assertEquals("точка", pointsWord(-1))
        assertEquals("точек", pointsWord(-11))
    }

    @Test
    fun label_joinsCountAndWord() {
        assertEquals("1 точка", pointsLabel(1))
        assertEquals("2 точки", pointsLabel(2))
        assertEquals("41 точка", pointsLabel(41))
        assertEquals("82 точки", pointsLabel(82))
        assertEquals("0 точек", pointsLabel(0))
    }

    @Test
    fun segmentsWord_declinesByCount() {
        assertEquals("сегмент", segmentsWord(1))
        assertEquals("сегмент", segmentsWord(21))
        assertEquals("сегмента", segmentsWord(2))
        assertEquals("сегмента", segmentsWord(3))
        assertEquals("сегмента", segmentsWord(4))
        assertEquals("сегментов", segmentsWord(0))
        assertEquals("сегментов", segmentsWord(5))
        assertEquals("сегментов", segmentsWord(11))
        assertEquals("сегментов", segmentsWord(13))
    }

    @Test
    fun relativeTime_underMinute_isJustNow() {
        assertEquals("только что", relativeTimeRu(0L, 0L))
        assertEquals("только что", relativeTimeRu(0L, 59_000L))
        assertEquals("только что", relativeTimeRu(0L, 59_999L))
    }

    @Test
    fun relativeTime_minutes() {
        assertEquals("1 мин назад", relativeTimeRu(0L, 60_000L))
        assertEquals("2 мин назад", relativeTimeRu(0L, 120_000L))
        assertEquals("59 мин назад", relativeTimeRu(0L, 59L * 60_000L))
    }

    @Test
    fun relativeTime_hours() {
        assertEquals("1 ч назад", relativeTimeRu(0L, 3_600_000L))
        assertEquals("2 ч назад", relativeTimeRu(0L, 2L * 3_600_000L))
    }

    @Test
    fun relativeTime_negativeDelta_isJustNow() {
        assertEquals("только что", relativeTimeRu(120_000L, 0L))
    }

    @Test
    fun relativeTime_boundariesRollOver() {
        // 59 999 ms still under a minute, 60 000 ms rolls to «1 мин назад»
        assertEquals("только что", relativeTimeRu(0L, 59_999L))
        assertEquals("1 мин назад", relativeTimeRu(0L, 60_000L))
        // one ms under an hour is still minutes; exactly an hour rolls to «1 ч назад»
        assertEquals("59 мин назад", relativeTimeRu(0L, 3_599_999L))
        assertEquals("1 ч назад", relativeTimeRu(0L, 3_600_000L))
    }
}
