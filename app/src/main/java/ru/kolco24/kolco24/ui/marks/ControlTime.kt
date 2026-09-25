package ru.kolco24.kolco24.ui.marks

import ru.kolco24.kolco24.data.db.MarkEntity

/**
 * Live control-time (КВ) state of the selected team — pure model behind the «ДО КВ» cell of the
 * Отметки metrics card (port of the iOS control-time feature). Android-free, JVM-tested in
 * `ControlTimeTest`.
 */
sealed interface ControlTimeState {
    /** КВ of the category is not set (`control_time == 0`) and the team has not finished. */
    data object Unknown : ControlTimeState

    /** No own NFC take on a `start` КП yet — show the category's КВ. */
    data class NotStarted(val limitMs: Long) : ControlTimeState

    /** Started, КВ not yet reached — countdown. */
    data class Running(val remainingMs: Long) : ControlTimeState

    /** КВ reached, no finish take — lateness. */
    data class Overtime(val overMs: Long) : ControlTimeState

    /**
     * Finished: [elapsedMs] = finish − start. [overMs] is non-null only when the whole-minute duration
     * exceeds the КВ (server rule `int(ms/60000) > control_time`), i.e. a penalty-worthy lateness.
     */
    data class Finished(val elapsedMs: Long, val overMs: Long?) : ControlTimeState
}

private const val MINUTE_MS = 60_000L

/**
 * Computes the [ControlTimeState] from the team's own [marks].
 *
 * Only `method == "nfc"` takes with a positive [timeOf] count (mirrors the server's boundary-time
 * auto-population). Start = the earliest take on a `start`-type КП (roster completeness is irrelevant);
 * finish = the earliest take on a `finish`-type КП at or after the start. [checkpointTypes] maps
 * checkpoint id → `CheckpointEntity.type`; without a legend it is empty, so no start is ever found.
 * [controlMinutes] is the category КВ in minutes (`0` = not set). Minutes are floored everywhere.
 */
fun controlTimeState(
    marks: List<MarkEntity>,
    checkpointTypes: Map<Int, String>,
    controlMinutes: Int,
    nowMs: Long,
    timeOf: (MarkEntity) -> Long,
): ControlTimeState {
    val timed = marks.asSequence()
        .filter { it.method == "nfc" }
        .map { it to timeOf(it) }
        .filter { (_, t) -> t > 0 }
        .toList()
    val start = timed
        .filter { (m, _) -> checkpointTypes[m.checkpointId] == "start" }
        .minOfOrNull { (_, t) -> t }
    val finish = start?.let { s ->
        timed
            .filter { (m, t) -> checkpointTypes[m.checkpointId] == "finish" && t >= s }
            .minOfOrNull { (_, t) -> t }
    }
    val limitMs = controlMinutes * MINUTE_MS
    if (start != null && finish != null) {
        val elapsed = finish - start
        val overMs =
            if (controlMinutes > 0 && elapsed / MINUTE_MS > controlMinutes) elapsed - limitMs else null
        return ControlTimeState.Finished(elapsed, overMs)
    }
    if (controlMinutes <= 0) return ControlTimeState.Unknown
    if (start == null) return ControlTimeState.NotStarted(limitMs)
    val elapsed = nowMs - start
    return if (elapsed < limitMs) {
        ControlTimeState.Running(limitMs - elapsed)
    } else {
        ControlTimeState.Overtime(elapsed - limitMs)
    }
}

/**
 * Take time of [m] on the trusted scale: [MarkEntity.trustedTakenAt], else the monotonic mark
 * re-anchored via [trustedAt] (`TrustedClock.trustedAt`), else the raw wall [MarkEntity.takenAt].
 * Duplicates `MarkRepository.backfillTrustedMs` on purpose (duplicate, don't couple) so the countdown
 * matches what the server receives.
 */
fun resolveMarkTime(m: MarkEntity, trustedAt: (Long, Int?) -> Long?): Long =
    m.trustedTakenAt ?: m.elapsedRealtimeAt?.let { trustedAt(it, m.bootCount) } ?: m.takenAt

/** `Ч:ММ` with minutes floored (server rounding); negative input clamps to `0:00`. */
fun formatHoursMinutes(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0) / MINUTE_MS
    return "${totalMinutes / 60}:${(totalMinutes % 60).toString().padStart(2, '0')}"
}

/**
 * Delay until the displayed minute of [state] changes — exactly on the minute boundary counted from
 * the start (not the wall-clock minute). `null` for the static states (no ticking needed).
 */
fun msUntilNextChange(state: ControlTimeState): Long? = when (state) {
    is ControlTimeState.Running -> state.remainingMs % MINUTE_MS + 1
    is ControlTimeState.Overtime -> MINUTE_MS - state.overMs % MINUTE_MS
    else -> null
}

/** Display triple for the КВ metrics cell; [isError] renders in `colorScheme.error`. */
data class ControlTimeLabel(val label: String, val value: String, val isError: Boolean)

fun controlTimeLabel(state: ControlTimeState): ControlTimeLabel = when (state) {
    ControlTimeState.Unknown -> ControlTimeLabel("КВ", "—", false)
    is ControlTimeState.NotStarted -> ControlTimeLabel("КВ", formatHoursMinutes(state.limitMs), false)
    is ControlTimeState.Running -> ControlTimeLabel("ДО КВ", formatHoursMinutes(state.remainingMs), false)
    is ControlTimeState.Overtime ->
        ControlTimeLabel("ОПОЗДАНИЕ", "+" + formatHoursMinutes(state.overMs), true)
    is ControlTimeState.Finished ->
        ControlTimeLabel("ВРЕМЯ", formatHoursMinutes(state.elapsedMs), state.overMs != null)
}
