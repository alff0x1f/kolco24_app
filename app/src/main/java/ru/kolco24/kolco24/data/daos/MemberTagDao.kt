package ru.kolco24.kolco24.data.daos

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ru.kolco24.kolco24.data.entities.MemberTag

@Dao
interface MemberTagDao {
    @Insert
    fun insertMemberTag(memberTag: MemberTag)

    @Update
    suspend fun updateMemberTag(memberTag: MemberTag)

    @Query("SELECT * FROM MemberTag WHERE id = :id")
    fun getMemberTagById(id: Int): MemberTag?

    @Query("SELECT * FROM MemberTag WHERE tag = :tagId")
    fun getMemberTagByTagId(tagId: String): MemberTag?

    @Query("SELECT * FROM MemberTag")
    fun getAllMemberTags(): List<MemberTag>

    @Query("SELECT * FROM MemberTag ORDER BY id DESC LIMIT 50")
    fun getAllMemberTagsLiveData(): LiveData<List<MemberTag>>

    @Query("DELETE FROM MemberTag WHERE id = :id")
    suspend fun deleteMemberTagById(id: Int)

    @Query("DELETE FROM MemberTag")
    fun deleteAllMemberTags()
}
