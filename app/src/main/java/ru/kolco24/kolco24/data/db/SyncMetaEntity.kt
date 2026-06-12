package ru.kolco24.kolco24.data.db

import androidx.room.Entity

/**
 * Per-origin sync metadata. The composite key keeps ETags separate by origin (base URL) and
 * resource, so the same table can be reused for teams/legend later.
 *
 * @param origin base URL the data came from
 * @param resource resource name, e.g. `"races"`
 * @param etag last seen ETag, stored verbatim (with quotes) for `If-None-Match`
 */
@Entity(tableName = "sync_meta", primaryKeys = ["origin", "resource"])
data class SyncMetaEntity(
    val origin: String,
    val resource: String,
    val etag: String,
)
