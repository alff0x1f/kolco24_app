package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.FetchResult
import ru.kolco24.kolco24.data.api.dto.CategoryDto
import ru.kolco24.kolco24.data.api.dto.TeamDto
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.SelectedTeamDao
import ru.kolco24.kolco24.data.db.SelectedTeamEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity
import ru.kolco24.kolco24.data.db.TeamDao
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.db.TeamMemberItem

/** Per-race `sync_meta` resource for the teams endpoint (see [SyncMetaEntity]). */
private fun teamsResource(raceId: Int): String = "race/$raceId/teams"

/**
 * Single source of truth for one race's teams + categories and the currently-selected team.
 * Room holds the data; the network only updates it. The UI reads [teamsForRace] /
 * [categoriesForRace] / [selectedTeam]; [refreshTeams] performs a conditional fetch and, on `200`,
 * fully replaces that race's local rows.
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 */
class TeamRepository(
    private val apiClient: ApiClient,
    private val teamDao: TeamDao,
    private val selectedTeamDao: SelectedTeamDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
) {
    /** Offline-readable teams of one race, ordered by start number then id. */
    fun teamsForRace(raceId: Int): Flow<List<TeamEntity>> = teamDao.observeTeamsForRace(raceId)

    /** Offline-readable categories of one race, ordered by sort order then id. */
    fun categoriesForRace(raceId: Int): Flow<List<CategoryEntity>> =
        teamDao.observeCategoriesForRace(raceId)

    /** The currently-selected team, or `null` when nothing is chosen. */
    val selectedTeam: Flow<SelectedTeamEntity?> = selectedTeamDao.observe()

    /** A single team by id (emits `null` once it disappears from the local table). */
    fun observeTeam(teamId: Int): Flow<TeamEntity?> = teamDao.observeTeamById(teamId)

    /**
     * Fetches `/app/race/<raceId>/teams/` with the stored ETag and, on `200`, replaces that race's
     * teams + categories then saves the new ETag. Like [RaceRepository.refreshRaces], the data write
     * and the ETag write are two **separate** transactions on purpose: a crash between them leaves
     * fresh data with a stale ETag, so the next refresh gets another `200` and self-heals.
     */
    suspend fun refreshTeams(raceId: Int): RefreshResult {
        val resource = teamsResource(raceId)
        val etag = syncMetaDao.getEtag(origin, resource)
        return when (val result = apiClient.fetchTeams(raceId, etag)) {
            is FetchResult.Success -> {
                val response = result.data
                teamDao.replaceAllForRace(
                    raceId = raceId,
                    categories = response.categories.map { it.toEntity(raceId) },
                    teams = response.teams.map { it.toEntity(raceId) },
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

    /** Records the chosen team as the single selected-team row (overwrites any previous choice). */
    suspend fun selectTeam(raceId: Int, teamId: Int) {
        selectedTeamDao.upsert(SelectedTeamEntity(raceId = raceId, teamId = teamId))
    }
}

/** Maps a network DTO to the persisted entity, stamping the owning [raceId]. */
private fun CategoryDto.toEntity(raceId: Int): CategoryEntity = CategoryEntity(
    id = id,
    raceId = raceId,
    code = code,
    shortName = shortName,
    name = name,
    sortOrder = order,
)

/** Maps a network DTO to the persisted entity; `members` becomes the JSON column payload. */
private fun TeamDto.toEntity(raceId: Int): TeamEntity = TeamEntity(
    id = id,
    raceId = raceId,
    teamname = teamname,
    startNumber = startNumber,
    categoryId = category2,
    ucount = ucount,
    paidPeople = paidPeople,
    startTime = startTime,
    finishTime = finishTime,
    members = members.map { TeamMemberItem(name = it.name, numberInTeam = it.numberInTeam) },
)
