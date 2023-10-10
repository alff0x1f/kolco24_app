package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "points")
class Point(
    var number: Int,
    var description: String,
    var cost: Int
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    class PointExt(
        var id: Int,
        var number: Int,
        var description: String,
        var cost: Int,
        var photoTime: String?,
        var nfcTime: String?
    )
}
