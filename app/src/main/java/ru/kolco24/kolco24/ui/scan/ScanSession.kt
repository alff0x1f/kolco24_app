package ru.kolco24.kolco24.ui.scan

import ru.kolco24.kolco24.data.UnlockOutcome
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.marks.CheckMethod
import ru.kolco24.kolco24.data.nfc.chipCodeHex
import ru.kolco24.kolco24.data.track.UploadTarget

/** Sliding scan-window duration in milliseconds. Shared by ScanScreen's UI timer and MainActivity's DB-side expiry. */
internal const val SCAN_WINDOW_MS = 20_000L

/**
 * Has the 20 s sliding window elapsed between [lastScanAt] and [now]?
 *
 * Both arguments are **monotonic** `SystemClock.elapsedRealtime()` ms (immune to wall-clock changes —
 * translating the phone clock can no longer collapse or freeze the window). A null [lastScanAt] (no
 * scan yet) is never expired. The boundary is `>=`: exactly [SCAN_WINDOW_MS] after the last scan
 * counts as expired (matches the host's prior `>=` guard).
 */
fun isWindowExpired(lastScanAt: Long?, now: Long): Boolean =
    lastScanAt != null && (now - lastScanAt) >= SCAN_WINDOW_MS

/**
 * In-flight state of one «Отметить КП» session — a sliding 20 s window that accumulates the team's
 * present-set around a single checkpoint chip. Pure value type: no Android, no I/O, no timer. The
 * stateful host ([ScanScreen]) owns the clock and the Room writes; this only describes what has been
 * scanned so far.
 *
 * The KP chip and the member bracelets can be scanned in any order. Until the KP chip is read
 * ([checkpointId] == null), member scans are held in [bufferedBeforeKp]; once the KP arrives, the buffer is
 * drained into [present] (see [reduce]). [lastScanAt] is the **monotonic** `elapsedRealtime` ms of the
 * most recent **accepted** scan and drives the window: an `UnboundChip`/`BadKp` scan is ignored and
 * does **not** advance it. (Monotonic, not wall-clock, so translating the phone clock can't skew it.)
 * [checkMethod] is the КП tag's verification rule and [expectedCount] the roster size, both snapshotted by
 * [reduce] when a [ScanEvent.Kp] **opens** the take (a new КП / fresh session) and kept on a same-КП
 * re-scan — exactly like the host's DB take row (`MarkRepository.startKpTake` snapshots `checkMethod` and
 * `expectedCount` once; a same-КП re-scan reuses the row). [checkMethod] defaults to
 * [CheckMethod.Offline] and [expectedCount] to 0 (never complete) until a КП lands.
 */
