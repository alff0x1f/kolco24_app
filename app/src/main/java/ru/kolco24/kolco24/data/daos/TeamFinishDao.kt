package ru.kolco24.kolco24.data.daos

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ru.kolco24.kolco24.data.entities.TeamFinish

@Dao
interface TeamFinishDao {
    @Insert
    fun insert(event: TeamFinish): Long

    @Update
    fun update(event: TeamFinish)

    @Query("SELECT * FROM team_finish ORDER BY recordedAt DESC")
    fun getAll(): LiveData<List<TeamFinish>>

    @Query("SELECT * FROM team_finish WHERE isSyncLocal = 0 ORDER BY recordedAt ASC")
    fun getPendingLocal(): List<TeamFinish>

    @Query("SELECT * FROM team_finish WHERE isSyncRemote = 0 ORDER BY recordedAt ASC")
    fun getPendingRemote(): List<TeamFinish>

    @Query("UPDATE team_finish SET isSyncLocal = :synced WHERE id = :id")
    fun markLocalSynced(id: Int, synced: Boolean)

    @Query("UPDATE team_finish SET isSyncRemote = :synced WHERE id = :id")
    fun markRemoteSynced(id: Int, synced: Boolean)
}
