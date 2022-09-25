package org.kolco24.kolco24.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

public class PhotoPointRepository {
    private PhotoDao mPhotoPointDao;
    private LiveData<List<Photo>> mAllPhotoPoints;

    public PhotoPointRepository(Application application) {
        PointsDatabase db = PointsDatabase.getDatabase(application);
        mPhotoPointDao = db.photoPointDao();
        mAllPhotoPoints = mPhotoPointDao.getAllPhotoPoints();
    }

    public LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotoPoints;
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    public void insert(Photo photo) {
        PointsDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoPointDao.insert(photo);
        });
    }

    public void deleteAll() {
        PointsDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoPointDao.deleteAll();
        });
    }
}
