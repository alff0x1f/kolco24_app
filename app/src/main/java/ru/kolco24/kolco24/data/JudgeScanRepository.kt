package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.JudgeScanDto
import ru.kolco24.kolco24.data.api.dto.JudgeScanUploadResponse
import ru.kolco24.kolco24.data.api.dto.toDto
import ru.kolco24.kolco24.data.db.JudgeScanDao
import ru.kolco24.kolco24.data.db.JudgeScanEntity
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.uploadResultKind

/**
 * One upload target (cloud HTTPS / local LAN cleartext). A thin functional seam over
 * `ApiClient.uploadJudgeScans` so [JudgeScanRepository]'s upload loop is unit-testable with a fake
 * (mirrors [ru.kolco24.kolco24.data.MarkUploader]); in production each target is one `ApiClient`
 * instance. Unlike [ru.kolco24.kolco24.data.track.TrackUploader], [upload] carries no `teamId` — a
 * judge station scans across all teams of a race, so the scope is `raceId` only.
 */
fun interface JudgeScanUploader {
    suspend fun upload(
        raceId: Int,
        sourceInstallId: String,
        scans: List<JudgeScanDto>,
    ): PostResult<JudgeScanUploadResponse>
}

/**
 * Single source of truth for judge-side start/finish piks. Wraps [JudgeScanDao] for the write-once
 * rows and includes a dual-target (cloud + local LAN) idempotent batch upload loop mirroring
 * [ru.kolco24.kolco24.data.track.TrackRepository]. Unlike [MarkRepository] there is no per-scan
 * mutation (no `addMember`/`attachLocation` phase) and no team dimension — every pik is a complete,
 * immutable row scoped by `raceId` alone.
 */
class JudgeScanRepository(
    private val judgeScanDao: JudgeScanDao,
    private val sourceInstallId: String = "",
    private val cloudUploader: JudgeScanUploader = JudgeScanUploader { _, _, _ -> PostResult.Offline },
    private val localUploader: JudgeScanUploader = JudgeScanUploader { _, _, _ -> PostResult.Offline },
) {
    /**
     * Guards [uploadPending]/[uploadAllPending] against concurrent entry (the 60 s ticker firing
     * while a manual flush is already in flight). `tryLock()` — not `lock()` — so the loser skips
     * rather than queues: the same rows would otherwise be POSTed twice, and the next trigger
     * flushes whatever the winner did not.
     */
    private val uploadMutex = Mutex()

    /**
     * Record one judge pik: mints a fresh client UUID, maps [sample] onto the entity's time columns
     * (mirrors [MarkRepository.startKpTake]'s [TimeSample] handling), and inserts the write-once row.
     * Returns the new id.
     */
    suspend fun record(
        raceId: Int,
        eventType: String,
        participantNumber: Int,
        nfcUid: String,
        sample: TimeSample,
    ): String {
        val id = UUID.randomUUID().toString()
        judgeScanDao.insert(
            JudgeScanEntity(
                id = id,
                raceId = raceId,
                eventType = eventType,
                participantNumber = participantNumber,
                nfcUid = nfcUid,
                takenAt = sample.wallMs,
                trustedTakenAt = sample.trustedMs,
                elapsedRealtimeAt = sample.elapsedMs,
                bootCount = sample.bootCount,
                sourceInstallId = sourceInstallId,
            ),
        )
        return id
    }

    /** Flush all pending scans for one race to **both** targets independently. */
    suspend fun uploadPending(raceId: Int) {
        if (!uploadMutex.tryLock()) return
        try {
            flushRace(raceId)
        } finally {
            uploadMutex.unlock()
        }
    }

    /**
     * Opportunistic re-send across **every** race with pending scans, not just the currently active
     * one — so piks stranded under a race switch still flush. Walks [JudgeScanDao.pendingUploadRaces].
     * Same concurrency guard as [uploadPending].
     */
    suspend fun uploadAllPending() {
        if (!uploadMutex.tryLock()) return
        try {
            for (raceId in judgeScanDao.pendingUploadRaces()) {
                flushRace(raceId)
            }
        } finally {
            uploadMutex.unlock()
        }
    }

    /** Flush one race to both targets in turn; each target's loop is independent of the other's. */
    private suspend fun flushRace(raceId: Int) {
        uploadLoop(
            fetch = { judgeScanDao.unuploadedLocal(raceId, UPLOAD_BATCH) },
            upload = { localUploader.upload(raceId, sourceInstallId, it) },
            mark = { judgeScanDao.markUploadedLocal(it) },
        )
        uploadLoop(
            fetch = { judgeScanDao.unuploadedCloud(raceId, UPLOAD_BATCH) },
            upload = { cloudUploader.upload(raceId, sourceInstallId, it) },
            mark = { judgeScanDao.markUploadedCloud(it) },
        )
    }

    /**
     * Drain one target in batches until done or stuck, returning the terminal [UploadResultKind] or
     * `null`. Terminates on: empty first fetch (nothing pending → `null`); a non-`Success` response
     * (offline/error — mapped via [uploadResultKind], retry next trigger); or **no forward progress**
     * (none of the fetched batch's ids came back accepted → [UploadResultKind.Error]). Only ids both
     * `accepted` **and** in the fetched batch are marked, so a marked row strictly shrinks the pending
     * set, guaranteeing exit.
     */
    private suspend fun uploadLoop(
        fetch: suspend () -> List<JudgeScanEntity>,
        upload: suspend (List<JudgeScanDto>) -> PostResult<JudgeScanUploadResponse>,
        mark: suspend (List<String>) -> Unit,
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
            mark(toMark)
            progressed = true
        }
    }

    private companion object {
        /** Max scans per upload request; the scoped `unuploaded*` queries `LIMIT` to this. */
        const val UPLOAD_BATCH = 500
    }
}
