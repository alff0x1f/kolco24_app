package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single checkpoint (КП) of a race's legend. This entity doubles as the app's model —
 * there is no separate domain layer. The primary key [id] is the server-assigned checkpoint id;
 * [raceId] (indexed) ties the row to its race so a race's legend can be replaced wholesale.
 *
 * The legend is now served with **per-checkpoint encryption**: a [locked] checkpoint arrives with
 * an `enc:{iv,ct}` envelope ([encIv]/[encCt]) **instead of** its [cost]/[description] (both nullable
 * — the plaintext only appears once the CP is unlocked offline). An open checkpoint carries its
 * `cost`/`description` directly with no `enc` and `locked = false`.
 *
 * [taken] is not part of the legend API (it comes from NFC marks, not built yet) — it defaults
 * to `false` and the future marks feature flips the data, not the schema.
 */
@Entity(tableName = "checkpoints", indices = [Index("raceId")])
data class CheckpointEntity(
    @PrimaryKey val id: Int,
    val raceId: Int,
    val number: Int,
    val cost: Int?,
    val type: String,
    val description: String?,
    val locked: Boolean = false,
    val encIv: String? = null,
    val encCt: String? = null,
    val taken: Boolean = false,
)
