package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * A **local-only** binding of a physical NFC bracelet to one member slot of a selected team. The slot
 * is identified by `(teamId, numberInTeam)` because a team member exposes no stable participant id —
 * only a name and a `number_in_team` (see the binding-key assumption in the plan / CLAUDE.md).
 *
 * [nfcUid] is the normalized uid read off the chip; [participantNumber] is resolved from the race's
 * `member_tags` pool at bind time and stored so a row renders without a pool lookup. The [nfcUid]
 * index supports the duplicate check ("is this chip already bound to another slot?"). This table is
 * never uploaded to the backend.
 */
@Entity(
    tableName = "member_chip_bindings",
    primaryKeys = ["teamId", "numberInTeam"],
    indices = [Index("nfcUid")],
)
data class MemberChipBindingEntity(
    val teamId: Int,
    val numberInTeam: Int,
    val nfcUid: String,
    val participantNumber: Int,
)
