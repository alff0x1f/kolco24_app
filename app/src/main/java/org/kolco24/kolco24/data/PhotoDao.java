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

    @Query("DELETE FROM photo_points WHERE id = :id")
    void deletePhotoPointById(int id);

    @Query("DELETE FROM photo_points")
    void deleteAll();
}
