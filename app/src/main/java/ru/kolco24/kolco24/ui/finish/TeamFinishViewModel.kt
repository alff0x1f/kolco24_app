package ru.kolco24.kolco24.ui.finish

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import ru.kolco24.kolco24.data.entities.TeamFinish
import ru.kolco24.kolco24.data.repositories.TeamFinishRepository

class TeamFinishViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TeamFinishRepository(application)

    val allFinishes: LiveData<List<TeamFinish>> = repository.observeAll()

    fun insert(event: TeamFinish) {
        repository.insert(event)
    }

    fun getPending(useLocal: Boolean): List<TeamFinish> = repository.getPending(useLocal)

    fun markLocalSynced(id: Int, synced: Boolean) {
        repository.markLocalSynced(id, synced)
    }

    fun markRemoteSynced(id: Int, synced: Boolean) {
        repository.markRemoteSynced(id, synced)
    }
}
