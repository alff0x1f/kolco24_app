package org.kolco24.kolco24.ui.photo;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import org.kolco24.kolco24.data.Photo;
import org.kolco24.kolco24.data.PhotoRepository;

import java.util.List;

public class PhotoPointViewModel extends AndroidViewModel {
    private PhotoRepository mRepository;
    private final LiveData<List<Photo>> mAllPhotoPoints;

    public PhotoPointViewModel(@NonNull Application application) {
        super(application);
        mRepository = new PhotoRepository(application);
        mAllPhotoPoints = mRepository.getAllPhotoPoints();
    }

    LiveData<List<Photo>> getAllPhotoPoints() {
        return mAllPhotoPoints;
    }

    public void deleteAll(){
        mRepository.deleteAll();
    }

    public void insert(Photo photo) {
        mRepository.insert(photo);
    }
}
