package ru.kolco24.kolco24.data.track

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.TrackPointDto
import ru.kolco24.kolco24.data.api.dto.TrackUploadResponse
import ru.kolco24.kolco24.data.api.dto.toDto
import ru.kolco24.kolco24.data.db.TrackDao
import ru.kolco24.kolco24.data.db.TrackPointEntity
import ru.kolco24.kolco24.data.time.TrustedClock

/**
 * One upload target (cloud HTTPS / local LAN cleartext). A thin functional seam over
 * `ApiClient.uploadTrack` so [TrackRepository]'s upload loop is unit-testable with a fake (mirrors the
 * pure-seam convention, e.g. `NfcTransport`); in production each target is one `ApiClient` instance.
 */
fun interface TrackUploader {
    suspend fun upload(raceId: Int, teamId: Int, points: List<TrackPointDto>): PostResult<TrackUploadResponse>
}

/**
 * Single source of truth for the **local-only** GPS track points. Wraps [TrackDao] and owns the
 * impure mapping from a pure [RawFix] to a stored [TrackPointEntity] — the recording service only
 * forwards `List<RawFix>`, no time logic lives there. It also owns the **dual batch upload** (Phase 2):
 * each target ([localUploader]/[cloudUploader]) flushes independently, idempotently (upsert by client
 * id), with per-target `uploaded*` flags.
 *
 * Dependencies are injected so the mapping is deterministic and unit-testable (mirrors `MarkRepository`
 * + the `TrustedClock` injection convention): [trustedClock] derives the per-point trusted time,
 * [bootCountProvider] is the current boot session (a fix is always captured in the running session),
 * [idFactory] mints the client UUID, and [wallProvider]/[elapsedProvider] read the wall and monotonic
 * clocks once per batch for the wall back-projection.
 */
