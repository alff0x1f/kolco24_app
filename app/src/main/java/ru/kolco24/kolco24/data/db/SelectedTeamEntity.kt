package ru.kolco24.kolco24.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single currently-selected team. A one-row table (fixed PK [id] = 1) whose Flow drives the
 * «Команда» tab between its empty state and the selected team's roster.
 */
@Entity(tableName = "selected_team")
data class SelectedTeamEntity(
    @PrimaryKey val id: Int = 1,
    val raceId: Int,
    val teamId: Int,
)
