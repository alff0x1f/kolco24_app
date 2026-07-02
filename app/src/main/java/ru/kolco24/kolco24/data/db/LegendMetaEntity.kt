package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Race-level aggregates of the legend that have no per-checkpoint home: [totalCost] (sum of
 * **every** checkpoint's `cost`, open + locked) and [scoringCount] (count of checkpoints with
 * `cost > 0`, open + locked) as reported by the server in the legend response's top-level
 * `total_cost`/`scoring_count` fields.
 *
 * They exist because a **locked** checkpoint hides its individual `cost` (the plaintext never
 * leaves the server), so the client cannot compute either aggregate from [CheckpointEntity] rows
 * alone. The server-supplied aggregates are the only correct denominators; this row persists them
 * (one per race, keyed by [raceId]) so the legend reads them offline like the rest of the data.
 * Written by `LegendRepository.refreshLegend` alongside the checkpoints/tags.
 */
@Entity(tableName = "legend_meta")
data class LegendMetaEntity(
    @PrimaryKey val raceId: Int,
    val totalCost: Int,
    val scoringCount: Int,
)
