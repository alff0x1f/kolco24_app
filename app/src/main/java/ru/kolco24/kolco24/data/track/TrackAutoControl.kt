package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.db.CP_TYPE_FINISH
import ru.kolco24.kolco24.data.db.CP_TYPE_TEST
import ru.kolco24.kolco24.data.db.normalizeCpType

enum class TrackAutoAction { Start, Stop, None }

/**
 * What a fresh КП take should do to GPS-track recording. [cpType] is `CheckpointEntity.type`
 * (`start|finish|test|kp`, `null` when the legend has no such КП yet; case/whitespace-insensitive).
 * `test` is used before the race and never acts. `finish` stops a running recording. Any other type
 * (incl. blank or unknown server types) starts recording when it is not running — a missed «Старт» or
 * a manual stop self-heals on the next КП — unless the team already has a take on a `finish` КП
 * ([finishTaken], evaluated before this take), which latches auto-start off.
 */
fun trackAutoAction(cpType: String?, recording: Boolean, finishTaken: Boolean): TrackAutoAction {
    val type = normalizeCpType(cpType)
    return when {
        type == null || type == CP_TYPE_TEST -> TrackAutoAction.None
        type == CP_TYPE_FINISH -> if (recording) TrackAutoAction.Stop else TrackAutoAction.None
        finishTaken -> TrackAutoAction.None
        recording -> TrackAutoAction.None
        else -> TrackAutoAction.Start
    }
}

/**
 * Whether any of the team's takes (their [checkpointIds], read **before** the new take is written) is
 * on a `finish` КП. Any take counts — NFC or photo, complete or not. An id [typeOf] can't resolve
 * (КП no longer in the legend) does not count.
 */
fun hasFinishTake(checkpointIds: Iterable<Int>, typeOf: (Int) -> String?): Boolean =
    checkpointIds.any { normalizeCpType(typeOf(it)) == CP_TYPE_FINISH }

/**
 * The team a recording is running for, for auto-control purposes: `null` when [Idle][TrackState.Idle]
 * or when the recording is already [stopping][TrackState.Recording.stopping] (a start sent during the
 * teardown flush resumes recording as a new session instead of being swallowed as «already recording»).
 */
fun TrackState.activeRecordingTeamId(): Int? =
    (this as? TrackState.Recording)?.takeUnless { it.stopping }?.teamId

/**
 * [action] to apply now plus the new value of the parked-start flag ([pending], `null` = unchanged).
 */
data class TrackAutoDecision(val action: TrackAutoAction, val pending: Boolean?)

/**
 * Full take-time decision: [trackAutoAction] plus the permission gate and the parked-start flag.
 * Recording counts only when it is [takeTeamId]'s ([recordingTeamId] is the running service's team,
 * `null` when idle). A `Start` without location permission ([permitted]) is parked (`None`,
 * pending = true); a `Start` that goes through clears any parked start; any `finish` take clears it
 * too (even when nothing records and the action is `None`); everything else leaves it as is.
 */
fun trackAutoDecision(
    cpType: String?,
    recordingTeamId: Int?,
    takeTeamId: Int,
    finishTaken: Boolean,
    permitted: Boolean,
): TrackAutoDecision {
    val action = trackAutoAction(cpType, recording = recordingTeamId == takeTeamId, finishTaken = finishTaken)
    return when {
        action == TrackAutoAction.Start && !permitted -> TrackAutoDecision(TrackAutoAction.None, pending = true)
        action == TrackAutoAction.Start -> TrackAutoDecision(action, pending = false)
        normalizeCpType(cpType) == CP_TYPE_FINISH -> TrackAutoDecision(action, pending = false)
        else -> TrackAutoDecision(action, pending = null)
    }
}

/** What the host does with a parked auto-start right now. */
enum class PendingTrackStart {
    /** Nothing to do yet — keep the flag (or nothing is parked). */
    Wait,
    /** Clear the flag and start recording. */
    StartNow,
    /** Clear the flag and launch the track permission request. */
    AskPermission,
    /** Clear the flag, do nothing. */
    Drop,
}

/**
 * Resolve a parked auto-start ([pending]). Waits until the take overlays have [settled] (no scan/photo
 * overlay open and no scan-overlay location ask in flight — a parallel launch gets an instant empty
 * result logged as a real denial) and while [raceId]/[teamId] are not known yet (a transient null after
 * an Activity recreation must not lose the start). Then: already recording for the team → drop (no
 * duplicate start); permission granted meanwhile → start; otherwise ask only if nothing asked this
 * session ([askedThisSession] — a second denial is permanent on 11+), else drop.
 */
fun resolvePendingTrackStart(
    pending: Boolean,
    settled: Boolean,
    raceId: Int?,
    teamId: Int?,
    recording: Boolean,
    permitted: Boolean,
    askedThisSession: Boolean,
): PendingTrackStart = when {
    !pending || !settled || raceId == null || teamId == null -> PendingTrackStart.Wait
    recording -> PendingTrackStart.Drop
    permitted -> PendingTrackStart.StartNow
    !askedThisSession -> PendingTrackStart.AskPermission
    else -> PendingTrackStart.Drop
}

/**
 * After an auto-start: ask for `POST_NOTIFICATIONS` (so the recording notification with its «Стоп» is
 * visible) only on 13+ ([runtimePermission]), when not [granted], never denied before ([deniedBefore]
 * — no settings-dialog nagging after an automatic start) and not already asked by this path this
 * session ([askedThisSession]).
 */
fun shouldAskNotificationsAfterAutoStart(
    runtimePermission: Boolean,
    granted: Boolean,
    deniedBefore: Boolean,
    askedThisSession: Boolean,
): Boolean = runtimePermission && !granted && !deniedBefore && !askedThisSession
