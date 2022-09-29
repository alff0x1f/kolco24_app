package org.kolco24.kolco24.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

public class PhotoRepository {
    private PhotoDao mPhotoDao;
    private LiveData<List<Photo>> mAllPhotos;

    public PhotoRepository(Application application) {
        PointsDatabase db = PointsDatabase.getDatabase(application);
        mPhotoDao = db.photoDao();
        mAllPhotos = mPhotoDao.getAllPhotos();
    }

    public LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotos;
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    public void insert(Photo photo) {
        PointsDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.insert(photo);
        });
    }

    public void deleteAll() {
        PointsDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.deleteAll();
        });
    }
}