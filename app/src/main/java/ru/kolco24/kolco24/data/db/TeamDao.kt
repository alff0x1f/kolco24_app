package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TeamDao {
    @Query("SELECT * FROM teams WHERE raceId = :raceId ORDER BY (startNumber IS NULL OR startNumber = ''), CAST(NULLIF(startNumber, '') AS INTEGER), startNumber, id")
    fun observeTeamsForRace(raceId: Int): Flow<List<TeamEntity>>

    @Query("SELECT * FROM categories WHERE raceId = :raceId ORDER BY sortOrder, id")
    fun observeCategoriesForRace(raceId: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM teams WHERE id = :teamId")
    fun observeTeamById(teamId: Int): Flow<TeamEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTeams(teams: List<TeamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM teams WHERE raceId = :raceId")
    suspend fun deleteTeamsForRace(raceId: Int)

    @Query("DELETE FROM categories WHERE raceId = :raceId")
    suspend fun deleteCategoriesForRace(raceId: Int)

    /** Full replacement of one race's teams + categories on a `200`: wipe then re-insert, atomically. */
    @Transaction
    suspend fun replaceAllForRace(
        raceId: Int,
        categories: List<CategoryEntity>,
        teams: List<TeamEntity>,
    ) {
        deleteTeamsForRace(raceId)
        deleteCategoriesForRace(raceId)
        insertCategories(categories)
        insertTeams(teams)
    }
}
