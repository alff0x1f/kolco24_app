package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.MarkDto
import ru.kolco24.kolco24.data.api.dto.MarkUploadResponse
import ru.kolco24.kolco24.data.api.dto.toDto
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.db.MarkMemberSnapshot
import ru.kolco24.kolco24.data.db.PhotoFrameRow
import ru.kolco24.kolco24.data.db.TrackScope
import ru.kolco24.kolco24.data.db.UploadCounts
import ru.kolco24.kolco24.data.marks.encodePhotoPaths
import ru.kolco24.kolco24.data.marks.frameIdOf
import ru.kolco24.kolco24.data.marks.photoPaths
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.data.track.RawFix
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.UploadTarget
import ru.kolco24.kolco24.data.track.uploadResultKind

/**
 * One upload target (cloud HTTPS / local LAN cleartext). A thin functional seam over
 * `ApiClient.uploadMarks` so [MarkRepository]'s upload loop is unit-testable with a fake (mirrors
 * [ru.kolco24.kolco24.data.track.TrackUploader]); in production each target is one `ApiClient` instance.
 * Unlike the track seam, [upload] carries [sourceInstallId] — the marks body requires the device-
 * provenance UUID inside the signed body.
 */
fun interface MarkUploader {
    suspend fun upload(
        raceId: Int,
        teamId: Int,
        sourceInstallId: String,
        marks: List<MarkDto>,
    ): PostResult<MarkUploadResponse>
}

/**
 * One upload target's binary frame endpoint. A thin functional seam over `ApiClient.uploadMarkPhoto`
 * (mirrors [MarkUploader]) so the frame drain in [MarkRepository] is unit-testable with a fake; in
 * production each target is one `ApiClient` instance. **Defaults to [PostResult.Offline]** — never a
 * success/no-op — so a missed wiring leaves frames pending instead of falsely marking them uploaded.
 */
fun interface PhotoFrameUploader {
    suspend fun upload(raceId: Int, markId: String, frameId: String, bytes: ByteArray): PostResult<Unit>
}

/**
 * Reads one frame's raw bytes off disk given its relative path (`marks/<markId>/<uuid>.jpg`). Wired in
 * `AppContainer` to `File(filesDir, relPath).readBytes()`; a missing/unreadable file returns `null`,
 * which [MarkRepository]'s frame drain treats like a hard per-frame failure (leave the mark pending,
 * move on to the next mark — never silently "uploaded").
 */
fun interface PhotoFrameReader {
    fun read(relPath: String): ByteArray?
}

/**
 * Single source of truth for the **local-only** checkpoint-taking events (взятия КП). Wraps [MarkDao]
 * for the event rows. Includes a dual-target (cloud + local LAN) idempotent batch upload loop mirroring
 * [ru.kolco24.kolco24.data.track.TrackRepository].
 *
 * A take is a two-phase row: [startKpTake] is called the moment the КП chip is scanned (creating a
 * row with a client UUID so the take survives process death and merges cleanly across two servers),
 * then [addMember] accumulates each member's `numberInTeam` within the rolling scan window. Whenever a
 * row's `present` set covers the whole roster ([MarkEntity.expectedCount]) it becomes [MarkEntity.complete]
 * (= scored). A partial collect is stored for the future server log but not scored, and a repeat take of
 * the same checkpoint produces a **new** row.
 *
 * "Взято" is **not** written back onto the checkpoint row: it is team-scoped (a checkpoint shared by a
 * race's teams would otherwise leak one team's progress onto another's), so the legend derives it from
 * this team's complete marks via [takenPoints].
 */
