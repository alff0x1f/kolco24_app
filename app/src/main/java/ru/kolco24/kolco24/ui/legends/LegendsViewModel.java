package ru.kolco24.kolco24.ui.legends;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class LegendsViewModel extends ViewModel {

    private final MutableLiveData<String> mText;

    public LegendsViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("Пока легенда недоступна. Попробуйте загрузить позже");
    }

    public LiveData<String> getText() {
        return mText;
    }
}