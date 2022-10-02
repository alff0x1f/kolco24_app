package ru.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import ru.kolco24.kolco24.data.Photo;
import ru.kolco24.kolco24.data.PhotoRepository;

import java.util.List;

public class PhotoViewModel extends AndroidViewModel {
    private PhotoRepository mPhotoRepository;
    private final LiveData<List<Photo>> mAllPhoto;

    public PhotoViewModel(Application application) {
        super(application);
        mPhotoRepository = new PhotoRepository(application);
        mAllPhoto = mPhotoRepository.getAllPhotoPoints();

    }

    LiveData<List<Photo>> getAllPhoto() {
        return mAllPhoto;
    }

    int getPhotoCount() {
        return mPhotoRepository.getPhotoCount();
    }

    int getCostSum() {
        return mPhotoRepository.getCostSum();
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