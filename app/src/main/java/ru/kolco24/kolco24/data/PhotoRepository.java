package ru.kolco24.kolco24.data;

import android.app.Application;
import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;

public class PhotoRepository {
    private PhotoDao mPhotoDao;
    private LiveData<List<Photo>> mAllPhotos;
    private int teamId;

    public PhotoRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mPhotoDao = db.photoDao();
        teamId = application.getApplicationContext().getSharedPreferences(
                "team", Context.MODE_PRIVATE
        ).getInt("team_id", 0);

        mAllPhotos = mPhotoDao.getPhotosByTeamId(teamId);
    }

    public LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotos;
    }

    public void setTeamId(int teamId) {
        this.teamId = teamId;
        mAllPhotos = mPhotoDao.getPhotosByTeamId(teamId);
    }

    public int getPhotoCount(int teamId) {
        return mPhotoDao.getPhotoCount(teamId);
    }

    public int getCostSum(int teamId) {
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

    public Photo getPhotoById(int id) {
        return mPhotoDao.getPhotoById(id);
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mPhotoDao.deleteAll();
        });
    }
}
