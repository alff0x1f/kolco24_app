package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "MemberTag")
data class MemberTag(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val number: Int,
    val tagId: String
)
