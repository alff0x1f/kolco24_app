package ru.kolco24.kolco24.ui.teams;

import android.app.Application;
import android.os.AsyncTask;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import ru.kolco24.kolco24.data.repositories.TeamRepository;

public class TeamsViewModel extends AndroidViewModel {

    private final MutableLiveData<String> mText;
    private TeamRepository mRepository;

    public TeamsViewModel(Application application) {
        super(application);
        mText = new MutableLiveData<>();
        mText.setValue("This is team fragment");

        mRepository = new TeamRepository(application);
        AsyncTask.execute(() -> {
            int c = mRepository.getTeamCount();
            mText.postValue("This is team fragment " + c);
        });
    }

    public LiveData<String> getText() {
        return mText;
    }
}