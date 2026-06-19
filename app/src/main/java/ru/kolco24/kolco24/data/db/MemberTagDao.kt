package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberTagDao {
    @Query("SELECT * FROM member_tags WHERE raceId = :raceId ORDER BY number, nfcUid")
    fun observeForRace(raceId: Int): Flow<List<MemberTagEntity>>

    @Query("SELECT * FROM member_tags WHERE raceId = :raceId AND nfcUid = :nfcUid")
    suspend fun findByUid(raceId: Int, nfcUid: String): MemberTagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<MemberTagEntity>)

    @Query("DELETE FROM member_tags WHERE raceId = :raceId")
    suspend fun deleteForRace(raceId: Int)

    /** Full replacement of one race's member-tag pool on a `200`. */
    @Transaction
    suspend fun replaceAllForRace(raceId: Int, tags: List<MemberTagEntity>) {
        deleteForRace(raceId)
        insertAll(tags)
    }
}
