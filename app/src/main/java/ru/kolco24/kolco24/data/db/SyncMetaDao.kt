package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncMetaDao {
    @Query("SELECT etag FROM sync_meta WHERE origin = :origin AND resource = :resource")
    suspend fun getEtag(origin: String, resource: String): String?

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)
}
