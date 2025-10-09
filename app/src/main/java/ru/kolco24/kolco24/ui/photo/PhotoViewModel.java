package ru.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.List;

import ru.kolco24.kolco24.data.entities.Photo;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.data.repositories.PhotoRepository;
import ru.kolco24.kolco24.data.repositories.TeamRepository;

public class PhotoViewModel extends AndroidViewModel {
    private PhotoRepository mPhotoRepository;
    private TeamRepository mTeamRepository;
    private LiveData<List<Photo>> mAllPhoto;

    public PhotoViewModel(Application application) {
        super(application);
        mPhotoRepository = new PhotoRepository(application);
        mTeamRepository = new TeamRepository(application);
        mAllPhoto = mPhotoRepository.getAllPhotoPoints();
    }

    public LiveData<String> getTeamName(int teamId) {
        return mTeamRepository.getTeamName(teamId);
    }

    public LiveData<Team> getTeam(int teamId) {
        return mTeamRepository.getTeam(teamId);
    }

    public int getTeamNumberById(int teamId) {
        return mTeamRepository.getTeamNumberById(teamId);
    }

    LiveData<List<Photo>> getAllPhoto() {
        return mAllPhoto;
    }

    public List<Photo> getPhotos(int teamId) {
        List<Photo> photos = new ArrayList<>();
        Photo addPhoto = new Photo(0, 0, "add_photo", "", "", 0, "");
        Photo addFromGallery = new Photo(0, 0, "add_from_gallery", "", "", 0, "");
        photos.add(addPhoto);
        photos.add(addFromGallery);

        List<Photo> roomPhotos  = mPhotoRepository.getPhotoByTeamId(teamId);
        if (roomPhotos != null) {
            photos.addAll(roomPhotos);
        }

        return photos;
    }

    public LiveData<Integer> getPhotoCount(int teamId) {
        return mPhotoRepository.getPhotoCount(teamId);
    }


    public LiveData<Integer> getCostSum(int teamId) {
        return mPhotoRepository.getCostSum(teamId);
    }

    public LiveData<Integer> getLocalSyncedCount(int teamId) {
        return mPhotoRepository.getLocalSyncedCount(teamId);
    }

    public LiveData<Integer> getInternetSyncedCount(int teamId) {
        return mPhotoRepository.getInternetSyncedCount(teamId);
    }

    public Photo getPhotoById(int id) {
        return mPhotoRepository.getPhotoById(id);
    }

    public List<Photo> getNotSyncPhoto(int teamId) {
        return mPhotoRepository.getNotSyncPhoto(teamId);
    }

    public List<Photo> getNotLocalSyncPhoto(int teamId) {
        return mPhotoRepository.getNotLocalSyncPhoto(teamId);
    }

    /* Фото с номерами отсутствующими в легенде */
    public LiveData<List<Integer>> getNonLegendPointNumbers(int teamId) {
        return mPhotoRepository.getNonLegendPointNumbers(teamId);
    }

    public void insert(Photo photo) {
        mPhotoRepository.insert(photo);
    }

    public void update(Photo photo) {
        mPhotoRepository.update(photo);
    }

    public void deletePhotoPointById(int photoId) {
        mPhotoRepository.deletePhotoPointById(photoId);
    }
}
