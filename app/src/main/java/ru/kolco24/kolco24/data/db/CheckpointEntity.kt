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
 * `cost`/`description` directly with no `enc` and `locked = false`. After an offline reveal,
 * [CheckpointDao.reveal] clears [locked] to `false` (so `locked` always agrees with `cost == null`).
 *
 * There is no `taken` flag here: "взято" is **team-scoped** (it depends on which team's marks are
 * complete), while a checkpoint row is **race-scoped** and shared across that race's teams. Taken
 * state is therefore derived from the selected team's complete marks (see [ru.kolco24.kolco24.data.takenPoints]),
 * not persisted on the checkpoint — persisting it here leaked one team's progress onto another's.
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
)
