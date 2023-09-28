package ru.kolco24.kolco24.data;

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
            "WHERE team_id = :teamId AND sync_internet=0")
    List<Photo> getNotSyncPhoto(int teamId);

    @Query("SELECT * FROM photo_points " +
            "WHERE team_id = :teamId AND sync_local=0")
    List<Photo> getNotLocalSyncPhoto(int teamId);

    /* Фото с номерами отсутствующими в легенде */
    @Query("SELECT photo_points.point_number FROM photo_points " +
            "LEFT JOIN points " +
            "ON points.number=photo_points.point_number " +
            "WHERE photo_points.team_id = :teamId AND points.id IS NULL")
    LiveData<List<Integer>> getNonLegendPointNumbers(int teamId);

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
    LiveData<Integer> getPhotoCount(int teamId);

    @Query("SELECT sum(points.cost) FROM points " +
            "LEFT JOIN (SELECT point_number, team_id FROM photo_points " +
            "  WHERE team_id= :teamId " +
            "  GROUP BY point_number) photo " +
            "    ON points.number = photo.point_number " +
            "LEFT JOIN ( " +
            "  SELECT pointNumber AS point_number " +
            "  FROM nfc_check " +
            "  GROUP BY pointNumber" +
            ") nfc " +
            "   ON points.number == nfc.point_number " +
            "WHERE photo.point_number IS NOT NULL OR nfc.point_number IS NOT NULL")
    LiveData<Integer> getCostSum(int teamId);

    @Query("DELETE FROM photo_points " +
            "WHERE id = :id")
    void deletePhotoPointById(int id);

    @Query("DELETE FROM photo_points")
    void deleteAll();
}
