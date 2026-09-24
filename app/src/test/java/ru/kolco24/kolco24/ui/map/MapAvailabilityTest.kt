package ru.kolco24.kolco24.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.map.MapDownloadState

class MapAvailabilityTest {

    private val url = "https://example.org/1.mbtiles"

    @Test
    fun fileOnDiskWithoutMapUrlIsReady() {
        assertEquals(MapAvailability.Ready, mapAvailability(1, null, setOf(1), MapDownloadState.Idle))
    }

    @Test
    fun fileOnDiskWinsOverOtherRaceDownload() {
        assertEquals(
            MapAvailability.Ready,
            mapAvailability(1, url, setOf(1), MapDownloadState.Downloading(2, 0.5f)),
        )
    }

    @Test
    fun ownDownloadIsDownloadingWithProgress() {
        assertEquals(
            MapAvailability.Downloading(0.25f),
            mapAvailability(1, url, emptySet(), MapDownloadState.Downloading(1, 0.25f)),
        )
        assertEquals(
            MapAvailability.Downloading(null),
            mapAvailability(1, url, emptySet(), MapDownloadState.Downloading(1, null)),
        )
    }

    @Test
    fun otherRaceDownloadWithMapUrlIsBusy() {
        assertEquals(
            MapAvailability.BusyOtherRace,
            mapAvailability(1, url, emptySet(), MapDownloadState.Downloading(2, 0.1f)),
        )
    }

    @Test
    fun otherRaceDownloadWithoutMapUrlIsNoMap() {
        assertEquals(
            MapAvailability.NoMapForRace,
            mapAvailability(1, null, emptySet(), MapDownloadState.Downloading(2, 0.1f)),
        )
    }

    @Test
    fun noMapUrlIsNoMap() {
        assertEquals(MapAvailability.NoMapForRace, mapAvailability(1, null, emptySet(), MapDownloadState.Idle))
        assertEquals(MapAvailability.NoMapForRace, mapAvailability(1, "  ", emptySet(), MapDownloadState.Idle))
    }

    @Test
    fun otherwiseNotDownloaded() {
        assertEquals(MapAvailability.NotDownloaded, mapAvailability(1, url, emptySet(), MapDownloadState.Idle))
        assertEquals(MapAvailability.NotDownloaded, mapAvailability(1, url, setOf(2), MapDownloadState.Idle))
        assertEquals(
            MapAvailability.NotDownloaded,
            mapAvailability(1, url, emptySet(), MapDownloadState.Failed(1, "x")),
        )
    }
}
