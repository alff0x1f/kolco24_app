package ru.kolco24.kolco24.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.db.entity.PointEntity

@Dao
interface PointDao {

    @Query("SELECT * FROM points ORDER BY number ASC")
    fun getPoints(): Flow<List<PointEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pointEntity: PointEntity)

    @Query("DELETE FROM points")
    suspend fun deleteAll()

}