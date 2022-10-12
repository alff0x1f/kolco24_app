package ru.kolco24.kolco24.ui.photo;

import android.app.Application;
import android.content.Context;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import ru.kolco24.kolco24.data.Photo;
import ru.kolco24.kolco24.data.PhotoRepository;
import ru.kolco24.kolco24.data.TeamRepository;

import java.util.List;

public class PhotoViewModel extends AndroidViewModel {
    private PhotoRepository mPhotoRepository;
    private TeamRepository mTeamRepository;
    private LiveData<List<Photo>> mAllPhoto;
    private int teamId;

    public PhotoViewModel(Application application) {
        super(application);
        mPhotoRepository = new PhotoRepository(application);
        mTeamRepository = new TeamRepository(application);
        mAllPhoto = mPhotoRepository.getAllPhotoPoints();

        teamId = application.getApplicationContext().getSharedPreferences(
                "team", Context.MODE_PRIVATE
        ).getInt("team_id", 0);
    }

    public String getTeamName() {
        if (teamId == 0) {
            return "";
        }
        return mTeamRepository.getTeamName(teamId);
    }

    LiveData<List<Photo>> getAllPhoto() {
        return mAllPhoto;
    }

    public void setTeamId(int teamId) {
        this.teamId = teamId;
        mPhotoRepository.setTeamId(teamId);
        mAllPhoto = mPhotoRepository.getAllPhotoPoints();
    }

    public int getTeamId() {
        return teamId;
    }

    int getPhotoCount(int teamId) {
        return mPhotoRepository.getPhotoCount(teamId);
    }

    int getCostSum(int teamId) {
        return mPhotoRepository.getCostSum(teamId);
    }

    public Photo getPhotoById(int id) {
        return mPhotoRepository.getPhotoById(id);
    }

    public void insert(Photo photo) {
        mPhotoRepository.insert(photo);
    }

    public void update(Photo photo) {
        mPhotoRepository.update(photo);
    }
}