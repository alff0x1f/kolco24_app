package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.Serializable

/** Top-level payload of `GET /app/race/<race_id>/legend/` (see docs/API.md). */
@Serializable
data class LegendResponse(
    val race: Int,
    val checkpoints: List<CheckpointDto>,
)

/**
 * A single legend checkpoint. Fields are flat (no snake_case), so no `@SerialName` is needed.
 * `type` ∈ `start|finish|test|kp` is kept as a plain string (forward-compatible — unknown types
 * won't break parsing, and there is no per-type styling yet).
 */
@Serializable
data class CheckpointDto(
    val id: Int,
    val number: Int,
    val cost: Int,
    val type: String,
    val description: String,
)
