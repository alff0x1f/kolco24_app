package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "point_tags",
    foreignKeys = [ForeignKey(
        entity = Point::class,
        parentColumns = ["id"],
        childColumns = ["pointId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PointTag(
    var pointId: Int = 0,
    var tag: String
){
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
}