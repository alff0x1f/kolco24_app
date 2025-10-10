package ru.kolco24.kolco24.data.repositories

import android.app.Application
import androidx.lifecycle.LiveData
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.TeamFinish

class TeamFinishRepository(application: Application) {
    private val dao = AppDatabase.getDatabase(application).teamFinishDao()

    fun observeAll(): LiveData<List<TeamFinish>> = dao.getAll()

    fun insert(event: TeamFinish) {
        AppDatabase.databaseWriteExecutor.execute { dao.insert(event) }
    }

    fun getPending(useLocal: Boolean): List<TeamFinish> =
        if (useLocal) dao.getPendingLocal() else dao.getPendingRemote()

    fun markLocalSynced(id: Int, synced: Boolean) {
        AppDatabase.databaseWriteExecutor.execute { dao.markLocalSynced(id, synced) }
    }

    fun markRemoteSynced(id: Int, synced: Boolean) {
        AppDatabase.databaseWriteExecutor.execute { dao.markRemoteSynced(id, synced) }
    }
}
