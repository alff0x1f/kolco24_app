package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A registered team. Belongs to a race via [raceId] (indexed for per-race queries). This entity
 * doubles as the app's model. [startNumber] is nullable (the backend field is recent and may be
 * absent or an empty string). [members] is persisted as a JSON column via [TeamMembersConverter].
 */
@Entity(
    tableName = "teams",
    indices = [Index("raceId")],
)
data class TeamEntity(
    @PrimaryKey val id: Int,
    val raceId: Int,
    val teamname: String,
    val startNumber: String?,
    val categoryId: Int?,
    val ucount: Int,
    val paidPeople: Double,
    val startTime: Long,
    val finishTime: Long,
    val members: List<TeamMemberItem>,
)
