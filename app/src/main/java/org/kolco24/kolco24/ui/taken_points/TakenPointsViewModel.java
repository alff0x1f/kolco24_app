package org.kolco24.kolco24.ui.taken_points;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import org.kolco24.kolco24.data.Point;
import org.kolco24.kolco24.data.PointRepository;

import java.util.List;

public class TakenPointsViewModel extends AndroidViewModel {

    private PointRepository mRepository;
    private final LiveData<List<Point>> mAllPoints;

    public TakenPointsViewModel(Application application) {
        super(application);
        mRepository = new PointRepository(application);
        mAllPoints = mRepository.getAllPoints();

    }

    LiveData<List<Point>> getAllPoints() {
        return mAllPoints;
    }

    public void insert(Point point) {
        mRepository.insert(point);
    }
}