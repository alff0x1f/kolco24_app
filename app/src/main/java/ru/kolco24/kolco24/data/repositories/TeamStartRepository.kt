package ru.kolco24.kolco24.data.repositories

import android.app.Application
import androidx.lifecycle.LiveData
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.TeamStart

class TeamStartRepository(application: Application) {
    private val dao = AppDatabase.getDatabase(application).teamStartDao()

    fun observeAll(): LiveData<List<TeamStart>> = dao.getAll()

    fun insert(event: TeamStart) {
        AppDatabase.databaseWriteExecutor.execute { dao.insert(event) }
    }

    fun markLocalSynced(id: Int, synced: Boolean) {
        AppDatabase.databaseWriteExecutor.execute { dao.markLocalSynced(id, synced) }
    }

    fun markRemoteSynced(id: Int, synced: Boolean) {
        AppDatabase.databaseWriteExecutor.execute { dao.markRemoteSynced(id, synced) }
    }

    fun getPending(useLocal: Boolean): List<TeamStart> =
        if (useLocal) dao.getPendingLocal() else dao.getPendingRemote()
}
