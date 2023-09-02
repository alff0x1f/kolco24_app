package ru.kolco24.kolco24.db.entity

import androidx.room.Entity

@Entity(tableName = "points")
data class PointEntity(
    val id: Int,
    val number: Number,
    val description: String,
    val encryptedDescription: String,
    val cost: Int,
)
