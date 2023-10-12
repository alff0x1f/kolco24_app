package ru.kolco24.kolco24.data.daos;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Photo;

@Dao
public interface PhotoDao {
    @Insert
    void insert(Photo photo);

    @Update
    void update(Photo photo);

    @Query("SELECT * FROM photo_points WHERE id = :id")
    Photo getPhotoById(int id);

    @Query("SELECT * FROM photo_points " +
            "WHERE teamId = :teamId AND isSync=0")
    List<Photo> getNotSyncPhoto(int teamId);

    @Query("SELECT * FROM photo_points " +
            "WHERE teamId = :teamId AND isSync=0")
    List<Photo> getNotLocalSyncPhoto(int teamId);

    /* Фото с номерами отсутствующими в легенде */
    @Query("SELECT photo_points.pointNumber FROM photo_points " +
            "LEFT JOIN points " +
            "ON points.number=photo_points.pointNumber " +
            "WHERE photo_points.teamId = :teamId AND points.id IS NULL")
    LiveData<List<Integer>> getNonLegendPointNumbers(int teamId);

    @Query("SELECT * FROM photo_points " +
            "ORDER BY pointNumber")
    List<Photo> getListPhotos();

    @Query("SELECT * FROM photo_points " +
            "ORDER BY pointNumber")
    LiveData<List<Photo>> getAllPhotos();

    @Query("SELECT * FROM photo_points " +
            "WHERE teamId = :teamId " +
            "ORDER BY pointNumber")
    List<Photo> getPhotosByTeamId(int teamId);

    @Query("SELECT count(DISTINCT pointNumber) " +
            "FROM photo_points " +
            "WHERE teamId = :teamId")
    LiveData<Integer> getPhotoCount(int teamId);

    @Query("SELECT sum(points.cost) FROM points " +
            "LEFT JOIN (SELECT pointNumber, teamId FROM photo_points " +
            "  WHERE teamId= :teamId " +
            "  GROUP BY pointNumber) photo " +
            "    ON points.number = photo.pointNumber " +
            "WHERE photo.pointNumber IS NOT NULL")
    LiveData<Integer> getCostSum(int teamId);

    @Query("DELETE FROM photo_points " +
            "WHERE id = :id")
    void deletePhotoPointById(int id);

    @Query("DELETE FROM photo_points")
    void deleteAll();
}
