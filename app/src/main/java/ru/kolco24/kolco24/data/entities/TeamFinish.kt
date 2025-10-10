package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "team_finish")
data class TeamFinish(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val memberTagId: Int,
    val tagUid: String,
    val recordedAt: Long = System.currentTimeMillis(),
    var isSyncLocal: Boolean = false,
    var isSyncRemote: Boolean = false
)
