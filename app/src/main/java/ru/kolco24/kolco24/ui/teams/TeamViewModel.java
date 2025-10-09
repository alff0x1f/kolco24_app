package ru.kolco24.kolco24.ui.teams;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.data.repositories.TeamRepository;


public class TeamViewModel extends AndroidViewModel {
    private TeamRepository mRepository;
    private final LiveData<List<Team>> mAllTeams;
    private MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private MutableLiveData<Boolean> isLoading = new MutableLiveData<>();

    /*__init__*/
    public TeamViewModel(Application application) {
        super(application);
        mRepository = new TeamRepository(application);
        mAllTeams = mRepository.getAllTeams();
        toastMessage.setValue(null);
        isLoading.setValue(false);
    }

    LiveData<List<Team>> getAllTeams() {
        return mAllTeams;
    }

    public LiveData<List<Team>> getTeamsByCategory(Integer category) {
        return mRepository.getTeamsByCategory(category);
    }

    public Team getTeamById(int id) {
        return mRepository.getTeamById(id);
    }

    public LiveData<Team> getTeamLive(int id) {
        return mRepository.getTeam(id);
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public void clearToastMessage() {
        toastMessage.postValue(null);
    }

    public LiveData<Boolean> isLoading() {
        return isLoading;
    }
}
