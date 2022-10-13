package ru.kolco24.kolco24.data;

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

    @Query("SELECT points.*, photo.photo_thumb_url " +
            "FROM points " +
            "LEFT JOIN (SELECT point_number, min(photo_thumb_url) AS photo_thumb_url FROM photo_points GROUP BY point_number) photo " +
            "ON points.number == photo.point_number " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getAllPoints();

    @Query("SELECT points.*, photo.photo_thumb_url " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT point_number, min(photo_thumb_url) AS photo_thumb_url " +
            "  FROM photo_points " +
            "  WHERE team_id = :teamId " +
            "  GROUP BY point_number" +
            ") photo " +
            "ON points.number == photo.point_number " +
            "WHERE photo.point_number IS NULL " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getNewPointsByTeam(int teamId);

    @Query("SELECT points.*, photo.photo_thumb_url " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT point_number, min(photo_thumb_url) AS photo_thumb_url " +
            "  FROM photo_points " +
            "  WHERE team_id = :teamId " +
            "  GROUP BY point_number" +
            ") photo " +
            "ON points.number == photo.point_number " +
            "WHERE photo.point_number IS NOT NULL " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getTakenPointsByTeam(int teamId);

    @Query("DELETE FROM points WHERE id = :id")
    void deletePointById(int id);

    @Query("DELETE FROM points")
    void deleteAll();
}
