package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "team_starts",
    indices = [Index(value = ["teamId"]), Index(value = ["isSync"])]
)
data class TeamStart(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val teamId: Int,
    val startNumber: String,
    val teamName: String,
    val participantCount: Int,
    val scannedCount: Int,
    val memberTags: String,
    val startTimestamp: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isSync: Boolean = false
)
