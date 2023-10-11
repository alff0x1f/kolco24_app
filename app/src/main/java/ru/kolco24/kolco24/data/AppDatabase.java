package ru.kolco24.kolco24.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.kolco24.kolco24.data.daos.NfcCheckDao;
import ru.kolco24.kolco24.data.daos.PhotoDao;
import ru.kolco24.kolco24.data.daos.PointDao;
import ru.kolco24.kolco24.data.daos.TeamDao;
import ru.kolco24.kolco24.data.entities.NfcCheck;
import ru.kolco24.kolco24.data.entities.Photo;
import ru.kolco24.kolco24.data.entities.Point;
import ru.kolco24.kolco24.data.entities.Team;

@Database(
        entities = {Point.class, Photo.class, Team.class, NfcCheck.class},
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract PointDao pointDao();
    public abstract PhotoDao photoDao();
    public abstract TeamDao teamDao();
    public abstract NfcCheckDao nfcCheckDao();

    private static volatile AppDatabase INSTANCE;
    private static final int NUMBER_OF_THREADS = 4;
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    /**
     * Get the database, creating it if it does not exist.
     *
     * @param context the application context Singleton pattern, allows only one instance of the
     *                database to be opened at a time.
     */
    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "kolco24_database_3")
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
                PointDao photo_dao = INSTANCE.pointDao();
                photo_dao.deleteAll();

                // Photo Point
                PhotoDao photoDao = INSTANCE.photoDao();
                photoDao.deleteAll();

                // Team
                TeamDao teamDao = INSTANCE.teamDao();
                teamDao.deleteAll();

                // NfcCheck
                NfcCheckDao nfcCheckDao = INSTANCE.nfcCheckDao();
                nfcCheckDao.deleteAllNfcChecks();
            });
        }
    };
}
