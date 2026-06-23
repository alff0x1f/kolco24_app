package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPointMappingTest {

    private val fix = RawFix(
        lat = 55.751244,
        lon = 37.618423,
        accuracy = 12.4f,
        gpsTimeMs = 1_718_900_000_000L,
        elapsedRealtimeNanos = 9_876_543_210_000L,
    )

    @Test
    fun elapsedRealtimeAt_isNanosDividedByMillion() {
        val p = fix.toTrackPoint(
            raceId = 7,
            teamId = 42,
            wallMs = 1_718_900_000_100L,
            trustedMs = 1_718_900_000_123L,
            bootCount = 3,
            idFactory = { "id-1" },
        )
        assertEquals(9_876_543L, p.elapsedRealtimeAt)
    }

    @Test
    fun fieldsArePassedThrough() {
        val p = fix.toTrackPoint(
            raceId = 7,
            teamId = 42,
            wallMs = 1_718_900_000_100L,
            trustedMs = 1_718_900_000_123L,
            bootCount = 3,
            idFactory = { "id-1" },
        )
        assertEquals("id-1", p.id)
        assertEquals(7, p.raceId)
        assertEquals(42, p.teamId)
        assertEquals(55.751244, p.lat, 0.0)
        assertEquals(37.618423, p.lon, 0.0)
        assertEquals(12.4f, p.accuracy, 0f)
        assertEquals(1_718_900_000_000L, p.gpsTimeMs)
        assertEquals(1_718_900_000_100L, p.wallMs)
        assertEquals(3, p.bootCount)
        assertFalse(p.uploadedLocal)
        assertFalse(p.uploadedCloud)
    }

    @Test
    fun trustedMs_comesFromInjectedValue() {
        val p = fix.toTrackPoint(
            raceId = 1,
            teamId = 1,
            wallMs = 1_000L,
            trustedMs = 1_718_900_000_123L,
            bootCount = null,
            idFactory = { "id" },
        )
        assertEquals(1_718_900_000_123L, p.trustedMs)
    }

    @Test
    fun trustedMs_nullWhenNoClockSync() {
        val p = fix.toTrackPoint(
            raceId = 1,
            teamId = 1,
            wallMs = 1_000L,
            trustedMs = null,
            bootCount = null,
            idFactory = { "id" },
        )
        assertNull(p.trustedMs)
    }

    @Test
    fun idFactoryIsInvokedPerMapping() {
        var counter = 0
        val factory = { "id-${counter++}" }
        val a = fix.toTrackPoint(1, 1, 0L, null, null, factory)
        val b = fix.toTrackPoint(1, 1, 0L, null, null, factory)
        assertEquals("id-0", a.id)
        assertEquals("id-1", b.id)
    }
}
