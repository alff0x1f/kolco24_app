package ru.kolco24.kolco24.data;

import android.app.Application;
import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;

public class PhotoRepository {
    private PhotoDao mPhotoDao;
    private LiveData<List<Photo>> mAllPhotos;

    public PhotoRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPhotoDao = db.photoDao();
        mAllPhotos = mPhotoDao.getAllPhotos();
    }

    public LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotos;
    }

    public LiveData<List<Photo>> getPhotoByTeamId(int teamId) {
        return mPhotoDao.getPhotosByTeamId(teamId);
    }

    public LiveData<Integer> getPhotoCount(int teamId) {
        return mPhotoDao.getPhotoCount(teamId);
    }

    public LiveData<Integer> getCostSum(int teamId) {
        return mPhotoDao.getCostSum(teamId);
    }

    // You must call this on a non-UI thread or your app will throw an exception. Room ensures
    // that you're not doing any long running operations on the main thread, blocking the UI.
    public void insert(Photo photo) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.insert(photo);
        });
    }

    public void update(Photo photo) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.update(photo);
        });
    }

    public void deletePhotoPointById(int photoId) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.deletePhotoPointById(photoId);
        });
    }

    public Photo getPhotoById(int id) {
        return mPhotoDao.getPhotoById(id);
    }

    public List<Photo> getNotSyncPhoto(int teamId) {
        return mPhotoDao.getNotSyncPhoto(teamId);
    }

    public List<Photo> getNotLocalSyncPhoto(int teamId) {
        return mPhotoDao.getNotLocalSyncPhoto(teamId);
    }

    public LiveData<List<Integer>> getNonLegendPointNumbers(int teamId) {
        return mPhotoDao.getNonLegendPointNumbers(teamId);
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.deleteAll();
        });
    }
}
