package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level payload of `GET /app/races/` (see docs/API.md). */
@Serializable
data class RacesResponse(
    val races: List<RaceDto>,
)

/**
 * A single published race. Dates and `reg_status` are kept as strings exactly as received
 * (forward-compatible — new `reg_status` values won't break parsing).
 */
@Serializable
data class RaceDto(
    val id: Int,
    val name: String,
    val slug: String,
    val date: String,
    @SerialName("date_end") val dateEnd: String? = null,
    val place: String,
    @SerialName("reg_status") val regStatus: String,
)
