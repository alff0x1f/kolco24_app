package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "MemberTag")
data class MemberTag(
    val siteId: Int? = null,
    val tag: String,
    val teamId: Int? = null,
    val name: String = "",
){
    @PrimaryKey(autoGenerate = true)
    var id = 0
}
