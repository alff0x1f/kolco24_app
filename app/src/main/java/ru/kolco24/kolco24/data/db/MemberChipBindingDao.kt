package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberChipBindingDao {
    @Query("SELECT * FROM member_chip_bindings WHERE teamId = :teamId ORDER BY numberInTeam")
    fun observeForTeam(teamId: Int): Flow<List<MemberChipBindingEntity>>

    @Query("SELECT * FROM member_chip_bindings WHERE nfcUid = :nfcUid")
    suspend fun findByUid(nfcUid: String): MemberChipBindingEntity?

    @Upsert
    suspend fun upsert(binding: MemberChipBindingEntity)

    @Query("DELETE FROM member_chip_bindings WHERE teamId = :teamId AND numberInTeam = :numberInTeam")
    suspend fun deleteSlot(teamId: Int, numberInTeam: Int)

    @Query("DELETE FROM member_chip_bindings WHERE nfcUid = :nfcUid")
    suspend fun deleteByUid(nfcUid: String)

    /**
     * Atomically move a chip onto a new slot: drop any slot currently holding [MemberChipBindingEntity.nfcUid]
     * then write [binding], so a chip is never momentarily on two slots.
     */
    @Transaction
    suspend fun reassign(binding: MemberChipBindingEntity) {
        deleteByUid(binding.nfcUid)
        upsert(binding)
    }
}
