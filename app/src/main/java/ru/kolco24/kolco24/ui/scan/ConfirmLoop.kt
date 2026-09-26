package ru.kolco24.kolco24.ui.scan

import kotlinx.coroutines.delay
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.UploadTarget

/**
 * Scan-overlay confirmation state for a `cloud`/`local` take: the overlay stays open while the mark
 * is POSTed to [target] and only a server accept counts the КП. Both states carry the [target], so
 * the overlay derives it once (on the completing transition) and reads it back for the loop and
 * «Повторить». There is no "confirmed" state — [runConfirm] reports success through its return
 * value. Pure so the loop is JVM-tested.
 */
sealed interface ConfirmState {
    val target: UploadTarget

    data class Sending(override val target: UploadTarget, val attempt: Int) : ConfirmState
    data class Failed(override val target: UploadTarget, val offline: Boolean) : ConfirmState
}

/**
 * Total retry window measured from the first attempt; the last attempt may overrun by one request
 * timeout.
 */
internal const val CONFIRM_TIMEOUT_MS = 20_000L

/** Pause between a failed attempt and the next one. */
internal const val CONFIRM_RETRY_MS = 3_000L

/**
 * Retry [attempt] until it returns [UploadResultKind.Ok] (returns `true`) or [timeoutMs] has elapsed
 * since the start (emits [ConfirmState.Failed] — `offline` from the last result — and returns `false`).
 * [onState] sees only [ConfirmState.Sending] (before each attempt) and the terminal [ConfirmState.Failed].
 * An attempt that starts before the deadline and succeeds after it still confirms — the deadline is
 * checked only after a failed attempt. Cancellation propagates normally (no terminal state is emitted).
 * [timeoutMs]/[retryMs] are overridable only so tests can pin the deadline boundary.
 */
suspend fun runConfirm(
    target: UploadTarget,
    attempt: suspend () -> UploadResultKind,
    onState: (ConfirmState) -> Unit,
    elapsedNow: () -> Long,
    timeoutMs: Long = CONFIRM_TIMEOUT_MS,
    retryMs: Long = CONFIRM_RETRY_MS,
): Boolean {
    val start = elapsedNow()
    var n = 1
    while (true) {
        onState(ConfirmState.Sending(target, n))
        val r = attempt()
        if (r == UploadResultKind.Ok) return true
        if (elapsedNow() - start >= timeoutMs) {
            onState(ConfirmState.Failed(target, offline = r == UploadResultKind.Offline))
            return false
        }
        delay(retryMs)
        n++
    }
}

/** User-facing status line for [state] (the scan overlay's `ConfirmStatus`). */
fun confirmStatusText(state: ConfirmState): String = when (state) {
    is ConfirmState.Sending -> when (state.target) {
        UploadTarget.Cloud -> "Отправка на сервер…"
        UploadTarget.Local -> "Отправка на локальный сервер…"
    } + " (попытка ${state.attempt})"
    is ConfirmState.Failed ->
        if (state.offline) "Нет связи — КП не подтверждён"
        else "Сервер не принял — КП не подтверждён"
}
