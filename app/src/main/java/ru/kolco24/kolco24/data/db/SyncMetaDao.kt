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

    /**
     * Drops a stored ETag. Used to invalidate the *other* origin's cached ETag for a resource
     * right after this origin's `200` persists rows — the two origins share one Room table, so
     * a stale ETag from the origin not just written could otherwise earn a `304` on the next
     * switch-back and skip re-persisting that origin's (actually different) data.
     */
    @Query("DELETE FROM sync_meta WHERE origin = :origin AND resource = :resource")
    suspend fun deleteEtag(origin: String, resource: String)
}
