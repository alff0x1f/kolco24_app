package ru.kolco24.kolco24.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.track.RawFix

/**
 * Pure-mapper tests for [gpsTimeCandidate] (JVM, no Android) — the trusted-time GPS-anchor filter.
 * Covers the accepted-fix mapping and every rejection reason (mock / non-gps / no time / coarse).
 */
class GpsTimeCandidateTest {

    private fun fix(
        gpsTimeMs: Long = 1_718_900_000_000L,
        elapsedRealtimeNanos: Long = 9_876_543_210_000L,
        accuracy: Float = 12.4f,
        isMock: Boolean = false,
        provider: String? = "gps",
    ) = RawFix(
        lat = 55.751244,
        lon = 37.618423,
        accuracy = accuracy,
        altitude = null,
        verticalAccuracyMeters = null,
        gpsTimeMs = gpsTimeMs,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        isMock = isMock,
        provider = provider,
    )

    @Test
    fun validGpsFix_mapsToCandidate() {
        val c = gpsTimeCandidate(fix())
        assertNotNull(c)
        c!!
        assertEquals(1_718_900_000_000L, c.serverMs)
        // elapsedRealtimeNanos / 1_000_000 (same ms scale as TimeSample.elapsedMs)
        assertEquals(9_876_543L, c.anchorElapsedMs)
        assertEquals(GPS_UNCERTAINTY_MS, c.uncertaintyMs)
    }

    @Test
    fun mockFix_isRejected() {
        assertNull(gpsTimeCandidate(fix(isMock = true)))
    }

    @Test
    fun nonGpsProvider_isRejected() {
        assertNull(gpsTimeCandidate(fix(provider = "network")))
        assertNull(gpsTimeCandidate(fix(provider = "fused")))
        assertNull(gpsTimeCandidate(fix(provider = null)))
    }

    @Test
    fun nonPositiveTime_isRejected() {
        assertNull(gpsTimeCandidate(fix(gpsTimeMs = 0L)))
        assertNull(gpsTimeCandidate(fix(gpsTimeMs = -1L)))
    }

    @Test
    fun coarseAccuracy_isRejected() {
        assertNull(gpsTimeCandidate(fix(accuracy = GPS_TIME_MAX_ACCURACY_METERS + 0.1f)))
    }

    @Test
    fun accuracyAtBoundary_isAccepted() {
        assertNotNull(gpsTimeCandidate(fix(accuracy = GPS_TIME_MAX_ACCURACY_METERS)))
    }
}
