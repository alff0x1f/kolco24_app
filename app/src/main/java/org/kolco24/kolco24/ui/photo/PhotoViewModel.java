package org.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import org.kolco24.kolco24.data.Photo;
import org.kolco24.kolco24.data.PhotoRepository;

import java.util.List;

public class PhotoViewModel extends AndroidViewModel {

    private PhotoRepository mPhotoRepository;
    private final LiveData<List<Photo>> mAllPoints;

    public PhotoViewModel(Application application) {
        super(application);
        mPhotoRepository = new PhotoRepository(application);
        mAllPoints = mPhotoRepository.getAllPhotoPoints();

    }

    LiveData<List<Photo>> getAllPoints() {
        return mAllPoints;
    }

    public void insert(Photo photo) {
        mPhotoRepository.insert(photo);
    }
}