data class ScanSession(
    val checkpointId: Int?,
    val checkpointNumber: Int?,
    val cost: Int?,
    val cpUid: String?,
    val cpCode: String?,
    val present: Set<Int>,
    val bufferedBeforeKp: Set<Int>,
    val lastScanAt: Long,
    val checkMethod: CheckMethod = CheckMethod.Offline,
    val expectedCount: Int = 0,
) {
    companion object {
        /** A fresh session with no KP and no members yet, stamped with the first scan's [now]. */
        fun empty(now: Long): ScanSession = ScanSession(
            checkpointId = null,
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
    /**
     * The checkpoint chip: identifies [checkpointId] with its resolved [number]/[cost] and anti-cheat log.
     * [checkMethod] is the tag's parsed verification rule ([classifyTag] always passes it; the default
     * only keeps test literals short).
     */
    data class Kp(
        val checkpointId: Int,
        val number: Int,
        val cost: Int,
        val cpUid: String,
        val cpCode: String,
        val checkMethod: CheckMethod = CheckMethod.Offline,
    ) : ScanEvent

    /** A bound team-member bracelet ([numberInTeam] is the member's slot within the roster). */
    data class Member(val numberInTeam: Int) : ScanEvent

    /** A bracelet whose uid is not bound to any member of the current team. */
    data object UnboundChip : ScanEvent

    /** A checkpoint chip that could not be turned into a usable [Kp] (unknown / crypto fail / no legend). */
    data class BadKp(val reason: String) : ScanEvent
}

/**
 * Folds one [event] into the [session] at time [now] (monotonic `elapsedRealtime` ms). Pure; the only
 * state machine of the scan flow.
 *
 * - [ScanEvent.Kp] sets the KP fields and **drains** [ScanSession.bufferedBeforeKp] into
 *   [ScanSession.present] (members scanned before the chip count once the chip lands). A КП that opens
 *   a take (no КП yet, or a different КП) snapshots [ScanSession.checkMethod] from the tag and
 *   [ScanSession.expectedCount] from [rosterSize]; a repeat scan of the same КП just re-stamps the
 *   window and keeps both snapshots (the host reuses the persisted take row with its original method
 *   and expected count, even if this physical tag carries a different method or the roster changed).
 *   [rosterSize] (the live roster size at this tap) is only read on such a take-opening КП; its 0
 *   default (a take that never completes) only keeps member-only test calls short.
 * - [ScanEvent.Member] goes to the buffer while [ScanSession.checkpointId] is null, otherwise straight into
 *   `present`; set-semantics make a repeated member idempotent. A member scanned with no session yet
 *   starts one (so pre-KP bracelets are not lost). Re-scanning a member who is **already** counted
 *   does **not** refresh the window — otherwise one person could keep the 20 s timer alive
 *   indefinitely by re-tapping their own chip while walking back to the team.
 * - [ScanEvent.UnboundChip] / [ScanEvent.BadKp] are ignored — the session (and its window) is
 *   returned unchanged, so a stray tap never extends the 20 s timer.
 *
 * Any scan that adds new information (a KP or a not-yet-counted member) refreshes
 * [ScanSession.lastScanAt] to [now]; an idempotent re-scan leaves the window untouched.
 */
fun reduce(session: ScanSession?, event: ScanEvent, now: Long, rosterSize: Int = 0): ScanSession? = when (event) {
    is ScanEvent.Kp -> {
        val base = session ?: ScanSession.empty(now)
        // When switching to a different КП, discard the prior KP's members — they were present at a
        // different checkpoint. A repeat scan of the same КП preserves accumulated members and the
        // take's method / expected-count snapshots (mirrors the host reusing the persisted row).
        val sameTake = session?.checkpointId == event.checkpointId
        val priorPresent = if (sameTake) base.present else emptySet()
        base.copy(
            checkpointId = event.checkpointId,
            checkpointNumber = event.number,
            cost = event.cost,
            cpUid = event.cpUid,
            cpCode = event.cpCode,
            checkMethod = if (sameTake) base.checkMethod else event.checkMethod,
            expectedCount = if (sameTake) base.expectedCount else rosterSize,
            present = priorPresent + base.bufferedBeforeKp,
            bufferedBeforeKp = emptySet(),
            lastScanAt = now,
        )
    }

    is ScanEvent.Member -> {
        val base = session ?: ScanSession.empty(now)
        if (base.checkpointId == null) {
            // Already buffered → idempotent, leave the window alone.
            if (event.numberInTeam in base.bufferedBeforeKp) base
            else base.copy(bufferedBeforeKp = base.bufferedBeforeKp + event.numberInTeam, lastScanAt = now)
        } else {
            // Already present → idempotent, leave the window alone.
            if (event.numberInTeam in base.present) base
            else base.copy(present = base.present + event.numberInTeam, lastScanAt = now)
        }
    }

    ScanEvent.UnboundChip, is ScanEvent.BadKp -> session
}

/**
 * Classifies one NFC tap into a [ScanEvent], purely (no Android `Tag` I/O, no crypto — the caller
 * reads the chip [code]/[uid] and runs [UnlockOutcome] beforehand).
 *
 * A non-null [code] is a checkpoint chip: the [unlock] outcome's `checkpointId` is resolved against
 * [checkpointsById] for the [number]/[cost] snapshot ([UnlockOutcome.unlock] only returns the id).
 * A still-`null` cost (legend not synced) downgrades to [ScanEvent.BadKp]. A null [code] is a
 * bracelet: looked up in [bindings] (uid → numberInTeam) for [ScanEvent.Member] or
 * [ScanEvent.UnboundChip]. The tag's raw `check_method` is parsed into [ScanEvent.Kp.checkMethod]
 * (unknown → [CheckMethod.Offline]).
 */
fun classifyTag(
    code: ByteArray?,
    uid: String,
    unlock: UnlockOutcome?,
    bindings: Map<String, Int>,
    checkpointsById: Map<Int, CheckpointEntity>,
): ScanEvent {
    if (code != null) {
        val (checkpointId, rawMethod) = when (unlock) {
            is UnlockOutcome.Revealed -> unlock.checkpointId to unlock.checkMethod
            is UnlockOutcome.IdentityOnly -> unlock.checkpointId to unlock.checkMethod
            is UnlockOutcome.Failed -> return ScanEvent.BadKp(unlock.reason)
            UnlockOutcome.Unknown -> return ScanEvent.BadKp("неизвестный чип")
            null -> return ScanEvent.BadKp("не удалось расшифровать")
        }
        val cp = checkpointsById[checkpointId]
        val cost = cp?.cost ?: return ScanEvent.BadKp("легенда не загружена")
        return ScanEvent.Kp(
            checkpointId = checkpointId,
            number = cp.number,
            cost = cost,
            cpUid = uid,
            cpCode = chipCodeHex(code),
            checkMethod = CheckMethod.parse(rawMethod),
        )
    }
    val numberInTeam = bindings[uid] ?: return ScanEvent.UnboundChip
    return ScanEvent.Member(numberInTeam)
}

/**
 * UI-close decision: is the take "complete" — a КП identified and every member expected **when the take
 * opened** ([ScanSession.expectedCount]) present?
 *
 * Mirrors `MarkRepository`'s `complete = present.size >= expectedCount` on the same snapshotted count, so
 * the overlay's completion (and its confirm-mode entry) agrees with the persisted row even if the roster
 * grows or shrinks mid-take. Requires [ScanSession.checkpointId] != null (pre-КП members live in
 * [ScanSession.bufferedBeforeKp] and are drained into [ScanSession.present] only once the КП lands) and
 * a non-zero expected count.
 */
fun isComplete(session: ScanSession?): Boolean =
    session?.checkpointId != null && session.expectedCount > 0 && session.present.size >= session.expectedCount

/** What a processed tap did to take completion — decided purely by [completionOnTransition]. */
sealed interface Completion {
    /** No incomplete → complete transition (still collecting, or a repeat tap after completion). */
    data object None : Completion

    /** An `offline` take just completed: it counts now (fanfare + success beat + auto-close). */
    data object Counted : Completion

    /** A `cloud`/`local` take just completed: enter confirm mode against [target]; nothing counts yet. */
    data class Confirm(val target: UploadTarget) : Completion
}

/**
 * The scan overlay's completion decision for one tap: [wasComplete] is [isComplete] of the session the
 * tap was folded into (after the window-expiry reset), [session] the reduced result. Only the incomplete →
 * complete edge fires; its kind follows the take's snapshotted [ScanSession.checkMethod] (a switch to a
 * different КП opens a new take and replaces the rule; a same-КП re-scan keeps it). Also covers completion arriving on a
 * [ScanEvent.Kp] when pre-КП buffered members drain into `present`.
 */
fun completionOnTransition(wasComplete: Boolean, session: ScanSession?): Completion {
    if (wasComplete || !isComplete(session)) return Completion.None
    val target = session?.checkMethod?.uploadTarget ?: return Completion.Counted
    return Completion.Confirm(target)
}
