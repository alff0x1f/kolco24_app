package ru.kolco24.kolco24.ui.admin

import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.db.MemberTagEntity
import ru.kolco24.kolco24.data.nfc.CHIP_CODE_BYTES
import ru.kolco24.kolco24.data.nfc.ChipCodes
import ru.kolco24.kolco24.data.nfc.ChipWriteResult
import ru.kolco24.kolco24.data.nfc.chipCodeFromHex

/**
 * Pure, Android-free model of writing the server-issued secret code onto a participant bracelet
 * (`K24` record, type `CHIP_TYPE_PARTICIPANT`), mirroring `ProvisioningModel.kt` — no Compose, no
 * Android, JVM-unit-testable. The stateful host ([MemberProvisioningScreen]) owns the member-tag pool,
 * the NFC hook, and the bind/write side effects; this only decides transitions and user-facing strings.
 *
 * Two modes in one flow: a bracelet whose UID is in the pool (or was written this session) binds with
 * `number = null` — the server knows the participant; an unknown UID (or a `404` on `null`) asks the
 * admin for the number first. The host holds the [android.nfc.Tag] through the bind, so a bracelet
 * kept on the phone is written on the same tap; a second tap is needed only when the write missed.
 */

/** State of the bracelet in progress. */
sealed interface MemberProvisionState {
    /** No bracelet in progress — «Приложите браслет участника». */
    data object WaitingForChip : MemberProvisionState

    /** [uid] is not in the pool (or the server answered `404`) — waiting for the participant number. */
    data class NeedsNumber(val uid: String) : MemberProvisionState

    /** `POST .../member_tags/bind/` in flight; [number] `null` = the server resolves it from the pool. */
    data class Binding(val uid: String, val number: Int?) : MemberProvisionState

    /** The code for participant [number] is being written onto bracelet [uid]. */
    data class Writing(val uid: String, val number: Int) : MemberProvisionState

    /** The server issued [codeHex] but the write missed — waiting for bracelet [uid] again; [hint] says so. */
    data class WaitingForWrite(
        val uid: String,
        val number: Int,
        val codeHex: String,
        val hint: String,
    ) : MemberProvisionState

    /** The code was written and verified: the bracelet now carries participant [number]'s code. */
    data class Success(val number: Int) : MemberProvisionState

    /** The bind or write failed; [reason] is the RU message for the scan zone. */
    data class Failed(val reason: String) : MemberProvisionState
}

/** One bracelet written this session (newest first in the feed). */
data class FreshBracelet(val uid: String, val number: Int)

/** Max bracelets kept in the session feed. */
const val MEMBER_FEED_CAP = 20

const val MEMBER_WRITE_AGAIN_HINT = "Приложите браслет ещё раз"
const val MEMBER_WRITE_RETRY_HINT = "Не удалось записать, приложите снова"
const val MEMBER_SAME_BRACELET_HINT = "Приложите тот же браслет"

/** First-phase routing of a tap, decided from the state alone (before any chip I/O). */
sealed interface MemberTapRoute {
    /** Busy (bind/write in flight) or the same unknown bracelet re-tapped while asking for its number. */
    data object Ignore : MemberTapRoute

    /** Read the chip and [classifyMemberTap] it. */
    data object Classify : MemberTapRoute

    /** The pending bracelet is back: write [pending]'s code onto it. */
    data class Write(val pending: MemberProvisionState.WaitingForWrite) : MemberTapRoute

    /** Another bracelet while one is pending a write — keep waiting, show [state]'s hint. */
    data class Hint(val state: MemberProvisionState.WaitingForWrite) : MemberTapRoute
}

fun routeMemberTap(state: MemberProvisionState, uid: String): MemberTapRoute = when (state) {
    is MemberProvisionState.Binding, is MemberProvisionState.Writing -> MemberTapRoute.Ignore
    is MemberProvisionState.WaitingForWrite ->
        if (uid == state.uid) {
            MemberTapRoute.Write(state)
        } else {
            MemberTapRoute.Hint(state.copy(hint = MEMBER_SAME_BRACELET_HINT))
        }
    is MemberProvisionState.NeedsNumber ->
        if (uid == state.uid) MemberTapRoute.Ignore else MemberTapRoute.Classify
    MemberProvisionState.WaitingForChip,
    is MemberProvisionState.Success,
    is MemberProvisionState.Failed,
    -> MemberTapRoute.Classify
}

