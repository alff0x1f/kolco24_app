package ru.kolco24.kolco24.data.daos;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Checkpoint;


@Dao
public interface CheckpointDao {
    @Insert
    void insert(Checkpoint point);

    @Update
    void update(Checkpoint point);

    @Query("SELECT * FROM points WHERE id = :id")
    Checkpoint getCheckpointById(int id);

    @Query("SELECT * FROM points WHERE number = :number")
    Checkpoint getCheckpointByNumber(int number);

    @Query("SELECT " +
            "points.id, " +
            "points.number, " +
            "points.description, " +
            "points.cost, " +
            "photo.photoTime, " +
            "photo.time, " +
            "COALESCE(tags.tagCount, 0) AS tagCount " +
            "FROM points " +
            "LEFT JOIN (" +
            "   SELECT pointNumber, photoTime, time " +
            "   FROM photo_points " +
            "   GROUP BY pointNumber) photo " +
            "       ON points.number == photo.pointNumber " +
            "LEFT JOIN (" +
            "   SELECT checkpointId, COUNT(*) AS tagCount " +
            "   FROM point_tags " +
            "   GROUP BY checkpointId " +
            ") tags ON points.id = tags.checkpointId " +
            "ORDER BY points.number")
    LiveData<List<Checkpoint.PointExt>> getAllPoints();

    @Query("SELECT " +
            "points.id, " +
            "points.number, " +
            "points.description, " +
            "points.cost, " +
            "photo.photoTime, " +
            "photo.time, " +
            "COALESCE(tags.tagCount, 0) AS tagCount " +
            "FROM points " +
            "LEFT JOIN (" +
            "  SELECT pointNumber, photoTime, time " +
            "  FROM photo_points " +
            "  WHERE teamId = :teamId " +
            "  GROUP BY pointNumber" +
            ") photo " +
            "ON points.number == photo.pointNumber " +
            "LEFT JOIN (" +
            "  SELECT checkpointId, COUNT(*) AS tagCount " +
            "  FROM point_tags " +
            "  GROUP BY checkpointId " +
            ") tags ON points.id = tags.checkpointId " +
            "WHERE points.type = 'kp' " +
            "ORDER BY " +
            "     CASE " +
            "        WHEN photo.photoTime IS NULL THEN 1" +
            "        ELSE 0" +
            "    END, " +
            "photo.photoTime, points.number")
    LiveData<List<Checkpoint.PointExt>> getPointsByTeam(int teamId);

    @Query("DELETE FROM points WHERE id = :id")
    void deletePointById(int id);

    @Query("DELETE FROM points")
    void deleteAll();
}