class MarkRepository(
    private val markDao: MarkDao,
    private val sourceInstallId: String = "",
    private val cloudUploader: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
    private val localUploader: MarkUploader = MarkUploader { _, _, _, _ -> PostResult.Offline },
    private val cloudPhotoUploader: PhotoFrameUploader = PhotoFrameUploader { _, _, _, _ -> PostResult.Offline },
    private val localPhotoUploader: PhotoFrameUploader = PhotoFrameUploader { _, _, _, _ -> PostResult.Offline },
    private val photoFrameReader: PhotoFrameReader = PhotoFrameReader { null },
    private val onUploadOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> },
) {
    /**
     * Guards [uploadPending]/[uploadAllPending] against concurrent entry (a take-complete flush and a
     * Launch B opportunistic flush firing at once). `tryLock()` — not `lock()` — so the loser skips
     * rather than queues: the same rows would otherwise be POSTed twice, and the next trigger flushes
     * whatever the winner did not.
     */
    private val uploadMutex = Mutex()

    /** Live take events for one team, newest first. */
    fun observeMarks(teamId: Int): Flow<List<MarkEntity>> = markDao.observeForTeam(teamId)

    /** Live per-target upload counts (total / uploadedLocal / uploadedCloud) for one scope. */
    fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts> = markDao.uploadCounts(teamId, raceId)

    /** Live per-target upload counts ignoring photo-frame state — "did the take row reach the server?" */
    fun uploadCountsMetadata(teamId: Int, raceId: Int): Flow<UploadCounts> =
        markDao.uploadCountsMetadata(teamId, raceId)

    /** Live per-target photo-frame upload counts (frame-granular), folded via [foldPhotoFrameCounts]. */
    fun photoFrameCounts(teamId: Int, raceId: Int): Flow<UploadCounts> =
        markDao.photoFrameRows(teamId, raceId).map(::foldPhotoFrameCounts)

    /**
     * Open a new take for [checkpointId] (КП chip just scanned). Generates a fresh UUID, snapshots the
     * checkpoint metadata ([number]/[cost]) and roster size ([expectedCount]), seeds the take from any
     * member snapshots already buffered before the chip ([bufferedMembers]), recomputes `complete`, and
     * upserts the row. Returns the new id.
     *
     * [bufferedMembers] carries one [MarkMemberSnapshot] per buffered member: both `present`
     * ([MarkMemberSnapshot.numberInTeam], the scoring truth) and `presentDetails` (the upload snapshots)
     * derive from **one** `distinctBy { numberInTeam }` pass — deduplicating once is essential, since a
     * doubled slot would inflate `present.size` and flip `complete` early.
     *
     * The take time comes from a [TimeSample] captured at the moment of the touch: `takenAt`/`updatedAt`
     * keep the raw wall ([TimeSample.wallMs]), `trustedTakenAt` gets the monotonic-anchored trusted time
     * ([TimeSample.trustedMs], NULL when no clock sync has happened), and `elapsedRealtimeAt`/`bootCount`
     * record the monotonic mark plus its boot session for forensic Δelapsed reconciliation.
     */
    suspend fun startKpTake(
        raceId: Int,
        teamId: Int,
        checkpointId: Int,
        number: Int,
        cost: Int,
        cpUid: String,
        cpCode: String,
        expectedCount: Int,
        bufferedMembers: Collection<MarkMemberSnapshot>,
        sample: TimeSample,
    ): String {
        val id = UUID.randomUUID().toString()
        // Both present (scoring truth) and presentDetails (upload snapshots) come from one distinct pass.
        val distinct = bufferedMembers.distinctBy { it.numberInTeam }
        val present = distinct.map { it.numberInTeam }
        val complete = expectedCount > 0 && present.size >= expectedCount
        markDao.upsert(
            MarkEntity(
                id = id,
                raceId = raceId,
                teamId = teamId,
                checkpointId = checkpointId,
                checkpointNumber = number,
                cost = cost,
                method = "nfc",
                cpUid = cpUid,
                cpCode = cpCode,
                present = present,
                presentDetails = distinct,
                expectedCount = expectedCount,
                complete = complete,
                takenAt = sample.wallMs,
                updatedAt = sample.wallMs,
                trustedTakenAt = sample.trustedMs,
                elapsedRealtimeAt = sample.elapsedMs,
                bootCount = sample.bootCount,
            ),
        )
        return id
    }

    /**
     * Add one member ([member]) to the take [markId] with set semantics (idempotent rescan). The
     * snapshot drives both `present` (its [MarkMemberSnapshot.numberInTeam], the scoring truth) and
     * `presentDetails` (the snapshot itself, the upload source). A missing row is a no-op. [checkpointId]
     * is unused now that scoring is derived (kept for the call-site's readability and a future
     * per-checkpoint recompute), so it is accepted but not consulted.
     */
    suspend fun addMember(
        markId: String,
        checkpointId: Int,
        member: MarkMemberSnapshot,
        expectedCount: Int,
        sample: TimeSample,
    ) {
        markDao.addMember(
            id = markId,
            numberInTeam = member.numberInTeam,
            nfcUid = member.nfcUid,
            number = member.number,
            code = member.code,
            now = sample.wallMs,
            expectedCount = expectedCount,
        )
    }

    /**
     * Create a **standalone photo-mark** ([markId] already minted by the entry point so the captured
     * frames were written under `marks/<markId>/` before the row existed — see the Phase-1 plan). The row
     * is a hybrid take: `method = "photo"`, `complete = true` (scored locally), `present = emptyList()`
     * (the composition is not asserted — only that the team reached the КП), and `cpUid`/`cpCode` are
     * empty (no chip was read).
     *
     * [cp] is the resolved checkpoint; `cost` falls back to `0` for a still-locked КП ([CheckpointEntity.cost]
     * is null while locked) — the legend's live-cost resolver picks up the real value after a later reveal.
     * [expectedCount] is the roster size, stored for the server log only — it does **not** drive `complete`
     * here (set explicitly). [paths] are the **relative** photo paths; they are JSON-encoded into
     * [MarkEntity.photoPath]. Times come from [sample] (captured at the first saved frame), exactly as
     * [startKpTake] persists an NFC take's [TimeSample].
     *
     * The anti-cheat coordinate is attached separately via [attachLocation] (the same column-scoped path
     * the NFC new-take branch uses); the entry-point wiring fires a one-shot `currentLocationProvider`.
     */
    suspend fun createPhotoMark(
        markId: String,
        cp: CheckpointEntity,
        raceId: Int,
        teamId: Int,
        paths: List<String>,
        expectedCount: Int,
        sample: TimeSample,
    ) {
        markDao.upsert(
            MarkEntity(
                id = markId,
                raceId = raceId,
                teamId = teamId,
                checkpointId = cp.id,
                checkpointNumber = cp.number,
                cost = cp.cost ?: 0,
                method = "photo",
                cpUid = "",
                cpCode = "",
                present = emptyList(),
                presentDetails = null,
                expectedCount = expectedCount,
                complete = true,
                photoPath = encodePhotoPaths(paths),
                takenAt = sample.wallMs,
                updatedAt = sample.wallMs,
                trustedTakenAt = sample.trustedMs,
                elapsedRealtimeAt = sample.elapsedMs,
                bootCount = sample.bootCount,
            ),
        )
    }

    /**
     * Append [newPaths] to take [markId]'s photo list (column-scoped via [MarkDao.attachPhotos]; only
     * `photoPath`/`updatedAt` change, `uploaded*` is left intact — `photoPath` is not in the marks DTO).
     * A missing row is a no-op. [now] bumps `updatedAt`. Used by the "photo after NFC" auto-attach path.
     */
    suspend fun attachPhotos(markId: String, newPaths: List<String>, now: Long) {
        markDao.attachPhotos(markId, newPaths, now)
    }

    /**
     * Attach the take-place GPS fix to take [markId] (second phase, fire-and-forget like a late
     * [addMember]). A null [fix] (no permission / GPS off / no provider / timeout / stale-cache reject)
     * is a no-op — the take row simply keeps `locLat == null`, its "no coordinate" sentinel. Otherwise
     * the 7 `loc*` columns are written column-scoped (see [MarkDao.attachLocation]); the monotonic
     * [RawFix.elapsedRealtimeNanos] is converted to millis for `locElapsedRealtimeAt`.
     */
    suspend fun attachLocation(markId: String, fix: RawFix?) {
        if (fix == null) return
        markDao.attachLocation(
            id = markId,
            lat = fix.lat,
            lon = fix.lon,
            accuracy = fix.accuracy.takeIf { it != Float.MAX_VALUE },
            altitude = fix.altitude,
            verticalAccuracy = fix.verticalAccuracyMeters,
            gpsTimeMs = fix.gpsTimeMs.takeIf { it > 0L },
            elapsedRealtimeAt = fix.elapsedRealtimeNanos / 1_000_000,
        )
    }

    /**
     * Flush all pending marks for one `(raceId, teamId)` to **both** targets independently — a target
     * failing (offline/`403`) never blocks the other. Guarded so two flushes never double-send; the
     * loser is a no-op (the next trigger catches up).
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
     * Opportunistic re-send across **every** pending scope, not just the current selection — so marks
     * stranded under an old race/team still flush. Walks [MarkDao.pendingUploadScopes]. Same concurrency
     * guard as [uploadPending].
     */
    suspend fun uploadAllPending() {
        if (!uploadMutex.tryLock()) return
        try {
            for (scope in markDao.pendingUploadScopes()) {
                flushScope(scope.raceId, scope.teamId)
            }
        } finally {
            uploadMutex.unlock()
        }
    }

    /**
     * Flush one scope to both targets in turn; each target's loop is independent of the other's. Per
     * target, the metadata loop ([uploadLoop]) runs first, then the frame drain ([frameDrainLoop]) —
     * metadata-first ordering (the frame-pending DAO queries already gate on `uploadedX = 1`, so this
     * is belt-and-braces). The two results are combined into **one** [onUploadOutcome] call via
     * [combineOutcome] so a frame `Ok` can never mask a metadata `Error`/`Offline`; a combined `null`
     * means neither loop attempted anything, so the prior outcome is left untouched (an idle re-flush
     * never overwrites a real «ошибка» with a misleading «ok»).
     */
    private suspend fun flushScope(raceId: Int, teamId: Int) {
        val scope = TrackScope(raceId, teamId)

        val localMeta = uploadLoop(
            fetch = { markDao.unuploadedLocal(raceId, teamId, UPLOAD_BATCH) },
            upload = { localUploader.upload(raceId, teamId, sourceInstallId, it) },
            mark = { batch, ids -> markLocalGpsAware(batch, ids) },
        )
        val localFrame = frameDrainLoop(
            fetch = { markDao.framePendingLocal(raceId, teamId, UPLOAD_BATCH) },
            upload = { markId, frameId, bytes -> localPhotoUploader.upload(raceId, markId, frameId, bytes) },
            markDone = { id, updatedAt -> markDao.setPhotosUploadedLocalIfUnchanged(id, updatedAt) },
        )
        combineOutcome(localMeta, localFrame)?.let { onUploadOutcome(scope, UploadTarget.Local, it) }

        val cloudMeta = uploadLoop(
            fetch = { markDao.unuploadedCloud(raceId, teamId, UPLOAD_BATCH) },
            upload = { cloudUploader.upload(raceId, teamId, sourceInstallId, it) },
            mark = { batch, ids -> markCloudGpsAware(batch, ids) },
        )
        val cloudFrame = frameDrainLoop(
            fetch = { markDao.framePendingCloud(raceId, teamId, UPLOAD_BATCH) },
            upload = { markId, frameId, bytes -> cloudPhotoUploader.upload(raceId, markId, frameId, bytes) },
            markDone = { id, updatedAt -> markDao.setPhotosUploadedCloudIfUnchanged(id, updatedAt) },
        )
        combineOutcome(cloudMeta, cloudFrame)?.let { onUploadOutcome(scope, UploadTarget.Cloud, it) }
    }

    /**
     * Mark rows as locally uploaded with two guards per row:
     *
     * 1. **[updatedAt] version guard** — if [addMember] mutated the row between the batch fetch and
     *    this call (bumping [MarkEntity.updatedAt]), the guard fails and the row stays
     *    `uploadedLocal = 0` so the next trigger re-fetches and re-uploads the updated present list.
     * 2. **`locLat IS NULL` guard** (rows without GPS only) — if [attachLocation] wrote a fix
     *    between DTO creation and this call, the row is left un-marked so the next trigger
     *    re-uploads it with the GPS coordinate.
     *
     * The two guards are orthogonal: [addMember] bumps [MarkEntity.updatedAt] but does not touch
     * `locLat`; [attachLocation] sets `locLat` but does not bump [MarkEntity.updatedAt].
     */
    private suspend fun markLocalGpsAware(batch: List<MarkEntity>, ids: List<String>) {
        val idToEntity = batch.associateBy { it.id }
        for (id in ids) {
            val entity = idToEntity[id] ?: continue
            if (entity.locLat != null) {
                markDao.markUploadedLocalIfUnchanged(id, entity.updatedAt)
            } else {
                markDao.markUploadedLocalIfUnchangedAndNoLocation(id, entity.updatedAt)
            }
        }
    }

    /** Same dual-guard as [markLocalGpsAware] for the cloud target. */
    private suspend fun markCloudGpsAware(batch: List<MarkEntity>, ids: List<String>) {
        val idToEntity = batch.associateBy { it.id }
        for (id in ids) {
            val entity = idToEntity[id] ?: continue
            if (entity.locLat != null) {
                markDao.markUploadedCloudIfUnchanged(id, entity.updatedAt)
            } else {
                markDao.markUploadedCloudIfUnchangedAndNoLocation(id, entity.updatedAt)
            }
        }
    }

    /**
     * Drain one target in batches until done or stuck, returning the terminal [UploadResultKind] or
     * `null`. Terminates on: empty first fetch (**nothing pending** → `null`, no attempt made); drained
     * to empty after marking progress (→ [UploadResultKind.Ok]); a non-`Success` response (offline/error
     * — mapped via [uploadResultKind], retry next trigger); or **no forward progress** (none of the
     * fetched batch's ids came back accepted → [UploadResultKind.Error], **not** the `Ok` that
     * `uploadResultKind(Success)` would give). Only ids both `accepted` **and** in the fetched batch are
     * marked — so a strange response can never mark a row out of this scope, and a marked row strictly
     * shrinks the pending set, guaranteeing exit.
     */
    private suspend fun uploadLoop(
        fetch: suspend () -> List<MarkEntity>,
        upload: suspend (List<MarkDto>) -> PostResult<MarkUploadResponse>,
        mark: suspend (List<MarkEntity>, List<String>) -> Unit,
    ): UploadResultKind? {
        var progressed = false
        while (true) {
            val batch = fetch()
            if (batch.isEmpty()) return if (progressed) UploadResultKind.Ok else null
            val result = upload(batch.map { it.toDto() })
            if (result !is PostResult.Success) return uploadResultKind(result) // Offline / Error
            val batchIds = batch.mapTo(HashSet()) { it.id }
            val toMark = result.data.accepted.filter { it in batchIds }
            if (toMark.isEmpty()) return UploadResultKind.Error // no progress → stop, catch up next trigger
            mark(batch, toMark)
            progressed = true
        }
    }

    /**
     * Drain one target's photo frames in batches until done or stuck, returning the terminal
     * [UploadResultKind] or `null` (nothing pending — mirrors [uploadLoop]'s contract exactly).
     *
     * Per mark in the fetched batch, [uploadOneMarksFrames] is tried: a **transient/target-wide**
     * failure (`Offline`/`403`/`404`/`429`/`401`/`409`/`5xx`/other `Error`) stops the **whole target**
     * immediately (no point issuing N doomed requests while the endpoint is down); a **hard per-frame**
     * failure (`400`, or `413` → `Error(413)`) or a **null read** (missing/unreadable file) leaves that
     * mark pending and moves on to the next mark in the batch (one poison frame must not block later
     * good marks). When all of a mark's frames are accepted, [markDone] flips its per-target flag
     * (version-guarded by `updatedAt` — see [MarkDao.setPhotosUploadedLocalIfUnchanged]).
     *
     * Terminates on **no forward progress** (a pass that flips zero marks → [UploadResultKind.Error]),
     * so a poison-only / missing-file-only batch stays visibly pending instead of spinning forever —
     * the same stuck-detection [uploadLoop] uses.
     */
    private suspend fun frameDrainLoop(
        fetch: suspend () -> List<MarkEntity>,
        upload: suspend (markId: String, frameId: String, bytes: ByteArray) -> PostResult<Unit>,
        markDone: suspend (id: String, updatedAt: Long) -> Unit,
    ): UploadResultKind? {
        var progressed = false
        while (true) {
            val batch = fetch()
            if (batch.isEmpty()) return if (progressed) UploadResultKind.Ok else null
            var flippedThisPass = false
            for (mark in batch) {
                when (val result = uploadOneMarksFrames(mark, upload)) {
                    FrameMarkResult.Flipped -> {
                        markDone(mark.id, mark.updatedAt)
                        flippedThisPass = true
                    }
                    FrameMarkResult.Pending -> Unit
                    is FrameMarkResult.Stop -> return result.kind
                }
            }
            if (!flippedThisPass) return UploadResultKind.Error // no progress → stop, catch up next trigger
            progressed = true
        }
    }

    /** Outcome of attempting every frame of one mark within [frameDrainLoop]. */
    private sealed interface FrameMarkResult {
        /** Every frame accepted (or the mark carries no frames) — the mark's flag can flip. */
        data object Flipped : FrameMarkResult

        /** A hard per-frame failure or missing file — leave the mark pending, try the next mark. */
        data object Pending : FrameMarkResult

        /** A transient/target-wide failure — the whole target stops for this trigger. */
        data class Stop(val kind: UploadResultKind) : FrameMarkResult
    }

    /**
     * Upload every frame of one mark, reading bytes via [photoFrameReader]. A zero-frame `photoPath`
     * (an empty `"[]"` JSON list) has nothing to send and is [FrameMarkResult.Flipped] immediately.
     */
    private suspend fun uploadOneMarksFrames(
        mark: MarkEntity,
        upload: suspend (markId: String, frameId: String, bytes: ByteArray) -> PostResult<Unit>,
    ): FrameMarkResult {
        for (relPath in photoPaths(mark.photoPath)) {
            val bytes = photoFrameReader.read(relPath) ?: return FrameMarkResult.Pending
            val result = upload(mark.id, frameIdOf(relPath), bytes)
            if (result is PostResult.Success) continue
            if (isHardFrameFailure(result)) return FrameMarkResult.Pending
            return FrameMarkResult.Stop(uploadResultKind(result))
        }
        return FrameMarkResult.Flipped
    }

    private companion object {
        /** Max marks per upload request; the scoped `unuploaded*`/`framePending*` queries `LIMIT` to this. */
        const val UPLOAD_BATCH = 500
    }
}

