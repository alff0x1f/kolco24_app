package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

@Entity(tableName = "MemberTag")
data class MemberTag(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val number: Int,
    val tagId: String
) {
    companion object {
        /*
          [
              {
                "id": 1,
                "number": 1050,
                "tag_id": "045D7B32F31C90"
              },
              {
                "id": 2,
                "number": 1098,
                "tag_id": "04036F32F31C91"
              }
          ]
         */
        @JvmStatic
        fun fromJson(point: JSONObject): MemberTag {
            val id = point.getInt("id")
            val number = point.getInt("number")
            val tagId = point.getString("tag_id")
            return MemberTag(id, number, tagId)
        }
    }
}
