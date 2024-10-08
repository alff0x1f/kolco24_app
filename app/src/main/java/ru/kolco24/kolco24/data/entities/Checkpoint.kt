package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

@Entity(tableName = "points")
class Checkpoint(
    var number: Int,
    var description: String,
    var cost: Int,
    var type: String,
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    class PointExt(
        var id: Int,
        var number: Int,
        var description: String,
        var cost: Int,
        var photoTime: String?,
        var time: Long?,
    )

    companion object {
        /*
        {
            "id": 152,
            "number": 54,
            "description": "Описание 54",
            "cost": 1,
            "type": "1d8f550e960000",
            "tags": [
              "1d8f550e960000",
              "1d90550e960000"
            ]
          }
         */
        @JvmStatic
        fun fromJson(point: JSONObject): Checkpoint {
            val id = point.getInt("id")
            val number = point.getInt("number")
            val description = point.getString("description")
            val cost = point.getInt("cost")
            val type = point.getString("type")
            return Checkpoint(number, description, cost, type).apply { this.id = id }
        }
    }
}
