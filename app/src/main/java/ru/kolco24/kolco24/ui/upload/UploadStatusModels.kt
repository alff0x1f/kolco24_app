package ru.kolco24.kolco24.ui.upload

import ru.kolco24.kolco24.data.track.TargetUploadOutcome

/**
 * One upload target's progress for the status row: how many of [total] points are uploaded to this
 * target and its last flush [outcome] (`null` until a flush has reported, or after a destructive
 * clear). UI-composition model, declared in a neutral package shared by the track/marks/photo status
 * consumers (never coupled back to any one of them).
 */
data class TargetLine(val uploaded: Int, val outcome: TargetUploadOutcome?)

/**
 * The adaptive upload-status view-model the host derives by joining the durable per-target counts
 * with the transient in-memory outcomes for the selected scope. [fullyUploaded] is the calm-state
 * gate: everything counted and both targets caught up.
 */
data class TrackUploadStatus(val total: Int, val local: TargetLine, val cloud: TargetLine) {
    val fullyUploaded: Boolean get() = total > 0 && local.uploaded == total && cloud.uploaded == total
}
