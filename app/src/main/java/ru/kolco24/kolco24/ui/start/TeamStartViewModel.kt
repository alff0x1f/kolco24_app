package ru.kolco24.kolco24.ui.start

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.data.entities.TeamStart
import ru.kolco24.kolco24.data.repositories.TeamRepository
import ru.kolco24.kolco24.data.repositories.TeamStartRepository

class TeamStartViewModel(application: Application) : AndroidViewModel(application) {
    private val startRepository = TeamStartRepository(application)
    private val teamRepository = TeamRepository(application)

    val allStarts: LiveData<List<TeamStart>> = startRepository.observeAll()

    fun insert(event: TeamStart) {
        startRepository.insert(event)
    }

    fun markLocalSynced(id: Int, synced: Boolean) {
        startRepository.markLocalSynced(id, synced)
    }

    fun markRemoteSynced(id: Int, synced: Boolean) {
        startRepository.markRemoteSynced(id, synced)
    }

    fun getPending(useLocal: Boolean): List<TeamStart> = startRepository.getPending(useLocal)

    fun findTeamByStartNumber(startNumber: String): Team? =
        teamRepository.getTeamByStartNumber(startNumber)
}
