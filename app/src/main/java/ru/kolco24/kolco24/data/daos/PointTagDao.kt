package ru.kolco24.kolco24.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.kolco24.kolco24.data.entities.CheckpointTag

@Dao
interface PointTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPointTag(checkpointTag: CheckpointTag)

    @Query("SELECT * FROM point_tags WHERE id = :id")
    fun getPointTagById(id: Int): CheckpointTag?

    @Query("SELECT * FROM point_tags WHERE tagUID = :tagUID")
    fun getPointTagByUID(tagUID: String): CheckpointTag?

    @Query("SELECT * FROM point_tags WHERE checkpointId = :pointId")
    suspend fun getPointTagsByPointId(pointId: Int): List<CheckpointTag>

    @Query("DELETE FROM point_tags WHERE checkpointId = :pointId")
    suspend fun deletePointTagsByPointId(pointId: Int)

    @Query("DELETE FROM point_tags WHERE checkpointId = :pointId AND tagUID = :tag")
    suspend fun deletePointTag(pointId: Int, tag: String)

    @Query("DELETE FROM point_tags")
    fun deleteAllPointTags()
}
