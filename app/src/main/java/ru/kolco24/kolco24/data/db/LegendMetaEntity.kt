package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Race-level aggregate of the legend that has no per-checkpoint home. Currently just [totalCost] —
 * the sum of **every** checkpoint's `cost` (open + locked) as reported by the server in the legend
 * response's top-level `total_cost` field.
 *
 * It exists because a **locked** checkpoint hides its individual `cost` (the plaintext never leaves
 * the server), so the client cannot compute the full denominator for the legend progress bar from
 * [CheckpointEntity] rows alone. The server-supplied aggregate is the only correct denominator; this
 * row persists it (one per race, keyed by [raceId]) so the legend reads it offline like the rest of
 * the data. Written by `LegendRepository.refreshLegend` alongside the checkpoints/tags.
 */
@Entity(tableName = "legend_meta")
data class LegendMetaEntity(
    @PrimaryKey val raceId: Int,
    val totalCost: Int,
)
