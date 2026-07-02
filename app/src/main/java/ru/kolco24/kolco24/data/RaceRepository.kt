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

    /** A [SyncSource.Cloud] refresh was skipped because the race is currently pinned to LAN. */
    data object Skipped : RefreshResult
}

/**
 * Single source of truth for the race list: Room holds the data, the network only updates it.
 * The UI reads [races]; [refreshRaces] performs a conditional fetch and, on `200`, fully replaces
 * the local table.
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 * @param localApiClient LAN client for [SyncSource.Local] fetches (the local race-day server).
 * @param localOrigin the LAN client's base URL — the ETag partition key for LAN fetches. Races are
 *   global (not race-scoped), so unlike the other three repos this one carries no pin guard: it is
 *   only ever called with [SyncSource.Local] from inside `enterLocalMode`/`refreshAll` while pinned.
 */
class RaceRepository(
    private val apiClient: ApiClient,
    private val raceDao: RaceDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
    private val localApiClient: ApiClient,
    private val localOrigin: String,
) {
    /** Offline-readable race list, ordered newest first. */
    val races: Flow<List<RaceEntity>> = raceDao.observeRaces()

    /**
     * Fetches `/app/races/` with the stored ETag and, on `200`, replaces the whole table then saves
     * the new ETag. The data write and the ETag write are two **separate** transactions on purpose:
     * a crash between them leaves fresh data with a stale ETag, so the next refresh gets another
     * `200` and self-heals; the reverse order would pin a new ETag onto stale data forever.
     */
    suspend fun refreshRaces(source: SyncSource = SyncSource.Cloud): RefreshResult {
        val (client, originKey) = when (source) {
            SyncSource.Cloud -> apiClient to origin
            SyncSource.Local -> localApiClient to localOrigin
        }
        val etag = syncMetaDao.getEtag(originKey, RESOURCE_RACES)
        return when (val result = client.fetchRaces(etag)) {
            is FetchResult.Success -> {
                // The two origins share this table: a stale ETag on the origin not just written
                // could earn a 304 on the next switch-back and skip re-persisting its own data.
                // Cleared **before** the replace (not after) so a crash mid-write can't strand a
                // stale other-origin ETag masking the fact that this write never landed.
                val otherOriginKey = when (source) {
                    SyncSource.Cloud -> localOrigin
                    SyncSource.Local -> origin
                }
                syncMetaDao.deleteEtag(otherOriginKey, RESOURCE_RACES)
                raceDao.replaceAll(result.data.map { it.toEntity() })
                if (result.etag != null) {
                    syncMetaDao.upsert(SyncMetaEntity(originKey, RESOURCE_RACES, result.etag))
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
)
