package org.kolco24.kolco24.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {Point.class}, version = 1, exportSchema = false)
public abstract class PointsDatabase extends RoomDatabase {
    public abstract PointDao pointDao();

    private static volatile PointsDatabase INSTANCE;
    private static final int NUMBER_OF_THREADS = 4;
    static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    /**
     * Get the database, creating it if it does not exist.
     *
     * @param context the application context Singleton pattern, allows only one instance of the
     *                database to be opened at a time.
     */
    static PointsDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (PointsDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    PointsDatabase.class, "points_database")
                            .addCallback(sRoomDatabaseCallback)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    private static RoomDatabase.Callback sRoomDatabaseCallback = new RoomDatabase.Callback() {

        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);

            //create empty database
            databaseWriteExecutor.execute(() -> {
                PointDao dao = INSTANCE.pointDao();
                dao.deleteAll();

                Point point = new Point("01", "Описание", 1);
                dao.insert(point);
                point = new Point("02", "Описание 2", 2);
                dao.insert(point);
                point = new Point("03", "Описание 3", 3);
                dao.insert(point);
                for (int i = 4; i < 10; i++) {
                    point = new Point("0" + i, "Описание " + i, i);
                    dao.insert(point);
                }
                for (int i = 10; i < 50; i++) {
                    point = new Point(Integer.toString(i), "Тестовое дерево в лесу у ручья " + i, i);
                    dao.insert(point);
                }
            });
        }
    };
}
