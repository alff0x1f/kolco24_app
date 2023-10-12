package ru.kolco24.kolco24.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import org.json.JSONException
import org.json.JSONObject
import ru.kolco24.kolco24.data.Converters

@Entity(tableName = "teams")
class Team(
    @PrimaryKey(autoGenerate = true)
    var id: Int,
    var owner: String,
    var paidPeople: Float, // время 6h, 12h, 24h
    var dist: String, // категория 6h, 12h_mm, 12h_mw, 12h_team, 24h etc
    var category: String,
    var teamname: String,
    var city: String,
    var organization: String,
    var year: String,
    var startNumber: String,
    @TypeConverters(Converters::class)
    var startTime: Long,
    @TypeConverters(Converters::class)
    var finishTime: Long,
    var isDnf: Boolean,
    var penalty: Int,
    var place: Int = 0,
    var pointsSum: Int = 0,
    var points: String = ""
) {

    /**
     * Сравнивает две команды
     *
     * @param obj - объект для сравнения
     * @return true, если команды одинаковые, иначе false
     */
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as Team
        return id == other.id && java.lang.Float.compare(
            paidPeople,
            other.paidPeople
        ) == 0 && dist == other.dist && this.category == other.category && teamname == other.teamname && city == other.city && organization == other.organization && year == other.year && startNumber == other.startNumber
    }

    override fun hashCode(): Int {
        return id
    }

    companion object {
        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(jsonObject: JSONObject): Team {
            val id = jsonObject.getInt("id")
            val paidPeople = jsonObject.optDouble("paid_people", 0.0).toFloat()
            val dist = jsonObject.optString("dist", "")
            val category = jsonObject.optString("category", "")
            val teamname = jsonObject.optString("teamname", "")
            val city = jsonObject.optString("city", "")
            val organization = jsonObject.optString("organization", "")
            val year = jsonObject.optString("year", "")
            val startNumber = jsonObject.optString("start_number", "")
            return Team(
                id, "", paidPeople, dist, category, teamname, city, organization,
                year, startNumber, 0L, 0L, false, 0
            )
        }
    }
}
