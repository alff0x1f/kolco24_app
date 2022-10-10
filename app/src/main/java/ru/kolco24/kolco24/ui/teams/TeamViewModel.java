package ru.kolco24.kolco24.ui.teams;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.Team;
import ru.kolco24.kolco24.data.TeamRepository;


public class TeamViewModel extends AndroidViewModel {
    private TeamRepository mRepository;
    private final LiveData<List<Team>> mAllTeams;

    /*__init__*/
    public TeamViewModel(Application application) {
        super(application);
        mRepository = new TeamRepository(application);
        mAllTeams = mRepository.getAllTeams();
    }

    LiveData<List<Team>> getAllTeams() {
        return mAllTeams;
    }

    public void insert(Team team) {
        mRepository.insert(team);
    }
}