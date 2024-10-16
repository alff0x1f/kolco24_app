package ru.kolco24.kolco24.data.repositories;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.AppDatabase;
import ru.kolco24.kolco24.data.daos.TeamDao;
import ru.kolco24.kolco24.data.entities.Team;

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

    public LiveData<List<Team>> getTeamsByCategory(Integer category){
        return mTeamDao.getTeamsByCategory(category);
    }

    public Team getTeamById(int id) {
        return mTeamDao.getTeamById(id);
    }

    public LiveData<String> getTeamName(int id) {
        return mTeamDao.getTeamName(id);
    }

    public LiveData<Team> getTeam(int id) {
        return mTeamDao.getTeam(id);
    }

    public int getTeamNumberById(int id) {
        return mTeamDao.getTeamNumberById(id);
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
