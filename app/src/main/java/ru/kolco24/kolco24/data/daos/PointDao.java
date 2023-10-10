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

    @Query("SELECT points.*, photo.photo_thumb_url " +
            "FROM points " +
            "LEFT JOIN (SELECT pointNumber, min(photoThumbUrl) AS photo_thumb_url FROM photo_points GROUP BY pointNumber) photo " +
            "ON points.number == photo.pointNumber " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getAllPoints();

    @Query("SELECT points.*, photo.photo_time " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT pointNumber, min(photoTime) AS photo_time " +
            "  FROM photo_points " +
            "  WHERE teamId = :teamId " +
            "  GROUP BY pointNumber" +
            ") photo " +
            "ON points.number == photo.pointNumber " +
            "LEFT JOIN ( " +
            "  SELECT pointNumber AS point_number, min(createDt) AS nfc_time" +
            "  FROM nfc_check " +
            "  GROUP BY pointNumber" +
            ") nfc " +
            "ON points.number == nfc.point_number " +
            "WHERE photo.pointNumber IS NULL " +
            "AND nfc.nfc_time IS NULL " +
            "ORDER BY points.number")
    LiveData<List<Point.PointExt>> getNewPointsByTeam(int teamId);

    @Query("SELECT " +
            "points.*, " +
            "photo.photoTime, " +
            "nfc.nfcTime " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT pointNumber, min(photoTime) AS photoTime " +
            "  FROM photo_points " +
            "  WHERE teamId = :teamId " +
            "  GROUP BY pointNumber" +
            ") photo " +
            "ON points.number == photo.pointNumber " +
            "LEFT JOIN ( " +
            "  SELECT pointNumber AS point_number, min(createDt) AS nfcTime" +
            "  FROM nfc_check " +
            "  GROUP BY pointNumber" +
            ") nfc " +
            "ON points.number == nfc.point_number " +
            "ORDER BY photo.photoTime, points.number")
    LiveData<List<Point.PointExt>> getTakenPointsByTeam(int teamId);

    @Query("DELETE FROM points WHERE id = :id")
    void deletePointById(int id);

    @Query("DELETE FROM points")
    void deleteAll();
}
