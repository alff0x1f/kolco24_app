package org.kolco24.kolco24.data;

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

    @Query("SELECT * FROM photo_points ORDER BY point_number")
    LiveData<List<Photo>> getAllPhotos();

    @Query("SELECT count(DISTINCT point_number) FROM photo_points")
    int getPhotoCount();

    @Query("SELECT sum(points.cost) FROM points " +
            "LEFT JOIN (select point_number from photo_points group by point_number) photo " +
            "ON points.number = photo.point_number "+
            "WHERE photo.point_number IS NOT NULL")
    int getCostSum();

    @Query("DELETE FROM photo_points WHERE id = :id")
    void deletePhotoPointById(int id);

    @Query("DELETE FROM photo_points")
    void deleteAll();
}
