package ru.kolco24.kolco24.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

public class TeamRepository {
    private TeamDao mTeamDao;
    private LiveData<List<Team>> mAllTeams;

    public TeamRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        mTeamDao = db.teamDao();
        mAllTeams = mTeamDao.getAllTeams();
    }


    public LiveData<List<Team>> getAllTeams() {
        return mAllTeams;
    }

    public Team getTeamById(int id) {
        return mTeamDao.getTeamById(id);
    }

    public Team getTeamByStartNumber(String number) {
        return mTeamDao.getTeamByStartNumber(number);
    }

    public int getTeamCount() {
        return mTeamDao.getTeamCount();
    }

    public void insert(Team team) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mTeamDao.insert(team);
        });
    }

    public void update(Team team) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mTeamDao.update(team);
        });
    }

    public void deleteAll() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            mTeamDao.deleteAll();
        });
    }
}
