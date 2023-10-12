package ru.kolco24.kolco24.ui.legends;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Point;
import ru.kolco24.kolco24.data.repositories.PointRepository;

public class PointViewModel extends AndroidViewModel {
    private PointRepository mRepository;

    /*__init__*/
    public PointViewModel(Application application) {
        super(application);
        mRepository = new PointRepository(application);
    }

    LiveData<List<Point.PointExt>> getAllPoints() {
        return mRepository.getAllPoints();
    }

    LiveData<List<Point.PointExt>> getTakenPointsByTeam(int teamId) {
        return mRepository.getTakenPointsByTeam(teamId);
    }

    public Point getPointById(int id) {
        return mRepository.getPointById(id);
    }
    public Point getPointByNumber(int number) {
        return mRepository.getPointByNumber(number);
    }

    public void insert(Point point) {
        mRepository.insert(point);
    }
    public void update(Point point) {
        mRepository.update(point);
    }
    public void deleteAll() {
        mRepository.deleteAll();
    }
}
