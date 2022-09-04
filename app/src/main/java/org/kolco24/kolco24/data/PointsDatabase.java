package org.kolco24.kolco24.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {Point.class}, version = 1, exportSchema = false)
public abstract class PointsDatabase extends RoomDatabase {
    public abstract PointDao pointDao();

    private static volatile PointsDatabase INSTANCE;
    private static final int NUMBER_OF_THREADS = 4;
    static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    static PointsDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (PointsDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    PointsDatabase.class, "points_database")
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
