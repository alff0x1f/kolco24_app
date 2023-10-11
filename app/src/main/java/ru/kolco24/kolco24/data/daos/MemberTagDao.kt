package ru.kolco24.kolco24.data.daos;

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ru.kolco24.kolco24.data.entities.MemberTag

@Dao
interface MemberTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemberTag(memberTag: MemberTag)

    @Update
    suspend fun updateMemberTag(memberTag: MemberTag)

    @Query("SELECT * FROM MemberTag WHERE id = :id")
    suspend fun getMemberTagById(id: Int): MemberTag?

    @Query("SELECT * FROM MemberTag")
    suspend fun getAllMemberTags(): List<MemberTag>

    @Query("DELETE FROM MemberTag WHERE id = :id")
    suspend fun deleteMemberTagById(id: Int)

    @Query("DELETE FROM MemberTag")
    fun deleteAllMemberTags()
}
