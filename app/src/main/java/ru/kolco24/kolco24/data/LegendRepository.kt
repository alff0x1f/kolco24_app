package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.FetchResult
import ru.kolco24.kolco24.data.api.dto.CheckpointDto
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

/** Per-race `sync_meta` resource for the legend endpoint (see [SyncMetaEntity]). */
private fun legendResource(raceId: Int): String = "race/$raceId/legend"

/**
 * Single source of truth for one race's legend (checkpoints). Room holds the data; the network only
 * updates it. The UI reads [checkpointsForRace]; [refreshLegend] performs a conditional fetch and,
 * on `200`, fully replaces that race's local checkpoint rows.
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 */
class LegendRepository(
    private val apiClient: ApiClient,
    private val checkpointDao: CheckpointDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
) {
    /** Offline-readable checkpoints of one race, ordered by number then id. */
    fun checkpointsForRace(raceId: Int): Flow<List<CheckpointEntity>> =
        checkpointDao.observeCheckpointsForRace(raceId)

    /**
     * Fetches `/app/race/<raceId>/legend/` with the stored ETag and, on `200`, replaces that race's
     * checkpoints then saves the new ETag. Like [TeamRepository.refreshTeams], the data write and the
     * ETag write are two **separate** transactions on purpose: a crash between them leaves fresh data
     * with a stale ETag, so the next refresh gets another `200` and self-heals.
     */
    suspend fun refreshLegend(raceId: Int): RefreshResult {
        val resource = legendResource(raceId)
        val etag = syncMetaDao.getEtag(origin, resource)
        return when (val result = apiClient.fetchLegend(raceId, etag)) {
            is FetchResult.Success -> {
                val response = result.data
                checkpointDao.replaceAllForRace(
                    raceId = raceId,
                    checkpoints = response.checkpoints.map { it.toEntity(raceId) },
                )
                if (result.etag != null) {
                    syncMetaDao.upsert(SyncMetaEntity(origin, resource, result.etag))
                }
                RefreshResult.Updated
            }
            FetchResult.NotModified -> RefreshResult.NotModified
            FetchResult.Forbidden -> RefreshResult.Forbidden
            is FetchResult.Error ->
                if (result.code == null) RefreshResult.Offline else RefreshResult.HttpError(result.code)
        }
    }
}

/** Maps a network DTO to the persisted entity, stamping the owning [raceId]. `taken` starts `false`. */
private fun CheckpointDto.toEntity(raceId: Int): CheckpointEntity = CheckpointEntity(
    id = id,
    raceId = raceId,
    number = number,
    // cost/description are now nullable on the DTO (locked CPs omit them). CheckpointEntity gains
    // nullable columns + locked/enc in Task 2; until then fall back so this compiles.
    cost = cost ?: 0,
    type = type,
    description = description.orEmpty(),
)
