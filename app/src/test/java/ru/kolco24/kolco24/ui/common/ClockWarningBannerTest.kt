package ru.kolco24.kolco24.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClockWarningBannerTest {

    @Test
    fun roundsHalfUp() {
        // 150_000 ms = exactly 2.5 min → rounds up to 3 (half-up semantics).
        // 149_999 ms = 2.4999... min → rounds down to 2.
        assertEquals("3 мин", formatSkewMinutes(150_000))
        assertEquals("2 мин", formatSkewMinutes(149_999))
    }

    @Test
    fun bothSignsCollapseToMagnitude() {
        // A slow clock yields a negative skew — never «−2 мин».
        assertEquals("2 мин", formatSkewMinutes(90_000))
        assertEquals("2 мин", formatSkewMinutes(-90_000))
    }

    @Test
    fun justOverThresholdRoundsToOne() {
        assertEquals("1 мин", formatSkewMinutes(60_001))
        assertEquals("1 мин", formatSkewMinutes(-60_001))
    }

    @Test
    fun roundsTowardNearest() {
        // 119_000 ms = 1.983 min → 2.
        assertEquals("2 мин", formatSkewMinutes(119_000))
    }

    @Test
    fun longMinValueDoesNotTrapAndStaysPositive() {
        val result = formatSkewMinutes(Long.MIN_VALUE)
        assertFalse("must not be negative", result.startsWith("-"))
        assertFalse("must not be «−»", result.startsWith("−"))
    }
}
