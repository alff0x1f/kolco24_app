package ru.kolco24.kolco24.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

public class NfcCheckRepository {
    private NfcCheckDao mNfcCheckDao;
    private LiveData<List<NfcCheck>> mAllNfcChecks;

    public NfcCheckRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mNfcCheckDao = db.nfcCheckDao();
        mAllNfcChecks = mNfcCheckDao.getAllNfcChecks();
    }

    public LiveData<List<NfcCheck>> getAllNfcChecks() {
        return mAllNfcChecks;
    }

    public List<NfcCheck> getNotSyncNfcCheck() {
        return mNfcCheckDao.getNotSyncNfcCheck();
    }

    public NfcCheck getNfcCheckById(int id) {
        return mNfcCheckDao.getNfcCheckById(id);
    }

    public LiveData<Integer> getNfcCheckCount() {
        return mNfcCheckDao.getNfcCheckCount();
    }

    // You must call this on a non-UI thread or your app will throw an exception.
    public void insert(NfcCheck nfcCheck) {
        AppDatabase.databaseWriteExecutor.execute(() ->
                mNfcCheckDao.insert(nfcCheck)
        );
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mNfcCheckDao.deleteAllNfcChecks();
        });
    }
}
