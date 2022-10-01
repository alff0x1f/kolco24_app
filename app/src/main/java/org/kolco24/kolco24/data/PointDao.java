package org.kolco24.kolco24.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;


@Dao
public interface PointDao {
    @Insert
    void insert(Point point);

    @Update
    void update(Point point);

    @Query("SELECT * FROM points WHERE id = :id")
    Point getPointById(int id);

    @Query("SELECT * FROM points WHERE number = :number")
    Point getPointByNumber(int number);

    @Query("SELECT * FROM points ORDER BY number")
    LiveData<List<Point>> getAllPoints();

    @Query("DELETE FROM points WHERE id = :id")
    void deletePointById(int id);

    @Query("DELETE FROM points")
    void deleteAll();
}
