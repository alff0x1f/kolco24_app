package ru.kolco24.kolco24.data.db

import android.util.Log
import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Stores a `List<Int>` (e.g. `marks.present`) as a JSON string. Mirrors [TeamMembersConverter];
 * kotlinx.serialization keeps element order stable, so a round-trip preserves the list as stored.
 */
class IntListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromJson(value: String): List<Int> = try {
        json.decodeFromString(value)
    } catch (e: SerializationException) {
        Log.e("IntListConverter", "Failed to decode Int list JSON", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.e("IntListConverter", "Failed to decode Int list JSON", e)
        emptyList()
    }

    @TypeConverter
    fun toJson(values: List<Int>): String = json.encodeToString(values)
}