/**
 * A frame POST that is unacceptable and will never succeed on retry: `400` (malformed) or `413`
 * (payload too large — mapped as [PostResult.Error] with `code == 413`). Draws the line between a
 * per-frame failure (leave the mark pending, move on) and a transient/target-wide one (stop the
 * whole target). See [MarkRepository]'s frame drain.
 */
internal fun isHardFrameFailure(result: PostResult<*>): Boolean =
    result is PostResult.BadRequest || (result is PostResult.Error && result.code == 413)

/**
 * Combine a scope's metadata-loop and frame-drain-loop outcomes into the single value reported to
 * [MarkRepository]'s `onUploadOutcome` — deterministic precedence **`Error` > `Offline` > `Ok` >
 * `null`**, fixed so the status-row message can never depend on call order: a frame `Ok` must never
 * mask a metadata `Error`/`Offline`. `null` only when **neither** loop attempted anything (nothing was
 * pending for that target this trigger).
 */
internal fun combineOutcome(metadata: UploadResultKind?, frame: UploadResultKind?): UploadResultKind? {
    val results = listOfNotNull(metadata, frame)
    return when {
        UploadResultKind.Error in results -> UploadResultKind.Error
        UploadResultKind.Offline in results -> UploadResultKind.Offline
        UploadResultKind.Ok in results -> UploadResultKind.Ok
        else -> null
    }
}

