package org.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import org.kolco24.kolco24.data.Photo;
import org.kolco24.kolco24.data.PhotoPointRepository;

import java.util.List;

public class PhotoPointViewModel extends AndroidViewModel {
    private PhotoPointRepository mRepository;
    private final LiveData<List<Photo>> mAllPhotoPoints;

    public PhotoPointViewModel(@NonNull Application application) {
        super(application);
        mRepository = new PhotoPointRepository(application);
        mAllPhotoPoints = mRepository.getAllPhotoPoints();
    }

    LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotoPoints;
    }

    public void insert(Photo photo) {
        mRepository.insert(photo);
    }
}
