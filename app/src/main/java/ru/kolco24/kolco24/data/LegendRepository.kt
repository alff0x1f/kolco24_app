package ru.kolco24.kolco24.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.FetchResult
import ru.kolco24.kolco24.data.api.dto.CheckpointDto
import ru.kolco24.kolco24.data.api.dto.TagDto
import ru.kolco24.kolco24.data.crypto.EncBlob
import ru.kolco24.kolco24.data.crypto.LegendCrypto
import ru.kolco24.kolco24.data.crypto.UnlockResult
import ru.kolco24.kolco24.data.crypto.UnlockTag
import kotlinx.coroutines.flow.map
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.LegendMetaDao
import ru.kolco24.kolco24.data.db.LegendMetaEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity
import ru.kolco24.kolco24.data.db.TagDao
import ru.kolco24.kolco24.data.db.TagEntity

/** Per-race `sync_meta` resource for the legend endpoint (see [SyncMetaEntity]). */
private fun legendResource(raceId: Int): String = "race/$raceId/legend"

/**
 * Single source of truth for one race's legend (checkpoints + NFC tags). Room holds the data; the
 * network only updates it. The UI reads [checkpointsForRace]; [refreshLegend] performs a conditional
 * fetch and, on `200`, fully replaces that race's local checkpoint and tag rows.
 *
 * Locked checkpoints can be revealed **offline** via [unlock]: the scanned tag's `code` runs through
 * [LegendCrypto] and the decrypted `{cost, description}` is persisted onto the matching checkpoint
 * rows (the row's `locked` flag is cleared to `false` and its content fills in).
 *
 * @param origin base URL the data is associated with — used as the ETag partition key in `sync_meta`.
 */
class LegendRepository(
    private val apiClient: ApiClient,
    private val checkpointDao: CheckpointDao,
    private val tagDao: TagDao,
    private val legendMetaDao: LegendMetaDao,
    private val syncMetaDao: SyncMetaDao,
    private val origin: String,
    private val json: Json,
) {
    /** Offline-readable checkpoints of one race, ordered by number then id. */
    fun checkpointsForRace(raceId: Int): Flow<List<CheckpointEntity>> =
        checkpointDao.observeCheckpointsForRace(raceId)

    /**
     * Offline-readable sum of **all** checkpoint costs of one race (open + locked) — the correct
     * denominator for the legend progress bar. Emits `0` until the first `200` populates `legend_meta`
     * (no row yet), which collapses the bar to 0% rather than skewing it; the server always sends the
     * field, so this is only the pre-sync window.
     */
    fun totalCostForRace(raceId: Int): Flow<Int> =
        legendMetaDao.observeForRace(raceId).map { it?.totalCost ?: 0 }

    /** One-shot snapshot — re-reads after an offline [unlock] to get the just-revealed cost. */
    suspend fun checkpointsSnapshot(raceId: Int): List<CheckpointEntity> =
        checkpointDao.getCheckpointsForRace(raceId)

    /**
     * Offline-readable NFC tags of one race (one row per bound chip). The admin provisioning flow
     * groups these by `point` to pre-seed each КП's «уже привязано» count.
     */
    fun tagsForRace(raceId: Int): Flow<List<TagEntity>> =
        tagDao.observeTagsForRace(raceId)

    /**
     * Fetches `/app/race/<raceId>/legend/` with the stored ETag and, on `200`, replaces that race's
     * checkpoints **and** tags, then saves the new ETag. Like [TeamRepository.refreshTeams], the data
     * writes and the ETag write are separate transactions on purpose: a crash between them leaves
     * fresh data with a stale ETag, so the next refresh gets another `200` and self-heals.
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
                tagDao.replaceAllForRace(
                    raceId = raceId,
                    tags = response.tags.map { it.toEntity(raceId) },
                )
                legendMetaDao.upsert(LegendMetaEntity(raceId, response.totalCost))
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

    /**
     * Offline-decrypts a scanned tag and persists any revealed checkpoint plaintext.
     *
     * The 16-byte NFC [code] is hashed to a `bid` and looked up in [TagDao]; the [LegendCrypto] engine
     * does the actual crypto (this method only owns the DB lookup, the entity→[EncBlob] map, and
     * persistence). Each revealed CP is written via [CheckpointDao.reveal] — `locked` is cleared to
     * `false`.
     *
     * @return an [UnlockOutcome]: [UnlockOutcome.Unknown] when no tag matches the `bid`,
     *   [UnlockOutcome.IdentityOnly] for an open-CP tag (nothing to decrypt),
     *   [UnlockOutcome.Revealed] (with the persisted CP ids) on success, or [UnlockOutcome.Failed] on
     *   any crypto/parse error.
     */
    suspend fun unlock(raceId: Int, code: ByteArray): UnlockOutcome {
        val bid = LegendCrypto.bid(code)
        val tagEntity = tagDao.getByBid(bid, raceId) ?: return UnlockOutcome.Unknown
        if (tagEntity.iv == null && tagEntity.ct == null) return UnlockOutcome.IdentityOnly(tagEntity.point)
        if (tagEntity.iv == null || tagEntity.ct == null) return UnlockOutcome.Failed("malformed tag envelope")
        val encById = checkpointDao.getCheckpointsForRace(raceId)
            .mapNotNull { cp ->
                val iv = cp.encIv
                val ct = cp.encCt
                if (iv != null && ct != null) cp.id to EncBlob(iv, ct) else null
            }
            .toMap()
        val result = withContext(Dispatchers.Default) {
            LegendCrypto.unlock(
                code = code,
                tag = UnlockTag(tagEntity.point, tagEntity.iv, tagEntity.ct),
                encById = encById,
                json = json,
            )
        }
        return when (result) {
            is UnlockResult.Revealed -> {
                for (cp in result.checkpoints) {
                    checkpointDao.reveal(cp.id, cp.cost, cp.description)
                }
                UnlockOutcome.Revealed(result.point, result.checkpoints.map { it.id })
            }
            is UnlockResult.IdentityOnly -> UnlockOutcome.IdentityOnly(result.point)
            is UnlockResult.Failed -> UnlockOutcome.Failed(result.reason)
        }
    }
}