/**
 * Fold raw [PhotoFrameRow]s into frame-granular [UploadCounts]: `total` sums every row's frame count
 * (via [photoPaths]); `local`/`cloud` sum a row's frame count only when its per-target flag is set.
 *
 * This is a **mark-granular tick, frame-granular denominator**: a mid-drain mark (some frames already
 * accepted by the server, but [PhotoFrameRow.photosUploadedLocal]/[photosUploadedCloud] not yet flipped —
 * see [MarkRepository.frameDrainLoop]) contributes its frames to `total` but **zero** to the numerator
 * until every one of its frames lands and the flag flips. This matches what the DB can truthfully report
 * (per-frame accepted state isn't persisted, only the all-or-nothing per-mark flag).
 */
fun foldPhotoFrameCounts(rows: List<PhotoFrameRow>): UploadCounts {
    var total = 0
    var local = 0
    var cloud = 0
    for (row in rows) {
        val frameCount = photoPaths(row.photoPath).size
        total += frameCount
        if (row.photosUploadedLocal) local += frameCount
        if (row.photosUploadedCloud) cloud += frameCount
    }
    return UploadCounts(total = total, local = local, cloud = cloud)
}

/** Distinct checkpoints scored (complete) across the given take events. */
fun takenPointCount(marks: List<MarkEntity>): Int =
    marks.filter { it.complete }.map { it.checkpointId }.distinct().size

