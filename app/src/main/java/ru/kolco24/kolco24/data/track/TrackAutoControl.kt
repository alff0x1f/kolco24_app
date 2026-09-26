package ru.kolco24.kolco24.data.track

enum class TrackAutoAction { Start, Stop, None }

/**
 * What a fresh КП take should do to GPS-track recording. [cpType] is `CheckpointEntity.type`
 * (`start|finish|test|kp`, `null` when the legend has no such КП yet). `test` is used before the race
 * and never acts. `finish` stops a running recording. Any other type starts recording when it is not
 * running — a missed «Старт» or a manual stop self-heals on the next КП — unless the team already has
 * a take on a `finish` КП ([finishTaken], evaluated before this take), which latches auto-start off.
 */
fun trackAutoAction(cpType: String?, recording: Boolean, finishTaken: Boolean): TrackAutoAction = when {
    cpType == null || cpType == "test" -> TrackAutoAction.None
    cpType == "finish" -> if (recording) TrackAutoAction.Stop else TrackAutoAction.None
    finishTaken -> TrackAutoAction.None
    recording -> TrackAutoAction.None
    else -> TrackAutoAction.Start
}