class TrackRepository(
    private val trackDao: TrackDao,
    private val trustedClock: TrustedClock,
    private val bootCountProvider: () -> Int?,
    private val idFactory: () -> String,
    private val wallProvider: () -> Long,
    private val elapsedProvider: () -> Long,
    private val cloudUploader: TrackUploader = TrackUploader { _, _, _ -> PostResult.Offline },
    private val localUploader: TrackUploader = TrackUploader { _, _, _ -> PostResult.Offline },
) {
    /**
     * Guards [uploadPending]/[uploadAllPending] against concurrent entry (a service-stop flush and a
     * Launch B opportunistic flush firing at once). `tryLock()` — not `lock()` — so the loser skips
     * rather than queues: the same rows would otherwise be POSTed twice, and the next trigger flushes
     * whatever the winner did not.
     */
    private val uploadMutex = Mutex()

    /**
     * Map a batch of raw fixes to entities and persist them. The wall/monotonic clocks and the boot
     * session are read **once per batch** (`wallNow`/`elapsedNow`/`bootAt`); then each fix gets:
     * - `elapsedAt = elapsedRealtimeNanos / 1_000_000` — the monotonic moment of the fix;
     * - `trustedMs = trustedClock.trustedAt(elapsedAt, bootAt)` — trusted time of the **fix**, NULL
     *   when no clock sync (so batched points keep their real capture time, not the delivery time);
     * - `wallMs = wallNow + (elapsedAt − elapsedNow)` — wall back-projected to the fix moment, so the
     *   per-point wall fallback (mirror of `MarkEntity` `trusted ?: wall`) is honest under Fused
     *   batching where the whole batch is inserted at one wall instant.
     *
     * An empty batch is a no-op (avoids snapshotting clocks for nothing).
     */
    suspend fun insertAll(rawFixes: List<RawFix>, raceId: Int, teamId: Int) {
        if (rawFixes.isEmpty()) return
        val wallNow = wallProvider()
        val elapsedNow = elapsedProvider()
        val bootAt = bootCountProvider()
        val entities = rawFixes.map { fix ->
            val elapsedAt = fix.elapsedRealtimeNanos / 1_000_000
            fix.toTrackPoint(
                raceId = raceId,
                teamId = teamId,
                wallMs = wallNow + (elapsedAt - elapsedNow),
                trustedMs = trustedClock.trustedAt(elapsedAt, bootAt),
                bootCount = bootAt,
                idFactory = idFactory,
            )
        }
        trackDao.insertAll(entities)
    }

    /** Live track points for one team, ordered by `elapsedRealtimeAt` (capture order). */
    fun observeTrack(teamId: Int): Flow<List<TrackPointEntity>> = trackDao.observeForTeam(teamId)

    /** Live point count for one team. */
    fun countForTeam(teamId: Int): Flow<Int> = trackDao.countForTeam(teamId)

    /** Wipe a team's track (the «Очистить трек» action; only while not recording). */
    suspend fun deleteForTeam(teamId: Int) = trackDao.deleteForTeam(teamId)

    /**
     * Flush all pending points for one `(raceId, teamId)` to **both** targets independently — a target
     * failing (offline/`403`) never blocks the other. Called on recording stop. Guarded so two flushes
     * never double-send; the loser is a no-op (the next trigger catches up).
     */
    suspend fun uploadPending(raceId: Int, teamId: Int) {
        if (!uploadMutex.tryLock()) return
        try {
            flushScope(raceId, teamId)
        } finally {
            uploadMutex.unlock()
        }
    }

    /**
     * Opportunistic re-send across **every** pending scope, not just the current selection — so points
     * stranded under an old race/team still flush. Walks [TrackDao.pendingUploadScopes]. Same
     * concurrency guard as [uploadPending].
     */
    suspend fun uploadAllPending() {
        if (!uploadMutex.tryLock()) return
        try {
            for (scope in trackDao.pendingUploadScopes()) {
                flushScope(scope.raceId, scope.teamId)
            }
        } finally {
            uploadMutex.unlock()
        }
    }

    /** Flush one scope to both targets in turn; each target's loop is independent of the other's. */
    private suspend fun flushScope(raceId: Int, teamId: Int) {
        uploadLoop(
            fetch = { trackDao.unuploadedLocal(raceId, teamId, UPLOAD_BATCH) },
            upload = { localUploader.upload(raceId, teamId, it) },
            mark = { trackDao.markUploadedLocal(it) },
        )
        uploadLoop(
            fetch = { trackDao.unuploadedCloud(raceId, teamId, UPLOAD_BATCH) },
            upload = { cloudUploader.upload(raceId, teamId, it) },
            mark = { trackDao.markUploadedCloud(it) },
        )
    }

    /**
     * Drain one target in batches until done or stuck. Terminates on: empty fetch (all sent); a
     * non-`Success` response (offline/error — retry next trigger); or **no forward progress** (none of
     * the fetched batch's ids came back accepted, e.g. empty or a foreign subset). Only ids that are
     * both `accepted` **and** in the fetched batch are marked — so a strange response can never mark a
     * row out of this scope, and a marked row strictly shrinks the pending set, guaranteeing exit.
     */
    private suspend fun uploadLoop(
        fetch: suspend () -> List<TrackPointEntity>,
        upload: suspend (List<TrackPointDto>) -> PostResult<TrackUploadResponse>,
        mark: suspend (List<String>) -> Unit,
    ) {
        while (true) {
            val batch = fetch()
            if (batch.isEmpty()) break
            val result = upload(batch.map { it.toDto() })
            if (result !is PostResult.Success) break
            val batchIds = batch.mapTo(HashSet()) { it.id }
            val toMark = result.data.accepted.filter { it in batchIds }
            if (toMark.isEmpty()) break // no progress → stop, catch up on the next trigger
            mark(toMark)
        }
    }

    private companion object {
        /** Max points per upload request; the scoped `unuploaded*` queries `LIMIT` to this. */
        const val UPLOAD_BATCH = 500
    }
}
