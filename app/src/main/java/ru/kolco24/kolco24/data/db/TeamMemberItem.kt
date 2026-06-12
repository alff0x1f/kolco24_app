package ru.kolco24.kolco24.data.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A team member as stored inside the `teams.members` JSON column. Members are only ever read
 * together with their team, so they live as serialized JSON rather than a separate table.
 */
@Serializable
data class TeamMemberItem(
    val name: String,
    @SerialName("number_in_team") val numberInTeam: Int,
)
