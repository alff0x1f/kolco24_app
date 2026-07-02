package ru.kolco24.kolco24.ui.admin

import ru.kolco24.kolco24.data.db.MemberTagEntity

/**
 * Pure, Android-free model of the judge start/finish scan flow, mirroring
 * `MemberChipCheckModel.kt` — no Compose, no Android, fully JVM-unit-testable. The stateful host
 * (`JudgeScanScreen`) owns the NFC hook and the impure reads (the `observeForRace` pool Flow,
 * `readChipCode` on the not-in-pool branch, whether the pool has ever synced); this only decides
 * the result from the already-resolved values.
 */

/**
 * The outcome of a single judge pik against the current race's member-tag pool.
 *
 * - [Recorded] — the chip's UID is in the pool; a row should be written.
 * - [UnknownChip] — the UID is not in the pool and no `K24` code was read: a foreign card, a blank
 *   chip, or a stale pool.
 * - [KpChip] — the UID is not in the pool but a `K24` code was read: the judge tapped a checkpoint
 *   chip instead of a participant bracelet.
 * - [PoolNotReady] — the race's member-tag pool hasn't synced yet; scans are rejected outright
 *   regardless of what the UID would otherwise match.
 */
sealed interface JudgeScanResult {
    data class Recorded(val uid: String, val number: Int) : JudgeScanResult
    data class UnknownChip(val uid: String) : JudgeScanResult
    object KpChip : JudgeScanResult
    object PoolNotReady : JudgeScanResult
}

/**
 * Decides the judge-scan result from values the host has already resolved. Pure — no I/O, no Flow
 * reads.
 *
 * @param uid the scanned chip's normalized UID.
 * @param memberTag `pool.firstOrNull { it.nfcUid == uid }` — null when the UID is not in the pool;
 *   only consulted when [poolReady].
 * @param hasKpCode whether `readChipCode` returned a code for this chip; only consulted on the
 *   not-in-pool branch.
 * @param poolReady whether the race's member-tag pool has synced; checked first — a scan while the
 *   pool isn't ready is rejected even when [memberTag] would otherwise match.
 *
 * Branch order: `!poolReady` → [JudgeScanResult.PoolNotReady]; `memberTag != null` →
 * [JudgeScanResult.Recorded]; `hasKpCode` → [JudgeScanResult.KpChip]; else
 * [JudgeScanResult.UnknownChip].
 */
fun classifyJudgeScan(
    uid: String,
    memberTag: MemberTagEntity?,
    hasKpCode: Boolean,
    poolReady: Boolean,
): JudgeScanResult = when {
    !poolReady -> JudgeScanResult.PoolNotReady
    memberTag != null -> JudgeScanResult.Recorded(uid = uid, number = memberTag.number)
    hasKpCode -> JudgeScanResult.KpChip
    else -> JudgeScanResult.UnknownChip(uid)
}
