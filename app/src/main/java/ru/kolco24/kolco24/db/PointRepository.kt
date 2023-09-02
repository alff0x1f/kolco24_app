package ru.kolco24.kolco24.db

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.db.dao.PointDao
import ru.kolco24.kolco24.db.entity.PointEntity

class PointRepository(private val pointDAO: PointDao) {

    val allPoints: Flow<List<PointEntity>> = pointDAO.getPoints()

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun insert(pointEntity: PointEntity) {
        pointDAO.insert(pointEntity)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun deleteAll() {
        pointDAO.deleteAll()
    }
}