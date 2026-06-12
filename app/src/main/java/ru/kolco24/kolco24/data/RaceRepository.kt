package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.FetchResult
import ru.kolco24.kolco24.data.api.dto.RaceDto
import ru.kolco24.kolco24.data.db.RaceDao
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

/** Resource name for races in `sync_meta` (see [SyncMetaEntity]). */
private const val RESOURCE_RACES = "races"

/**
 * Outcome of a [RaceRepository.refreshRaces] call. `Error(null)` from the network (offline /
 * dropped connection) maps to [Offline]; `Error(code)` to [HttpError].
 */
sealed interface RefreshResult {
    data object Updated : RefreshResult
    data object NotModified : RefreshResult
    data object Offline : RefreshResult
    data object Forbidden : RefreshResult
    data class HttpError(val code: Int) : RefreshResult
}

/**
 * Single source of truth for the race list: Room holds the data, the network only updates it.
 * The UI reads [races]; [refreshRaces] performs a conditional fetch and, on `200`, fully replaces
 * the local table.
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 */
class RaceRepository(
    private val apiClient: ApiClient,
    private val raceDao: RaceDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
) {
    /** Offline-readable race list, ordered newest first. */
    val races: Flow<List<RaceEntity>> = raceDao.observeRaces()

    /**
     * Fetches `/app/races/` with the stored ETag and, on `200`, replaces the whole table then saves
     * the new ETag. The data write and the ETag write are two **separate** transactions on purpose:
     * a crash between them leaves fresh data with a stale ETag, so the next refresh gets another
     * `200` and self-heals; the reverse order would pin a new ETag onto stale data forever.
     */
    suspend fun refreshRaces(): RefreshResult {
        val etag = syncMetaDao.getEtag(origin, RESOURCE_RACES)
        return when (val result = apiClient.fetchRaces(etag)) {
            is FetchResult.Success -> {
                raceDao.replaceAll(result.data.map { it.toEntity() })
                if (result.etag != null) {
                    syncMetaDao.upsert(SyncMetaEntity(origin, RESOURCE_RACES, result.etag))
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

/** Maps a network DTO to the persisted entity (the entity is also the app model). */
private fun RaceDto.toEntity(): RaceEntity = RaceEntity(
    id = id,
    name = name,
    slug = slug,
    date = date,
    dateEnd = dateEnd,
    place = place,
    regStatus = regStatus,
    isLegendVisible = isLegendVisible,
)
