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
 * @param localApiClient LAN client for [SyncSource.Local] fetches (the local race-day server).
 * @param localOrigin the LAN client's base URL — the ETag partition key for LAN fetches.
 * @param isRacePinned `true` when [raceId] is currently pinned to LAN — a [SyncSource.Cloud]
 *   refresh for a pinned race must not persist (a stale cloud mirror must not clobber fresher
 *   local rows), and symmetrically a [SyncSource.Local] refresh for a race that is **not**
 *   pinned must not persist either (a stale LAN response lingering after the switch turned off
 *   must not clobber a fresher cloud refresh).
 */
class MemberTagsRepository(
    private val apiClient: ApiClient,
    private val memberTagDao: MemberTagDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
    private val localApiClient: ApiClient,
    private val localOrigin: String,
    private val isRacePinned: (raceId: Int) -> Boolean,
) {
    /** Offline-readable member-tag pool of one race, ordered by participant number then uid. */
    fun observeForRace(raceId: Int): Flow<List<MemberTagEntity>> =
        memberTagDao.observeForRace(raceId)

    /** Resolves a scanned/normalized [nfcUid] against one race's pool (`null` when not in the pool). */
    suspend fun findByUid(raceId: Int, nfcUid: String): MemberTagEntity? =
        memberTagDao.findByUid(raceId, nfcUid)

    /**
     * Returns `true` if the member-tag pool for [raceId] has been successfully fetched at least once
     * from [source]'s origin. Used by the bind sheet to distinguish "pool not yet synced" from "pool
     * is genuinely empty", surviving activity recreation and cases where the startup warm-up synced
     * the pool before the bind sheet composition existed.
     *
     * Checks **either** the ETag resource (written when the server includes an `ETag` header) **or**
     * the sync-marker resource (written on every successful `200`, even when the server omits `ETag`).
     * Both are absent only when no successful fetch has ever occurred for this race from this source.
     */
    suspend fun hasBeenSynced(raceId: Int, source: SyncSource = SyncSource.Cloud): Boolean {
        val originKey = when (source) {
            SyncSource.Cloud -> origin
            SyncSource.Local -> localOrigin
        }
        return syncMetaDao.getEtag(originKey, memberTagsResource(raceId)) != null ||
            syncMetaDao.getEtag(originKey, memberTagsSyncedResource(raceId)) != null
    }

    /**
     * Fetches `/app/race/<raceId>/member_tags/` with the stored ETag and, on `200`, replaces that
     * race's member-tag rows, then saves the new ETag. Like [LegendRepository.refreshLegend], the
     * data write and the ETag write are separate transactions on purpose: a crash between them leaves
     * fresh data with a stale ETag, so the next refresh gets another `200` and self-heals.
     *
     * Guarded against clobbering a pinned race: a [SyncSource.Cloud] call for a currently-pinned
     * [raceId], and symmetrically a [SyncSource.Local] call for a [raceId] **not** (or no longer)
     * pinned, returns [RefreshResult.Skipped] **before** hitting the network, and the guard is
     * re-checked before persisting a `200` (an in-flight response for the source that just lost
     * relevance — e.g. the switch flipped mid-flight — must not overwrite fresher rows from the
     * other source).
     */
    suspend fun refreshMemberTags(raceId: Int, source: SyncSource = SyncSource.Cloud): RefreshResult {
        if (source == SyncSource.Cloud && isRacePinned(raceId)) return RefreshResult.Skipped
        if (source == SyncSource.Local && !isRacePinned(raceId)) return RefreshResult.Skipped
        val (client, originKey) = when (source) {
            SyncSource.Cloud -> apiClient to origin
            SyncSource.Local -> localApiClient to localOrigin
        }
        val resource = memberTagsResource(raceId)
        val etag = syncMetaDao.getEtag(originKey, resource)
        return when (val result = client.fetchMemberTags(raceId, etag)) {
            is FetchResult.Success -> {
                if (source == SyncSource.Cloud && isRacePinned(raceId)) return RefreshResult.Skipped
                if (source == SyncSource.Local && !isRacePinned(raceId)) return RefreshResult.Skipped
                // The two origins share this table: a stale ETag **or** sync-marker on the origin
                // not just written could earn a 304 (ETag) or a false hasBeenSynced() (marker) on
                // the next switch-back and skip re-syncing over rows this write is about to replace.
                // Cleared **before** the replace (not after) so a crash mid-write can't strand
                // either stale marker masking the fact that this write never landed.
                val otherOriginKey = when (source) {
                    SyncSource.Cloud -> localOrigin
                    SyncSource.Local -> origin
                }
                syncMetaDao.deleteEtag(otherOriginKey, resource)
                syncMetaDao.deleteEtag(otherOriginKey, memberTagsSyncedResource(raceId))
                memberTagDao.replaceAllForRace(
                    raceId = raceId,
                    tags = result.data.memberTags.map { it.toEntity(raceId) },
                )
                if (result.etag != null) {
                    syncMetaDao.upsert(SyncMetaEntity(originKey, resource, result.etag))
                } else {
                    // No ETag from the server: write a sync-marker so hasBeenSynced() returns true
                    // after activity recreation even when the pool is empty (an ETag-less empty-pool
                    // response is a legitimate server response, not a "not yet synced" condition).
                    syncMetaDao.upsert(SyncMetaEntity(originKey, memberTagsSyncedResource(raceId), "1"))
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
