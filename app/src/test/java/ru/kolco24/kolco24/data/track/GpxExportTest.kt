package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.TrackPointEntity

class GpxExportTest {

    private fun point(
        id: String,
        lat: Double,
        lon: Double,
        segmentId: String,
        altitude: Double? = null,
        trustedMs: Long? = null,
        wallMs: Long = 1_718_900_000_000L,
        elapsedRealtimeAt: Long = 0L,
        bootCount: Int? = null,
    ) = TrackPointEntity(
        id = id,
        raceId = 7,
        teamId = 42,
        lat = lat,
        lon = lon,
        accuracy = 8f,
        altitude = altitude,
        verticalAccuracyMeters = null,
        gpsTimeMs = 0L,
        elapsedRealtimeAt = elapsedRealtimeAt,
        bootCount = bootCount,
        wallMs = wallMs,
        trustedMs = trustedMs,
        segmentId = segmentId,
    )

    @Test
    fun emptyList_producesValidEmptyTrack() {
        val gpx = buildGpx(emptyList(), "Команда")
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("</trk>"))
        assertFalse(gpx.contains("<trkpt"))
        assertFalse(gpx.contains("<trkseg>"))
    }

    @Test
    fun distinctSegmentIds_produceSeparateTrksegs() {
        val gpx = buildGpx(
            listOf(
                point("a", 55.0, 37.0, segmentId = "s1"),
                point("b", 55.1, 37.1, segmentId = "s1"),
                point("c", 55.2, 37.2, segmentId = "s2"),
            ),
            "T",
        )
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(2, Regex("</trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<trkpt").findAll(gpx).count())
    }

    @Test
    fun callerSideRebootSafeSorting_preventsAlternatingOnePointSegments() {
        val points = sortedTrackPoints(
            listOf(
                point("old-1", 55.0, 37.0, segmentId = "old", wallMs = 1_000L, elapsedRealtimeAt = 100_000L, bootCount = 7),
                point("new-1", 56.0, 38.0, segmentId = "new", wallMs = 10_000L, elapsedRealtimeAt = 101_000L, bootCount = 8),
                point("old-2", 55.1, 37.1, segmentId = "old", wallMs = 2_000L, elapsedRealtimeAt = 102_000L, bootCount = 7),
                point("new-2", 56.1, 38.1, segmentId = "new", wallMs = 11_000L, elapsedRealtimeAt = 103_000L, bootCount = 8),
            ),
        )

        val gpx = buildGpx(points, "T")

        assertEquals(listOf("old-1", "old-2", "new-1", "new-2"), points.map { it.id })
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(4, Regex("<trkpt").findAll(gpx).count())
    }

    @Test
    fun altitude_omittedWhenNull_presentWhenSet() {
        val gpx = buildGpx(
            listOf(
                point("a", 55.0, 37.0, segmentId = "s", altitude = null),
                point("b", 55.1, 37.1, segmentId = "s", altitude = 187.5),
            ),
            "T",
        )
        assertEquals(1, Regex("<ele>").findAll(gpx).count())
        assertTrue(gpx.contains("<ele>187.500000</ele>"))
    }

    @Test
    fun time_usesTrustedThenWall_inUtcIso() {
        // 2024-06-20T18:53:20Z = 1_718_909_600_000 ms.
        val gpx = buildGpx(
            listOf(
                point("a", 55.0, 37.0, segmentId = "s", trustedMs = 1_718_909_600_000L, wallMs = 0L),
                point("b", 55.1, 37.1, segmentId = "s", trustedMs = null, wallMs = 1_718_909_600_000L),
            ),
            "T",
        )
        assertEquals(2, Regex("<time>2024-06-20T18:53:20Z</time>").findAll(gpx).count())
    }

    @Test
    fun coordinates_useDotDecimalSeparator() {
        val gpx = buildGpx(listOf(point("a", 55.751244, 37.618423, segmentId = "s")), "T")
        assertTrue(gpx.contains("lat=\"55.751244\""))
        assertTrue(gpx.contains("lon=\"37.618423\""))
    }

    @Test
    fun trackName_isXmlEscaped() {
        val gpx = buildGpx(emptyList(), "A & B <test>")
        assertTrue(gpx.contains("<name>A &amp; B &lt;test&gt;</name>"))
    }

    @Test
    fun fileName_sanitizesAndStamps() {
        assertEquals("kolco24-148-2026-06-26.gpx", gpxFileName("148", "2026-06-26"))
        assertEquals("kolco24-team_7-2026-06-26.gpx", gpxFileName("team 7", "2026-06-26"))
        assertEquals("kolco24-track-2026-06-26.gpx", gpxFileName("", "2026-06-26"))
    }
}
