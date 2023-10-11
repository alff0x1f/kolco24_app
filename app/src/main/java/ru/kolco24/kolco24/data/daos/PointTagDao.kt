package ru.kolco24.kolco24.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.kolco24.kolco24.data.entities.PointTag

@Dao
interface PointTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPointTag(pointTag: PointTag)

    @Query("SELECT * FROM point_tags WHERE pointId = :pointId")
    suspend fun getPointTagsByPointId(pointId: Int): List<PointTag>

    @Query("DELETE FROM point_tags WHERE pointId = :pointId")
    suspend fun deletePointTagsByPointId(pointId: Int)

    @Query("DELETE FROM point_tags WHERE pointId = :pointId AND tag = :tag")
    suspend fun deletePointTag(pointId: Int, tag: String)

    @Query("DELETE FROM point_tags")
    fun deleteAllPointTags()
}
