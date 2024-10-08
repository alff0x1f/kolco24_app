package ru.kolco24.kolco24.data.repositories;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.daos.PointDao;
import ru.kolco24.kolco24.data.entities.Checkpoint;

public class PointRepository {
    private PointDao mPointDao;

    public PointRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPointDao = db.pointDao();
    }

    public LiveData<List<Checkpoint.PointExt>> getAllPoints() {
        return mPointDao.getAllPoints();
    }

    public LiveData<List<Checkpoint.PointExt>> getTakenPointsByTeam(int teamId) {
        return mPointDao.getPointsByTeam(teamId);
    }

    public Checkpoint getPointById(int id) {
        return mPointDao.getPointById(id);
    }
    public Checkpoint getPointByNumber(int number) {
        return mPointDao.getPointByNumber(number);
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    public void insert(Checkpoint point) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPointDao.insert(point);
        });
    }

    public void update(Checkpoint point) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPointDao.update(point);
        });
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPointDao.deleteAll();
        });
    }
}
