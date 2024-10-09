package ru.kolco24.kolco24.data.repositories;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.daos.CheckpointDao;
import ru.kolco24.kolco24.data.entities.Checkpoint;

public class CheckpointRepository {
    private CheckpointDao mCheckpointDao;

    public CheckpointRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mCheckpointDao = db.checkpointDao();
    }

    public LiveData<List<Checkpoint.PointExt>> getAllPoints() {
        return mCheckpointDao.getAllPoints();
    }

    public LiveData<List<Checkpoint.PointExt>> getTakenPointsByTeam(int teamId) {
        return mCheckpointDao.getPointsByTeam(teamId);
    }

    public Checkpoint getPointById(int id) {
        return mCheckpointDao.getCheckpointById(id);
    }
    public Checkpoint getPointByNumber(int number) {
        return mCheckpointDao.getCheckpointByNumber(number);
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    public void insert(Checkpoint point) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mCheckpointDao.insert(point);
        });
    }

    public void update(Checkpoint point) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mCheckpointDao.update(point);
        });
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mCheckpointDao.deleteAll();
        });
    }
}
