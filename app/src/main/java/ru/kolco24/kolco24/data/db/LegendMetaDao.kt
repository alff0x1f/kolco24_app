package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LegendMetaDao {
    @Query("SELECT * FROM legend_meta WHERE raceId = :raceId")
    fun observeForRace(raceId: Int): Flow<LegendMetaEntity?>

    @Upsert
    suspend fun upsert(meta: LegendMetaEntity)
}
