package ru.kolco24.kolco24.data.daos;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Team;

@Dao
public interface TeamDao {
     @Query("SELECT * FROM teams")
     LiveData<List<Team>> getAllTeams();

     @Query("SELECT * FROM teams WHERE category = :category ORDER BY place, startNumber")
     LiveData<List<Team>> getTeamsByCategory(String category);

     @Query("SELECT * FROM teams WHERE id = :id")
     Team getTeamById(int id);

     @Query("SELECT teamname FROM teams WHERE id = :id")
     LiveData<String> getTeamName(int id);

     @Query("SELECT * FROM teams WHERE id = :id")
     LiveData<Team> getTeam(int id);

     @Query("SELECT startNumber FROM teams WHERE id = :id")
     int getTeamNumberById(int id);

     @Query("SELECT * FROM teams WHERE startNumber = :number")
     Team getTeamByStartNumber(String number);

     @Query("SELECT COUNT(*) FROM teams")
     int getTeamCount();

     @Insert
     void insert(Team team);

     @Update
     void update(Team team);

     @Query("DELETE FROM teams")
     void deleteAll();
}
