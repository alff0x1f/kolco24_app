package ru.kolco24.kolco24.ui.admin

import ru.kolco24.kolco24.data.db.MemberTagEntity

/**
 * Pure, Android-free model of the read-only member-bracelet verification flow («Проверка браслетов
 * участников»), mirroring `ChipCheckModel.kt` — no Compose, no Android, fully JVM-unit-testable.
 * The stateful host ([CheckMemberChipScreen]) owns the NFC hook and the impure reads (the
 * `poolForRace` Flow collection, `readChipCodes`); this only decides the result from the
 * already-resolved values.
 *
 * Identity-only and fully offline: a bracelet is verified by matching its normalized UID against the
 * synced per-race member-tag pool ([MemberTagEntity]) — no server round-trip. The on-chip read tells a
 * mis-tapped КП chip apart from a genuinely unknown bracelet, and shows whether a pooled bracelet
 * already carries its participant `K24` code (written from «Записать браслет участника»).
 */

/**
 * The outcome of verifying one scanned chip against the current race's member-tag pool.
 *
 * - [Ok] — the chip's UID is in the pool; [Ok.number] is the participant number, [Ok.hasCode] whether
 *   the bracelet carries a participant code (`null` — the pages could not be read, so unknown).
 * - [KpChip] — the UID is not in the pool but a `K24` code was read: this is a КП chip, not a
 *   bracelet (the admin tapped the wrong chip type).
 * - [Unknown] — the UID is not in the pool and no code was read: a foreign-race bracelet, a blank
 *   chip, or the pool list is stale.
 */
sealed interface MemberChipCheckResult {
    /** The scanned chip's normalized UID (uppercase hex), present on every variant. */
    val uid: String

    /**
     * The bracelet belongs to participant [number] of this race; [hasCode] = a participant code is
     * written, `null` = the chip read failed (a brief tap), so presence is unknown.
     */
    data class Ok(
        override val uid: String,
        val number: Int,
        val hasCode: Boolean? = false,
    ) : MemberChipCheckResult

    /** Not in the pool, but a КП code was read — a КП chip, not a participant bracelet. */
    data class KpChip(
        override val uid: String,
    ) : MemberChipCheckResult

    /** Not in the pool and no КП code — foreign-race bracelet, blank chip, or a stale pool. */
    data class Unknown(
        override val uid: String,
    ) : MemberChipCheckResult
}

/**
 * Decides the verification result from values the host has already resolved against the collected
 * pool. Pure — no I/O, no Flow reads.
 *
 * @param uid the scanned chip's normalized UID.
 * @param memberTag `pool.firstOrNull { it.nfcUid == uid }` — null when the UID is not in the pool.
 * @param hasKpCode whether the chip carries a КП code; only consulted on the not-in-pool branch (a
 *   pooled UID wins even if the chip somehow also carries a code — the server-synced pool is
 *   authoritative).
 * @param hasMemberCode whether the chip carries a participant code, `null` when the read failed;
 *   surfaced on [MemberChipCheckResult.Ok].
 *
 * Branch order: `memberTag != null` → [MemberChipCheckResult.Ok]; `hasKpCode` →
 * [MemberChipCheckResult.KpChip]; else [MemberChipCheckResult.Unknown].
 */
fun classifyMemberChipCheck(
    uid: String,
    memberTag: MemberTagEntity?,
    hasKpCode: Boolean,
    hasMemberCode: Boolean? = false,
): MemberChipCheckResult = when {
    memberTag != null -> MemberChipCheckResult.Ok(uid = uid, number = memberTag.number, hasCode = hasMemberCode)
    hasKpCode -> MemberChipCheckResult.KpChip(uid)
    else -> MemberChipCheckResult.Unknown(uid)
}
