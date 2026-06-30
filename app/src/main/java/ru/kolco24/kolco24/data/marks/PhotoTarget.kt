package ru.kolco24.kolco24.data.marks

import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.MarkEntity

/** Window after the latest complete take during which a photo auto-attaches to it (3 minutes). */
const val PHOTO_ATTACH_WINDOW_MS = 180_000L

/**
 * Where a freshly-started photo session should land — the pure, Android-free entry-point router.
 *
 * [AttachTo] means a complete take happened recently enough (within [PHOTO_ATTACH_WINDOW_MS]) that the
 * photo is treated as extra evidence for that same КП — no number prompt, the existing [MarkEntity] row
 * (and its anti-cheat coordinate) is reused. [AskNumber] means there is no recent take, so the user must
 * pick the КП number before shooting (a brand-new standalone photo-mark is created afterwards).
 */
sealed interface PhotoTarget {
    /** Attach the photos to the existing take [markId] (checkpoint [cpNumber] / [checkpointId]). */
    data class AttachTo(
        val markId: String,
        val cpNumber: Int,
        val checkpointId: Int,
    ) : PhotoTarget

    /** No recent take — ask the user for the КП number, then create a standalone photo-mark. */
    data object AskNumber : PhotoTarget
}

/**
 * Decide whether a new photo session attaches to a recent take or must ask for a КП number.
 *
 * The newest **complete** take whose effective time (`trustedTakenAt ?: takenAt`, mirroring the marks
 * gallery) is within [PHOTO_ATTACH_WINDOW_MS] of [nowMs] yields [PhotoTarget.AttachTo]; otherwise
 * [PhotoTarget.AskNumber]. Incomplete takes are ignored (they are kept only for the server log). The
 * window boundary is inclusive (exactly 3 minutes still attaches).
 */
fun decidePhotoTarget(marks: List<MarkEntity>, nowMs: Long): PhotoTarget {
    val latest = marks
        .filter { it.complete }
        .maxByOrNull { it.trustedTakenAt ?: it.takenAt }
        ?: return PhotoTarget.AskNumber
    val takenAt = latest.trustedTakenAt ?: latest.takenAt
    return if (nowMs - takenAt <= PHOTO_ATTACH_WINDOW_MS) {
        PhotoTarget.AttachTo(
            markId = latest.id,
            cpNumber = latest.checkpointNumber,
            checkpointId = latest.checkpointId,
        )
    } else {
        PhotoTarget.AskNumber
    }
}

/**
 * Resolve a КП number entered by the user against the synced [legend]. Returns the matching checkpoint
 * or `null` when the number is absent (the v1 "warning if not in legend" behaviour — no orphan mark).
 *
 * A **locked** checkpoint (`locked = true`, `cost = null`) deliberately still resolves: that is the core
 * "метку сорвали" scenario (the code was never read, so the КП stays unrevealed). The photo-mark is
 * still scored as a hybrid (`complete = true`, cost falls back to `0` while locked); after a later
 * reveal the live-cost resolver picks up the real value.
 */
fun resolvePhotoCheckpoint(number: Int, legend: List<CheckpointEntity>): CheckpointEntity? =
    legend.firstOrNull { it.number == number }
