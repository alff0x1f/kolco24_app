package ru.kolco24.kolco24.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Stores `teams.members` as a JSON string. kotlinx.serialization keeps element order stable, so a
 * round-trip preserves the member list as received.
 */
class TeamMembersConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromJson(value: String): List<TeamMemberItem> = try {
        json.decodeFromString(value)
    } catch (_: SerializationException) {
        emptyList()
    }

    @TypeConverter
    fun toJson(members: List<TeamMemberItem>): String = json.encodeToString(members)
}
