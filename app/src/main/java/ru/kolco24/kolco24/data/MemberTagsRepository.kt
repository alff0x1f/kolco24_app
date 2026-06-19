package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.FetchResult
import ru.kolco24.kolco24.data.api.dto.MemberTagDto
import ru.kolco24.kolco24.data.db.MemberTagDao
import ru.kolco24.kolco24.data.db.MemberTagEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

/** Per-race `sync_meta` resource for the member-tags endpoint (see [SyncMetaEntity]). */
private fun memberTagsResource(raceId: Int): String = "race/$raceId/member_tags"

/**
 * Separate sync-marker resource written on every successful `200` fetch, even when the server
 * omits the `ETag` header. [MemberTagsRepository.hasBeenSynced] checks this key so that an
 * empty pool served without an ETag is still recognised as "synced" after activity recreation.
 */
private fun memberTagsSyncedResource(raceId: Int): String = "race/$raceId/member_tags/synced"

/**
 * Single source of truth for one race's NFC member-tag pool (`number → nfc_uid`). Room holds the
 * data; the network only updates it. The UI/bind flow reads [observeForRace]/[findByUid];
 * [refreshMemberTags] performs a conditional fetch and, on `200`, fully replaces that race's local
 * rows.
 *
 * Mirrors [LegendRepository] exactly: the pool is modelled **per-race** ([MemberTagEntity.raceId],
 * `replaceAllForRace`) with a **per-race** ETag in `sync_meta` (resource key
 * `"race/<raceId>/member_tags"`), so two warm-ups touching different races write disjoint rows and
 * disjoint ETags — and it stays correct once the backend serves a genuinely different pool per race.
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 */
class MemberTagsRepository(
    private val apiClient: ApiClient,
    private val memberTagDao: MemberTagDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
) {
    /** Offline-readable member-tag pool of one race, ordered by participant number then uid. */
    fun observeForRace(raceId: Int): Flow<List<MemberTagEntity>> =
        memberTagDao.observeForRace(raceId)

    /** Resolves a scanned/normalized [nfcUid] against one race's pool (`null` when not in the pool). */
    suspend fun findByUid(raceId: Int, nfcUid: String): MemberTagEntity? =
        memberTagDao.findByUid(raceId, nfcUid)

    /**
     * Returns `true` if the member-tag pool for [raceId] has been successfully fetched at least once.
     * Used by the bind sheet to distinguish "pool not yet synced" from "pool is genuinely empty",
     * surviving activity recreation and cases where the startup warm-up synced the pool before the
     * bind sheet composition existed.
     *
     * Checks **either** the ETag resource (written when the server includes an `ETag` header) **or**
     * the sync-marker resource (written on every successful `200`, even when the server omits `ETag`).
     * Both are absent only when no successful fetch has ever occurred for this race.
     */
    suspend fun hasBeenSynced(raceId: Int): Boolean =
        syncMetaDao.getEtag(origin, memberTagsResource(raceId)) != null ||
            syncMetaDao.getEtag(origin, memberTagsSyncedResource(raceId)) != null

    /**
     * Fetches `/app/race/<raceId>/member_tags/` with the stored ETag and, on `200`, replaces that
     * race's member-tag rows, then saves the new ETag. Like [LegendRepository.refreshLegend], the
     * data write and the ETag write are separate transactions on purpose: a crash between them leaves
     * fresh data with a stale ETag, so the next refresh gets another `200` and self-heals.
     */
    suspend fun refreshMemberTags(raceId: Int): RefreshResult {
        val resource = memberTagsResource(raceId)
        val etag = syncMetaDao.getEtag(origin, resource)
        return when (val result = apiClient.fetchMemberTags(raceId, etag)) {
            is FetchResult.Success -> {
                memberTagDao.replaceAllForRace(
                    raceId = raceId,
                    tags = result.data.memberTags.map { it.toEntity(raceId) },
                )
                if (result.etag != null) {
                    syncMetaDao.upsert(SyncMetaEntity(origin, resource, result.etag))
                } else {
                    // No ETag from the server: write a sync-marker so hasBeenSynced() returns true
                    // after activity recreation even when the pool is empty (an ETag-less empty-pool
                    // response is a legitimate server response, not a "not yet synced" condition).
                    syncMetaDao.upsert(SyncMetaEntity(origin, memberTagsSyncedResource(raceId), "1"))
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

/** Maps a `member_tags[]` DTO to its persisted entity, stamping the owning [raceId]. */
private fun MemberTagDto.toEntity(raceId: Int): MemberTagEntity = MemberTagEntity(
    raceId = raceId,
    nfcUid = nfcUid,
    number = number,
)
