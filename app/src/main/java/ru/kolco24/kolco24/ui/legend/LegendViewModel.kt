package ru.kolco24.kolco24.ui.legend

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LegendViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is legend Fragment"
    }
    val text: LiveData<String> = _text
}