/**
 * The set of checkpoint ids (points) scored by these marks — i.e. the team's "взято" checkpoints,
 * derived from its own complete takes. The legend uses this instead of a persisted per-checkpoint flag
 * so that switching teams within a race shows each team's own progress.
 */
fun takenPoints(marks: List<MarkEntity>): Set<Int> =
    marks.filter { it.complete }.mapTo(HashSet()) { it.checkpointId }

/**
 * Sum of cost over distinct scored checkpoints — a repeat take of the same point does not double-count.
 * Uses the cost snapshotted onto the mark row at take time. Prefer the [costOf] overload for any
 * user-facing total: the snapshot goes stale if the organizer edits a КП cost after it was taken (a
 * 0→5 edit leaves the snapshot at 0), which makes the «Отметки» СУММА diverge from the «Легенда» score.
 */
fun totalScore(marks: List<MarkEntity>): Int = totalScore(marks) { it.cost }

/**
 * Sum of cost over distinct scored checkpoints using a **live** cost resolver instead of the snapshot
 * baked into the mark row. [costOf] returns the current checkpoint cost for a take (the legend's live
 * `CheckpointEntity.cost`), falling back to the mark's snapshot when the point is absent from the
 * legend. This keeps the «Отметки» СУММА in step with the «Легенда» score after a server cost edit.
 */
fun totalScore(marks: List<MarkEntity>, costOf: (MarkEntity) -> Int): Int =
    marks.filter { it.complete }.distinctBy { it.checkpointId }.sumOf { costOf(it) }
