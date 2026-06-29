package ru.kolco24.kolco24.data.db

import android.util.Log
import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Stores a nullable `List<MarkMemberSnapshot>` (`marks.presentDetails`) as a JSON string. Mirrors
 * [IntListConverter] but is **nullable** (NULL ↔ null) so a legacy row with no snapshot stays NULL.
 * kotlinx.serialization keeps element order stable, so a round-trip preserves the list as stored.
 */
class MarkMemberSnapshotListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromJson(value: String?): List<MarkMemberSnapshot>? {
        if (value == null) return null
        return try {
            json.decodeFromString(value)
        } catch (e: SerializationException) {
            Log.e("MarkSnapshotConverter", "Failed to decode snapshot list JSON", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e("MarkSnapshotConverter", "Failed to decode snapshot list JSON", e)
            null
        }
    }

    @TypeConverter
    fun toJson(values: List<MarkMemberSnapshot>?): String? =
        values?.let { json.encodeToString(it) }
}
