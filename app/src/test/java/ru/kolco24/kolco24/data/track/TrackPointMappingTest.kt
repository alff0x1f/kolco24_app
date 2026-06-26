package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointMappingTest {

    private val fix = RawFix(
        lat = 55.751244,
        lon = 37.618423,
        accuracy = 12.4f,
        altitude = 187.5,
        verticalAccuracyMeters = 3.2f,
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
            segmentId = "seg-1",
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
            segmentId = "seg-1",
            idFactory = { "id-1" },
        )
        assertEquals("id-1", p.id)
        assertEquals(7, p.raceId)
        assertEquals(42, p.teamId)
        assertEquals(55.751244, p.lat, 0.0)
        assertEquals(37.618423, p.lon, 0.0)
        assertEquals(12.4f, p.accuracy, 0f)
        assertEquals(187.5, p.altitude!!, 0.0)
        assertEquals(3.2f, p.verticalAccuracyMeters!!, 0f)
        assertEquals(1_718_900_000_000L, p.gpsTimeMs)
        assertEquals(1_718_900_000_100L, p.wallMs)
        assertEquals(3, p.bootCount)
        assertEquals("seg-1", p.segmentId)
        assertFalse(p.uploadedLocal)
        assertFalse(p.uploadedCloud)
    }

    @Test
    fun segmentId_comesFromInjectedValue() {
        val p = fix.toTrackPoint(
            raceId = 1,
            teamId = 1,
            wallMs = 1_000L,
            trustedMs = null,
            bootCount = null,
            segmentId = "session-abc",
            idFactory = { "id" },
        )
        assertEquals("session-abc", p.segmentId)
    }

    @Test
    fun altitudeFields_nullWhenFixHasNoVerticalComponent() {
        val flat = fix.copy(altitude = null, verticalAccuracyMeters = null)
        val p = flat.toTrackPoint(
            raceId = 1,
            teamId = 1,
            wallMs = 1_000L,
            trustedMs = null,
            bootCount = null,
            segmentId = "seg",
            idFactory = { "id" },
        )
        assertNull(p.altitude)
        assertNull(p.verticalAccuracyMeters)
    }

    @Test
    fun trustedMs_comesFromInjectedValue() {
        val p = fix.toTrackPoint(
            raceId = 1,
            teamId = 1,
            wallMs = 1_000L,
            trustedMs = 1_718_900_000_123L,
            bootCount = null,
            segmentId = "seg",
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
            segmentId = "seg",
            idFactory = { "id" },
        )
        assertNull(p.trustedMs)
    }

    @Test
    fun filterPoints_keepsFixesMeetingThreshold_dropsCoarser() {
        val fine = fix.copy(accuracy = 10f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val atLimit = fix.copy(accuracy = 50f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val coarse = fix.copy(accuracy = 51f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val result = filterPoints(listOf(fine, atLimit, coarse))
        assertEquals(listOf(10f, 50f), result.map { it.accuracy })
    }

    @Test
    fun filterPoints_customThreshold_dropsAboveThreshold() {
        val fine = fix.copy(accuracy = 10f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val medium = fix.copy(accuracy = 30f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val coarse = fix.copy(accuracy = 50f).toTrackPoint(1, 1, 0L, null, null, "seg") { "id" }
        val result = filterPoints(listOf(fine, medium, coarse), maxAccuracyMeters = 20f)
        assertEquals(1, result.size)
        assertEquals(10f, result.single().accuracy, 0f)
    }

    @Test
    fun filterPoints_emptyList_returnsEmpty() {
        assertTrue(filterPoints(emptyList<ru.kolco24.kolco24.data.db.TrackPointEntity>()).isEmpty())
    }

    @Test
    fun idFactoryIsInvokedPerMapping() {
        var counter = 0
        val factory = { "id-${counter++}" }
        val a = fix.toTrackPoint(1, 1, 0L, null, null, "seg", factory)
        val b = fix.toTrackPoint(1, 1, 0L, null, null, "seg", factory)
        assertEquals("id-0", a.id)
        assertEquals("id-1", b.id)
    }
}
