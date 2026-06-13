package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level payload of `GET /app/race/<race_id>/teams/` (see docs/design/API.md). */
@Serializable
data class TeamsResponse(
    val race: Int,
    val categories: List<CategoryDto>,
    val teams: List<TeamDto>,
)

/** A race category (e.g. distance/group). `order` is a reserved SQL word, hence the @SerialName mapping. */
@Serializable
data class CategoryDto(
    val id: Int,
    val code: String,
    @SerialName("short_name") val shortName: String,
    val name: String,
    val order: Int,
)

/**
 * A registered team. `start_number` is nullable with a default: the backend added it recently and
 * it is not yet documented in API.md, so we guard against its absence (old format) as well as the
 * Django `default=""` empty-string case.
 */
@Serializable
data class TeamDto(
    val id: Int,
    val teamname: String,
    @SerialName("start_number") val startNumber: String? = null,
    val category2: Int?,
    val ucount: Int,
    @SerialName("paid_people") val paidPeople: Double,
    @SerialName("start_time") val startTime: Long,
    @SerialName("finish_time") val finishTime: Long,
    val members: List<MemberDto>,
)

/** A single team member. */
@Serializable
data class MemberDto(
    val name: String,
    @SerialName("number_in_team") val numberInTeam: Int,
)
