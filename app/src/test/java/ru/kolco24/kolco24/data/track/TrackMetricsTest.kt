package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetricsTest {

    private data class Pt(
        override val lat: Double,
        override val lon: Double,
        override val accuracy: Float,
        override val elapsedRealtimeAt: Long,
    ) : TrackPointLike

    @Test
    fun length_emptyList_isZero() {
        assertEquals(0.0, trackLengthMeters(emptyList()), 0.0)
    }

    @Test
    fun length_singlePoint_isZero() {
        val pts = listOf(Pt(55.75, 37.61, 5f, 1000))
        assertEquals(0.0, trackLengthMeters(pts), 0.0)
    }

    @Test
    fun length_oneThousandthDegreeLat_isAboutOneEleven_meters() {
        // 0.001° of latitude ≈ EARTH_RADIUS_M * π/180 * 0.001 ≈ 111.19 m.
        val pts = listOf(
            Pt(55.000, 37.000, 5f, 1000),
            Pt(55.001, 37.000, 5f, 2000),
        )
        assertEquals(111.19, trackLengthMeters(pts), 0.2)
    }

    @Test
    fun length_sumsConsecutiveSegments() {
        val pts = listOf(
            Pt(55.000, 37.000, 5f, 1000),
            Pt(55.001, 37.000, 5f, 2000),
            Pt(55.002, 37.000, 5f, 3000),
        )
        // Two equal ~111.19 m segments.
        assertEquals(222.39, trackLengthMeters(pts), 0.4)
    }

    @Test
    fun length_ordersByElapsedRealtimeAt_notInputOrder() {
        // Same three points, shuffled: result must match the sorted order.
        val ordered = listOf(
            Pt(55.000, 37.000, 5f, 1000),
            Pt(55.001, 37.000, 5f, 2000),
            Pt(55.002, 37.000, 5f, 3000),
        )
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        assertEquals(trackLengthMeters(ordered), trackLengthMeters(shuffled), 1e-9)
    }

    @Test
    fun filter_dropsCoarseFixesAbove50m() {
        val pts = listOf(
            Pt(55.0, 37.0, 12f, 1000),
            Pt(55.0, 37.0, 50f, 2000),
            Pt(55.0, 37.0, 51f, 3000),
            Pt(55.0, 37.0, 200f, 4000),
        )
        val kept = filterPoints(pts)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.accuracy <= 50f })
    }

    @Test
    fun filter_customThreshold() {
        val pts = listOf(
            Pt(55.0, 37.0, 12f, 1000),
            Pt(55.0, 37.0, 20f, 2000),
        )
        assertEquals(1, filterPoints(pts, maxAccuracyMeters = 15f).size)
    }

    @Test
    fun haversine_zeroDistanceForSamePoint() {
        assertEquals(0.0, haversineMeters(55.75, 37.61, 55.75, 37.61), 1e-9)
    }
}
