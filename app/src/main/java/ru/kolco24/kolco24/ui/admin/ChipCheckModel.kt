package ru.kolco24.kolco24.ui.admin

import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.TagEntity
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.legend.parseCheckpointColor

/**
 * Pure, Android-free model of the read-only chip-verification flow («Проверка чипов КП»), mirroring
 * `ProvisioningModel.kt` / `ScanSession.kt` / `CheckpointColor.kt` — no Compose, no Android, fully
 * JVM-unit-testable. The stateful host ([CheckChipScreen]) owns the NFC hook and the impure reads
 * (`readChipCode`, the `tagsForRace`/`checkpointsForRace` Flow collection, `LegendCrypto.bid`); this
 * only decides the result from the already-resolved values.
 *
 * Identity-only and fully offline: a chip is verified by matching its derived `bid` against the
 * synced legend tags — no decrypt, no `reveal` side effect, no server round-trip.
 */

/**
 * The outcome of verifying one scanned chip against the current race's legend.
 *
 * - [Ok] — the chip's `bid` matched a tag whose checkpoint exists in the legend.
 * - [UnknownChip] — a code was read, but no tag with that `bid` exists in this race (foreign-race
 *   chip, or the legend list is stale).
 * - [Inconsistent] — a tag matched the `bid`, but its [ChipCheckResult.Inconsistent.pointId] has no checkpoint row (legend drift).
 * - [NoCode] — `readChipCode` returned null: a blank chip, a member bracelet, or a read error
 *   (collapsed into one case — the raw read can't distinguish them).
 */
sealed interface ChipCheckResult {
    /** The scanned chip's normalized UID (uppercase hex), present on every variant. */
    val uid: String

    /** The chip is correctly bound to an existing checkpoint. */
    data class Ok(
        override val uid: String,
        val number: Int,
        val cost: Int?,
        val color: CheckpointColor?,
        val bid: String,
        val checkMethod: String,
        val chipsOnKp: Int,
    ) : ChipCheckResult

    /** A code was read but no tag in this race has its [bid]. */
    data class UnknownChip(
        override val uid: String,
        val bid: String,
    ) : ChipCheckResult

    /** A tag matched [bid] but its [pointId] has no checkpoint row in the legend. */
    data class Inconsistent(
        override val uid: String,
        val bid: String,
        /** The checkpoint DB id (server surrogate key) — NOT the human-visible КП number. */
        val pointId: Int,
    ) : ChipCheckResult

    /** No КП code could be read from the chip (blank, bracelet, or read error). */
    data class NoCode(
        override val uid: String,
    ) : ChipCheckResult
}

/**
 * Decides the verification result from values the host has already resolved against the collected
 * legend lists. Pure — no I/O, no Flow reads.
 *
 * @param uid the scanned chip's normalized UID.
 * @param bid the chip's derived bid, or null when the code couldn't be read.
 * @param tag `tags.firstOrNull { it.bid == bid }` — null when no tag matches.
 * @param checkpoint `checkpointsById[tag.point]` — null when the tag's КП is missing from the legend.
 * @param chipsOnKp how many tags this race's legend has for the matched tag's checkpoint
 *   (`countsByPoint[tag.point]`); only meaningful on the [ChipCheckResult.Ok] path.
 *
 * Branch order: `bid == null` → [ChipCheckResult.NoCode]; `tag == null` → [ChipCheckResult.UnknownChip];
 * `checkpoint == null` → [ChipCheckResult.Inconsistent]; else [ChipCheckResult.Ok].
 */
/**
 * Positions in [uid] whose hex nibble differs from [previous] at the same index — the digits that
 * changed since the previous scan. A position past the end of [previous] counts as changed (a longer
 * uid). Returns an empty set when [previous] is null or blank (no baseline to diff against, e.g. the
 * first chip of a session) so the host renders the uid plain instead of fully dimmed.
 *
 * Compared by raw nibble index, before any byte-pair grouping the host adds for display. Pure.
 */
fun changedNibbles(uid: String, previous: String?): Set<Int> {
    if (previous.isNullOrEmpty()) return emptySet()
    val out = mutableSetOf<Int>()
    for (i in uid.indices) {
        if (i >= previous.length || uid[i] != previous[i]) out += i
    }
    return out
}

fun classifyChipCheck(
    uid: String,
    bid: String?,
    tag: TagEntity?,
    checkpoint: CheckpointEntity?,
    chipsOnKp: Int,
): ChipCheckResult = when {
    bid == null -> ChipCheckResult.NoCode(uid)
    tag == null -> ChipCheckResult.UnknownChip(uid, bid)
    checkpoint == null -> ChipCheckResult.Inconsistent(uid = uid, bid = bid, pointId = tag.point)
    else -> ChipCheckResult.Ok(
        uid = uid,
        number = checkpoint.number,
        cost = checkpoint.cost,
        color = parseCheckpointColor(checkpoint.color),
        bid = bid,
        checkMethod = tag.checkMethod,
        chipsOnKp = chipsOnKp,
    )
}
