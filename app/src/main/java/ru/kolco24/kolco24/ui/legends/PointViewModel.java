package ru.kolco24.kolco24.ui.legends;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

import ru.kolco24.kolco24.data.entities.Checkpoint;
import ru.kolco24.kolco24.data.repositories.CheckpointRepository;

public class PointViewModel extends AndroidViewModel {
    private CheckpointRepository mRepository;

    /*__init__*/
    public PointViewModel(Application application) {
        super(application);
        mRepository = new CheckpointRepository(application);
    }

    LiveData<List<Checkpoint.PointExt>> getAllPoints() {
        return mRepository.getAllPoints();
    }

    LiveData<List<Checkpoint.PointExt>> getTakenPointsByTeam(int teamId) {
        return mRepository.getTakenPointsByTeam(teamId);
    }

    public Checkpoint getPointById(int id) {
        return mRepository.getPointById(id);
    }
    public Checkpoint getPointByNumber(int number) {
        return mRepository.getPointByNumber(number);
    }

    public void insert(Checkpoint point) {
        mRepository.insert(point);
    }
    public void update(Checkpoint point) {
        mRepository.update(point);
    }
    public void deleteAll() {
        mRepository.deleteAll();
    }
}
