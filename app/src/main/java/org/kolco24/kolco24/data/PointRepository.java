package org.kolco24.kolco24.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

class PointRepository {
    private PointDao mPointDao;
    private LiveData<List<Point>> mAllPoints;

    PointRepository(Application application) {
        PointsDatabase db = PointsDatabase.getDatabase(application);
        mPointDao = db.pointDao();
        mAllPoints = mPointDao.getAllPoints();
    }

    LiveData<List<Point>> getAllPoints() {
        return mAllPoints;
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    void insert(Point point) {
        PointsDatabase.databaseWriteExecutor.execute(() -> {
            mPointDao.insert(point);
        });
    }
}
