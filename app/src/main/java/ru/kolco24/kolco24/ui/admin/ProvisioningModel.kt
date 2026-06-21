package ru.kolco24.kolco24.ui.admin

import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.legend.parseCheckpointColor

/**
 * Pure, Android-free model of the bulk chip-provisioning flow (mirrors `ScanSession.kt` /
 * `CheckpointColor.kt` — no Compose, no Android, JVM-unit-testable). The stateful host
 * ([ProvisioningScreen], wired in Task 12) owns the pager, the NFC hook, and the bind/write side
 * effects; this only describes the in-flight scan state, the rail layout, and the user-facing strings.
 */

/**
 * State of the current chip being provisioned against the settled КП. A fresh page (and the idle
 * scan zone) sits in [WaitingForChip]; a tapped chip walks [Binding] → [Writing] → [Success] (or
 * [Failed] on any error). The host stamps a new state per tap and resets to [WaitingForChip] when the
 * pager settles on another КП.
 */
sealed interface ProvisionState {
    /** No chip in progress — the scan zone pulses «Приложите чип к телефону». */
    data object WaitingForChip : ProvisionState

    /** A chip [uid] was read and is being bound to the КП server-side (`POST .../tags/`). */
    data class Binding(val uid: String) : ProvisionState

    /** The bind returned a `code`; that code is being written onto the chip over NFC. */
    data object Writing : ProvisionState

    /** The chip was bound and written: it now carries the КП whose participant [number] is shown. */
    data class Success(val number: Int) : ProvisionState

    /** The bind or write failed; [reason] is the user-facing RU message to surface in the scan zone. */
    data class Failed(val reason: String) : ProvisionState
}

/**
 * One segment of the provisioning rail (one per race checkpoint, in pager order). [color] tints the
 * tick (null = neutral/no color), [filled] is set once the КП has ≥1 bound chip (cached + freshly
 * written), and [current] marks the page the pager is settled on (the enlarged tick).
 */
data class RailTick(
    val color: CheckpointColor?,
    val filled: Boolean,
    val current: Boolean,
)

/**
 * Builds the rail from the [checkpoints] (pager order), the per-checkpoint bound-chip [boundCounts]
 * (keyed by `checkpoint.id`; missing → 0), and the settled [currentIndex]. A tick is [RailTick.filled]
 * when its count is `> 0` and [RailTick.current] when its position equals [currentIndex]. Pure.
 */
fun railTicks(
    checkpoints: List<CheckpointEntity>,
    boundCounts: Map<Int, Int>,
    currentIndex: Int,
): List<RailTick> = checkpoints.mapIndexed { index, cp ->
    RailTick(
        color = parseCheckpointColor(cp.color),
        filled = (boundCounts[cp.id] ?: 0) > 0,
        current = index == currentIndex,
    )
}

/**
 * Maps a non-success [PostResult] from a `bindTag` call to a user-facing RU message for the scan zone.
 * The caller (host) handles [PostResult.Success] before calling this; an unexpected Success falls to
 * the `else` branch and returns «Ошибка сервера» as a safe fallback.
 *
 * - `409` ([PostResult.Conflict]) → «уже привязан к другому КП». The server's `409` body carries no
 *   checkpoint number (`{"detail": ...}`), so the message is **generic** — it can't name the other КП.
 * - `404` ([PostResult.Error] with code 404) → «КП не найдено» (the checkpoint isn't bindable).
 * - `403` ([PostResult.Forbidden]) → covers **both** a non-admin authenticated user **and** a
 *   build-HMAC signature/clock failure (the spec returns `403` for either), hence the combined message.
 */
fun provisionErrorMessage(result: PostResult<*>): String = when (result) {
    PostResult.Conflict -> "Этот тег уже привязан к другому КП"
    PostResult.Forbidden -> "Нет прав администратора этой гонки или ошибка подписи/часов"
    PostResult.Unauthorized -> "Сессия истекла, войдите снова"
    PostResult.BadRequest -> "Неверный запрос"
    PostResult.RateLimited -> "Слишком часто, подождите немного"
    PostResult.Offline -> "Нет сети, попробуйте снова"
    is PostResult.Error -> if (result.code == 404) "КП не найдено" else "Ошибка сервера"
    else -> "Ошибка сервера"
}

/**
 * Short display label for a freshly-written chip token — the last 4 hex chars of the normalized [uid]
 * (already uppercase). A shorter uid is returned whole. Pure.
 */
fun chipTokenLabel(uid: String): String =
    if (uid.length <= 4) uid else uid.takeLast(4)
