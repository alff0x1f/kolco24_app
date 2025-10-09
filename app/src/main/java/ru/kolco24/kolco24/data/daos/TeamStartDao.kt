package ru.kolco24.kolco24.data.daos

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ru.kolco24.kolco24.data.entities.TeamStart

@Dao
interface TeamStartDao {
    @Insert
    fun insert(event: TeamStart): Long

    @Update
    fun update(event: TeamStart)

    @Query("SELECT * FROM team_starts ORDER BY startTimestamp DESC")
    fun getAll(): LiveData<List<TeamStart>>

    @Query("SELECT * FROM team_starts WHERE isSyncLocal = 0 ORDER BY startTimestamp ASC")
    fun getPendingLocal(): List<TeamStart>

    @Query("SELECT * FROM team_starts WHERE isSync = 0 ORDER BY startTimestamp ASC")
    fun getPendingRemote(): List<TeamStart>

    @Query("UPDATE team_starts SET isSyncLocal = :synced WHERE id = :id")
    fun markLocalSynced(id: Int, synced: Boolean)

    @Query("UPDATE team_starts SET isSync = :synced WHERE id = :id")
    fun markRemoteSynced(id: Int, synced: Boolean)

    @Query("DELETE FROM team_starts")
    fun deleteAll()
}
