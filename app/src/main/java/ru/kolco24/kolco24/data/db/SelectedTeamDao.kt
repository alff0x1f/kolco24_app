package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SelectedTeamDao {
    @Query("SELECT * FROM selected_team WHERE id = 1")
    fun observe(): Flow<SelectedTeamEntity?>

    @Upsert
    suspend fun upsert(selected: SelectedTeamEntity)
}
