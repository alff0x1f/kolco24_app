package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * A single NFC member-tag slot from a race's `member_tags` pool: a participant [number] paired with
 * the normalized [nfcUid] of the bracelet assigned to that participant. This entity doubles as the
 * app's model — there is no separate domain layer.
 *
 * The pool is modelled **per-race** ([raceId], indexed) to mirror [CheckpointEntity] and future-proof
 * for the backend's planned per-race tag pools, so a race's pool can be replaced wholesale. The
 * `member_tags` API carries no internal id — a slot is identified by its [nfcUid] — so the primary
 * key is the composite `(raceId, nfcUid)` (the same uid may legitimately appear in two races' pools).
 */
@Entity(
    tableName = "member_tags",
    primaryKeys = ["raceId", "nfcUid"],
    indices = [Index("raceId")],
)
data class MemberTagEntity(
    val raceId: Int,
    val nfcUid: String,
    val number: Int,
)