/** Second-phase decision for a [MemberTapRoute.Classify] tap. */
sealed interface MemberTapAction {
    data class Fail(val reason: String) : MemberTapAction
    data class AskNumber(val uid: String) : MemberTapAction

    /** Bind with `number = null` — the server knows the participant. */
    data class BindKnown(val uid: String) : MemberTapAction
}

/**
 * Decide a classified tap from the chip read ([codes] `null` = the read failed) and whether [known]
 * (pool ∪ this session's feed). A КП chip is refused before any bind — binding it would also make the
 * write zero its КП header. Pure.
 */
fun classifyMemberTap(uid: String, codes: ChipCodes?, known: Boolean): MemberTapAction = when {
    codes == null -> MemberTapAction.Fail("Не удалось прочитать, приложите снова")
    codes.code != null -> MemberTapAction.Fail("Это чип КП, а не браслет")
    known -> MemberTapAction.BindKnown(uid)
    else -> MemberTapAction.AskNumber(uid)
}

/** Whether [uid] resolves to a participant without asking: it is in the [pool] or the session [feed]. */
fun isKnownBracelet(uid: String, pool: List<MemberTagEntity>, feed: List<FreshBracelet>): Boolean =
    pool.any { it.nfcUid == uid } || feed.any { it.uid == uid }

/**
 * State after a non-success, non-`401` bind [result] for [uid] requested with [number]. A `404` on a
 * `null`-number request means the server doesn't know the UID → ask for the number (no error cue).
 */
fun memberBindFailureState(uid: String, number: Int?, result: PostResult<*>): MemberProvisionState =
    if (number == null && result == PostResult.Error(404)) {
        MemberProvisionState.NeedsNumber(uid)
    } else {
        MemberProvisionState.Failed(memberProvisionErrorMessage(result))
    }

/**
 * RU message for a non-success bind [result]. Differs from [provisionErrorMessage] (КП) on `409` (this
 * UID is bound to another participant) and `404` (reaches here only on a request with a number).
 */
fun memberProvisionErrorMessage(result: PostResult<*>): String = when {
    result == PostResult.Conflict -> "Браслет уже привязан к другому участнику"
    result == PostResult.Error(404) -> "Не найдено на сервере"
    else -> provisionErrorMessage(result)
}

/** The 16-byte code from the server's hex, or null when it is malformed (wrong length or not hex). */
fun parseServerChipCode(hex: String): ByteArray? {
    if (hex.length != CHIP_CODE_BYTES * 2) return null
    return try {
        chipCodeFromHex(hex)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * State after writing [codeHex] for participant [number] onto bracelet [uid]. A refused chip of
 * another type fails for good; any other failure keeps the code pending with [retryHint].
 */
fun memberWriteOutcome(
    uid: String,
    number: Int,
    codeHex: String,
    result: ChipWriteResult,
    retryHint: String,
): MemberProvisionState = when (result) {
    ChipWriteResult.Success -> MemberProvisionState.Success(number)
    is ChipWriteResult.WrongType -> MemberProvisionState.Failed(result.reason)
    else -> MemberProvisionState.WaitingForWrite(uid, number, codeHex, retryHint)
}

/** Put ([uid], [number]) on top of [feed], replacing an older entry for the same uid; capped. */
fun addFreshBracelet(feed: List<FreshBracelet>, uid: String, number: Int): List<FreshBracelet> =
    (listOf(FreshBracelet(uid, number)) + feed.filter { it.uid != uid }).take(MEMBER_FEED_CAP)

/** The number to prefill after writing [number] (auto-increment), or null on overflow. */
fun nextMemberNumber(number: Int): Int? = if (number == Int.MAX_VALUE) null else number + 1

/**
 * Parse the number field: ASCII digits only (surrounding spaces trimmed), leading zeros allowed;
 * empty, `0`, a sign, a non-number, or an `Int` overflow → null.
 */
fun parseMemberNumber(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
    return trimmed.toIntOrNull()?.takeIf { it >= 1 }
}
