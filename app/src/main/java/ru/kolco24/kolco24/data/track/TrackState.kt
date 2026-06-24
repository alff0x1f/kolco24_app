package ru.kolco24.kolco24.data.track

/**
 * The GPS-track recording state, owned by `AppContainer.trackRecordingState` (written by
 * `TrackRecordingService`, read by the UI). [Idle] is the resting state; [Recording] carries the team
 * being recorded plus the running point count so the card can show «N точек» without a separate query.
 */
sealed interface TrackState {
    data object Idle : TrackState

    data class Recording(val teamId: Int, val pointCount: Int) : TrackState
}
