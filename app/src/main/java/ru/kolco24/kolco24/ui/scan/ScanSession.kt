package ru.kolco24.kolco24.ui.scan

import ru.kolco24.kolco24.data.UnlockOutcome
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.nfc.chipCodeHex

/**
 * In-flight state of one «Отметить КП» session — a sliding 20 s window that accumulates the team's
 * present-set around a single checkpoint chip. Pure value type: no Android, no I/O, no timer. The
 * stateful host ([ScanScreen]) owns the clock and the Room writes; this only describes what has been
 * scanned so far.
 *
 * The KP chip and the member bracelets can be scanned in any order. Until the KP chip is read
 * ([point] == null), member scans are held in [bufferedBeforeKp]; once the KP arrives, the buffer is
 * drained into [present] (see [reduce]). [lastScanAt] is the epoch-ms of the most recent **accepted**
 * scan and drives the window: an `UnboundChip`/`BadKp` scan is ignored and does **not** advance it.
 */
data class ScanSession(
    val point: Int?,
    val checkpointNumber: Int?,
    val cost: Int?,
    val cpUid: String?,
    val cpCode: String?,
    val present: Set<Int>,
    val bufferedBeforeKp: Set<Int>,
    val lastScanAt: Long,
) {
    companion object {
        /** A fresh session with no KP and no members yet, stamped with the first scan's [now]. */
        fun empty(now: Long): ScanSession = ScanSession(
            point = null,
            checkpointNumber = null,
            cost = null,
            cpUid = null,
            cpCode = null,
            present = emptySet(),
            bufferedBeforeKp = emptySet(),
            lastScanAt = now,
        )
    }
}

/**
 * The classified result of a single NFC tap, decided purely by [classifyTag]. Only [Kp] and [Member]
 * advance the session; [UnboundChip] and [BadKp] are diagnostics the UI surfaces without touching the
 * window.
 */
sealed interface ScanEvent {
    /** The checkpoint chip: identifies [point] with its resolved [number]/[cost] and anti-cheat log. */
    data class Kp(
        val point: Int,
        val number: Int,
        val cost: Int,
        val cpUid: String,
        val cpCode: String,
    ) : ScanEvent

    /** A bound team-member bracelet ([numberInTeam] is the member's slot within the roster). */
    data class Member(val numberInTeam: Int) : ScanEvent

    /** A bracelet whose uid is not bound to any member of the current team. */
    data object UnboundChip : ScanEvent

    /** A checkpoint chip that could not be turned into a usable [Kp] (unknown / crypto fail / no legend). */
    data class BadKp(val reason: String) : ScanEvent
}

/**
 * Folds one [event] into the [session] at time [now]. Pure; the only state machine of the scan flow.
 *
 * - [ScanEvent.Kp] sets the KP fields and **drains** [ScanSession.bufferedBeforeKp] into
 *   [ScanSession.present] (members scanned before the chip count once the chip lands). A repeat KP
 *   scan just re-stamps the window.
 * - [ScanEvent.Member] goes to the buffer while [ScanSession.point] is null, otherwise straight into
 *   `present`; set-semantics make a repeated member idempotent. A member scanned with no session yet
 *   starts one (so pre-KP bracelets are not lost).
 * - [ScanEvent.UnboundChip] / [ScanEvent.BadKp] are ignored — the session (and its window) is
 *   returned unchanged, so a stray tap never extends the 20 s timer.
 *
 * Any accepted scan refreshes [ScanSession.lastScanAt] to [now].
 */
fun reduce(session: ScanSession?, event: ScanEvent, now: Long): ScanSession? = when (event) {
    is ScanEvent.Kp -> {
        val base = session ?: ScanSession.empty(now)
        base.copy(
            point = event.point,
            checkpointNumber = event.number,
            cost = event.cost,
            cpUid = event.cpUid,
            cpCode = event.cpCode,
            present = base.present + base.bufferedBeforeKp,
            bufferedBeforeKp = emptySet(),
            lastScanAt = now,
        )
    }

    is ScanEvent.Member -> {
        val base = session ?: ScanSession.empty(now)
        if (base.point == null) {
            base.copy(bufferedBeforeKp = base.bufferedBeforeKp + event.numberInTeam, lastScanAt = now)
        } else {
            base.copy(present = base.present + event.numberInTeam, lastScanAt = now)
        }
    }

    ScanEvent.UnboundChip, is ScanEvent.BadKp -> session
}

/**
 * Classifies one NFC tap into a [ScanEvent], purely (no Android `Tag` I/O, no crypto — the caller
 * reads the chip [code]/[uid] and runs [UnlockOutcome] beforehand).
 *
 * A non-null [code] is a checkpoint chip: the [unlock] outcome's `point` is resolved against
 * [checkpointsById] for the [number]/[cost] snapshot ([UnlockOutcome.unlock] only returns the point).
 * A still-`null` cost (legend not synced) downgrades to [ScanEvent.BadKp]. A null [code] is a
 * bracelet: looked up in [bindings] (uid → numberInTeam) for [ScanEvent.Member] or
 * [ScanEvent.UnboundChip].
 */
fun classifyTag(
    code: ByteArray?,
    uid: String,
    unlock: UnlockOutcome?,
    bindings: Map<String, Int>,
    checkpointsById: Map<Int, CheckpointEntity>,
): ScanEvent {
    if (code != null) {
        val point = when (unlock) {
            is UnlockOutcome.Revealed -> unlock.point
            is UnlockOutcome.IdentityOnly -> unlock.point
            is UnlockOutcome.Failed -> return ScanEvent.BadKp(unlock.reason)
            UnlockOutcome.Unknown -> return ScanEvent.BadKp("неизвестный чип")
            null -> return ScanEvent.BadKp("не удалось расшифровать")
        }
        val cp = checkpointsById[point]
        val cost = cp?.cost ?: return ScanEvent.BadKp("легенда не загружена")
        return ScanEvent.Kp(
            point = point,
            number = cp.number,
            cost = cost,
            cpUid = uid,
            cpCode = chipCodeHex(code),
        )
    }
    val numberInTeam = bindings[uid] ?: return ScanEvent.UnboundChip
    return ScanEvent.Member(numberInTeam)
}
