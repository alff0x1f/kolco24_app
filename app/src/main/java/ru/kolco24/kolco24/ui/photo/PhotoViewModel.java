package ru.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import java.util.ArrayList;
import java.util.List;

import ru.kolco24.kolco24.data.entities.Photo;
import ru.kolco24.kolco24.data.entities.Team;
import ru.kolco24.kolco24.data.repositories.NfcCheckRepository;
import ru.kolco24.kolco24.data.repositories.PhotoRepository;
import ru.kolco24.kolco24.data.repositories.TeamRepository;

public class PhotoViewModel extends AndroidViewModel {
    private PhotoRepository mPhotoRepository;
    private TeamRepository mTeamRepository;
    private NfcCheckRepository mNfcCheckRepository;
    private LiveData<List<Photo>> mAllPhoto;

    public PhotoViewModel(Application application) {
        super(application);
        mPhotoRepository = new PhotoRepository(application);
        mTeamRepository = new TeamRepository(application);
        mNfcCheckRepository = new NfcCheckRepository(application);
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
        MediatorLiveData<Integer> resultLiveData = new MediatorLiveData<>();

        LiveData<Integer> photoCountLiveData = mPhotoRepository.getPhotoCount(teamId);
        LiveData<Integer> nfcCheckCountLiveData = mNfcCheckRepository.getNfcCheckCount();

        resultLiveData.addSource(photoCountLiveData, value -> {
            Integer nfcCheckCount = nfcCheckCountLiveData.getValue();
            if (value != null && nfcCheckCount != null) {
                resultLiveData.setValue(value + nfcCheckCount);
            }
        });

        resultLiveData.addSource(nfcCheckCountLiveData, value -> {
            Integer photoCount = photoCountLiveData.getValue();
            if (photoCount != null && value != null) {
                resultLiveData.setValue(photoCount + value);
            }
        });

        return resultLiveData;
    }


    public LiveData<Integer> getCostSum(int teamId) {
        return mPhotoRepository.getCostSum(teamId);
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