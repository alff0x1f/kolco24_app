package ru.kolco24.kolco24.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PhotoDao {
    @Insert
    void insert(Photo photo);

    @Update
    void update(Photo photo);

    @Query("SELECT * FROM photo_points WHERE id = :id")
    Photo getPhotoById(int id);

    @Query("SELECT * FROM photo_points " +
            "ORDER BY point_number")
    LiveData<List<Photo>> getAllPhotos();

    @Query("SELECT * FROM photo_points " +
            "WHERE team_id = :teamId " +
            "ORDER BY point_number")
    LiveData<List<Photo>> getPhotosByTeamId(int teamId);

    @Query("SELECT count(DISTINCT point_number) " +
            "FROM photo_points " +
            "WHERE team_id = :teamId")
    int getPhotoCount(int teamId);

    @Query("SELECT sum(points.cost) FROM points " +
            "LEFT JOIN (select point_number, team_id from photo_points group by point_number) photo " +
            "ON points.number = photo.point_number " +
            "WHERE photo.point_number IS NOT NULL AND photo.team_id = :teamId")
    int getCostSum(int teamId);

    @Query("DELETE FROM photo_points " +
            "WHERE id = :id")
    void deletePhotoPointById(int id);

    @Query("DELETE FROM photo_points")
    void deleteAll();
}
