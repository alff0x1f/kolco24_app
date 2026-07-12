package ru.kolco24.kolco24.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.track.RawFix

/**
 * Pure-mapper tests for [gpsTimeCandidate] (JVM, no Android) — the trusted-time GPS-anchor filter.
 * Covers the accepted-fix mapping and every rejection reason (mock / non-gps / no time / coarse /
 * fused-without-altitude — the anti-spoofing GNSS corroboration).
 */
class GpsTimeCandidateTest {

    private fun fix(
        gpsTimeMs: Long = 1_718_900_000_000L,
        elapsedRealtimeNanos: Long = 9_876_543_210_000L,
        accuracy: Float = 12.4f,
        altitude: Double? = null,
        isMock: Boolean = false,
        provider: String? = "gps",
    ) = RawFix(
        lat = 55.751244,
        lon = 37.618423,
        accuracy = accuracy,
        altitude = altitude,
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
        assertNull(gpsTimeCandidate(fix(provider = "passive")))
        assertNull(gpsTimeCandidate(fix(provider = null)))
    }

    @Test
    fun fusedProvider_withAltitude_isAccepted() {
        // "fused" is what FusedLocationProviderClient stamps on every fix on a GMS device (the majority
        // configuration + both anchor power points). A genuine satellite-backed fused fix is 3-D and
        // reports altitude, so its time is real GNSS time — accepted.
        assertNotNull(gpsTimeCandidate(fix(provider = "fused", altitude = 154.2)))
    }

    @Test
    fun fusedProvider_withoutAltitude_isRejected() {
        // The anti-spoofing guard: a WiFi/cell-blended fused fix is 2-D (no altitude) and its time is
        // just the device wall-clock (spoofable via Android Settings with no mock-location app, so
        // isMock stays false). It can pass the coarse accuracy gate (WiFi ~20–60 m), so altitude
        // presence is what stops it from anchoring TrustedClock to a forged time.
        assertNull(gpsTimeCandidate(fix(provider = "fused", altitude = null)))
        // A spoofed-clock WiFi fix at realistic accuracy is still rejected for the same reason.
        assertNull(gpsTimeCandidate(fix(provider = "fused", altitude = null, accuracy = 35f)))
    }

    @Test
    fun gpsProvider_withoutAltitude_isAccepted() {
        // The explicit "gps" provider is an OS guarantee of a satellite source, so a 2-D gps fix
        // (no altitude) is still trusted by provider name — the altitude requirement is fused-only.
        assertNotNull(gpsTimeCandidate(fix(provider = "gps", altitude = null)))
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
