package ru.kolco24.kolco24.ui.map

import ru.kolco24.kolco24.data.map.MapDownloadState

/** What the Map tab can show for the current race (drives the overlay card / banner). */
sealed interface MapAvailability {
    /** The race has no `map_url` — online OSM base only, with a banner. */
    data object NoMapForRace : MapAvailability

    /** A map exists on the server but not on disk — «Скачать карту гонки» CTA. */
    data object NotDownloaded : MapAvailability

    /** Another race's map is downloading (one download app-wide) — CTA disabled. */
    data object BusyOtherRace : MapAvailability

    /** This race's map is downloading; [progress] in `0f..1f`, `null` = indeterminate. */
    data class Downloading(val progress: Float?) : MapAvailability

    /** The MBTiles file is on disk — offline base, no overlay. */
    data object Ready : MapAvailability
}

/**
 * Availability of the offline map for [raceId]. **The file wins over [mapUrl]** (unlike iOS): local
 * mode rewrites the races catalog with LAN rows that may lack `map_url`, and an already-downloaded
 * map must not disappear because of that.
 *
 * Order: file on disk → own download → no `mapUrl` → another race downloading → not downloaded.
 * A `Failed` state is transient (snackbar, then consumed) and maps to the same as `Idle`.
 */
fun mapAvailability(
    raceId: Int,
    mapUrl: String?,
    downloaded: Set<Int>,
    downloadState: MapDownloadState,
): MapAvailability = when {
    raceId in downloaded -> MapAvailability.Ready
    downloadState is MapDownloadState.Downloading && downloadState.raceId == raceId ->
        MapAvailability.Downloading(downloadState.progress)
    mapUrl.isNullOrBlank() -> MapAvailability.NoMapForRace
    downloadState is MapDownloadState.Downloading -> MapAvailability.BusyOtherRace
    else -> MapAvailability.NotDownloaded
}
