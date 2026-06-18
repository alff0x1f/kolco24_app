package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE raceId = :raceId ORDER BY point, bid")
    fun observeTagsForRace(raceId: Int): Flow<List<TagEntity>>

    /** Looks up a scanned tag by its derived [bid] within [raceId]; `null` when the tag is unknown. */
    @Query("SELECT * FROM tags WHERE bid = :bid AND raceId = :raceId")
    suspend fun getByBid(bid: String, raceId: Int): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE raceId = :raceId")
    suspend fun deleteTagsForRace(raceId: Int)

    /** Full replacement of one race's tags on a `200`: wipe then re-insert, atomically. */
    @Transaction
    suspend fun replaceAllForRace(raceId: Int, tags: List<TagEntity>) {
        deleteTagsForRace(raceId)
        insertTags(tags)
    }
}
