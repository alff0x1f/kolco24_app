package ru.kolco24.kolco24.data.daos;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Point;


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

    @Query("SELECT " +
            "points.id, " +
            "points.number, " +
            "points.description, " +
            "points.cost, " +
            "photo.photoTime, " +
            "photo.time " +
            "FROM points " +
            "LEFT JOIN (" +
            "   SELECT pointNumber, photoTime, time " +
            "   FROM photo_points " +
            "   GROUP BY pointNumber) photo " +
            "       ON points.number == photo.pointNumber " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getAllPoints();

    @Query("SELECT " +
            "points.id, " +
            "points.number, " +
            "points.description, " +
            "points.cost, " +
            "photo.photoTime, " +
            "photo.time " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT pointNumber, photoTime, time " +
            "  FROM photo_points " +
            "  WHERE teamId = :teamId " +
            "  GROUP BY pointNumber" +
            ") photo " +
            "ON points.number == photo.pointNumber " +
            "ORDER BY " +
            "     CASE " +
            "        WHEN photo.photoTime IS NULL THEN 1" +
            "        ELSE 0" +
            "    END, " +
            "photo.photoTime, points.number")
    LiveData<List<Point.PointExt>> getPointsByTeam(int teamId);

    @Query("DELETE FROM points WHERE id = :id")
    void deletePointById(int id);

    @Query("DELETE FROM points")
    void deleteAll();
}