/**
 * Persistence-aware outcome of [LegendRepository.unlock] (the repo's translation of the engine's
 * [UnlockResult]). [Unknown] has no engine counterpart — it means the scanned `bid` matched no tag.
 */
sealed interface UnlockOutcome {
    /** Revealed [checkpointIds] were decrypted and persisted; the tag belongs to [point]. */
    data class Revealed(val point: Int, val checkpointIds: List<Int>) : UnlockOutcome

    /** Open-CP tag: only identifies its [point], nothing to decrypt. */
    data class IdentityOnly(val point: Int) : UnlockOutcome

    /** No tag matched the scanned `bid` (unknown tag for this race set). */
    data object Unknown : UnlockOutcome

    /** A crypto or parse failure (wrong key, tamper, malformed bundle). */
    data class Failed(val reason: String) : UnlockOutcome
}

/**
 * Maps a network DTO to the persisted entity, stamping the owning [raceId]. A locked CP arrives with
 * an `enc` envelope and no `cost`/`description`; an open CP carries its content directly.
 * [CheckpointDao.replaceAllForRace] preserves any prior offline reveal. `color` is race-scoped public
 * data present in both branches, so it passes straight through (no preserve-on-resync handling needed).
 */
private fun CheckpointDto.toEntity(raceId: Int): CheckpointEntity = CheckpointEntity(
    id = id,
    raceId = raceId,
    number = number,
    cost = cost,
    type = type,
    description = description,
    locked = enc != null,
    encIv = enc?.iv,
    encCt = enc?.ct,
    color = color ?: "",
)

/** Maps a `tags[]` DTO to its persisted entity (1:1), stamping the owning [raceId]. */
private fun TagDto.toEntity(raceId: Int): TagEntity = TagEntity(
    bid = bid,
    raceId = raceId,
    point = point,
    checkMethod = checkMethod,
    iv = iv,
    ct = ct,
)
