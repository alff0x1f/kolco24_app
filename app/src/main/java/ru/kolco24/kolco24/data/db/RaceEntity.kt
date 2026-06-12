package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single race row. This entity doubles as the app's model — there is no separate domain layer.
 * Dates and [regStatus] are kept as strings exactly as received from the server (forward-compatible).
 * The primary key [id] is the server-assigned id.
 */
@Entity(tableName = "races")
data class RaceEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val slug: String,
    val date: String,
    val dateEnd: String?,
    val place: String,
    val regStatus: String,
    val isLegendVisible: Boolean,
)
