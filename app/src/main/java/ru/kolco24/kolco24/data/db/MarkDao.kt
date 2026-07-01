package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.marks.encodePhotoPaths
import ru.kolco24.kolco24.data.marks.isSafeRelativePhotoPath
import ru.kolco24.kolco24.data.marks.photoPaths

@Dao
interface MarkDao {
    // Order by the scoring/take time: trusted time when present, else the raw wall time. After a
    // clock change the tiles render trusted time, so they must also sort by it (untrusted rows fall
    // back to wall — the best source available).
    @Query("SELECT * FROM marks WHERE teamId = :teamId ORDER BY COALESCE(trustedTakenAt, takenAt) DESC")
    fun observeForTeam(teamId: Int): Flow<List<MarkEntity>>

    @Query("SELECT * FROM marks WHERE id = :id")
    suspend fun getById(id: String): MarkEntity?

    /** Every persisted mark id — backs the startup orphan-photo-directory sweep (a dir is orphaned when
     *  its name, the markId, has no row here). */
    @Query("SELECT id FROM marks")
    suspend fun allIds(): List<String>

    @Upsert
    suspend fun upsert(mark: MarkEntity)

    /**
     * Add one member to an existing take with set semantics: if [numberInTeam] is already in
     * `present`, the row is untouched (idempotent rescan); otherwise it is appended, [complete] is
     * recomputed against [expectedCount], and [updatedAt] is bumped. The caller is responsible for the
     * resulting `taken` flip on the checkpoint (a `complete` row scores). A missing row is a no-op.
     *
     * The upload snapshot ([nfcUid]/[number]/[code]) is written into `presentDetails` in lockstep with
     * `present`, also with set semantics by [numberInTeam]. A NULL `presentDetails` (a legacy/seed row
     * whose `present` predates this column) starts a fresh single-element list rather than NPE-ing — the
     * upload mapper merges over `present`, so the two never diverge in what gets uploaded.
     */
    @Transaction
    suspend fun addMember(
        id: String,
        numberInTeam: Int,
        nfcUid: String?,
        number: Int,
        code: String?,
        now: Long,
        expectedCount: Int,
    ) {
        val mark = getById(id) ?: return
        if (numberInTeam in mark.present) return
        val present = mark.present + numberInTeam
        val snapshot = MarkMemberSnapshot(numberInTeam, nfcUid, number, code)
        val presentDetails =
            mark.presentDetails.orEmpty().filterNot { it.numberInTeam == numberInTeam } + snapshot
        upsert(
            mark.copy(
                present = present,
                presentDetails = presentDetails,
                expectedCount = expectedCount,
                complete = expectedCount > 0 && present.size >= expectedCount,
                updatedAt = now,
                // A new member mutates the take — any previously-uploaded version is stale.
                // Works in tandem with markUploadedCloud*IfUnchanged* in MarkRepository:
                // (a) upload set the flags true before this call → reset re-queues the row.
                // (b) this call runs between a fetch and its mark (during an in-flight upload)
                //     → the bumped updatedAt causes the version guard to fail the mark, so the
                //     row stays pending and gets re-fetched with the updated present list.
                uploadedLocal = false,
                uploadedCloud = false,
            ),
        )
    }

    /**
     * Attach the take-place GPS fix to an existing take. **Column-scoped** on purpose: it writes only
     * the 7 `loc*` columns and never touches `present`/`complete`/the take times. This runs
     * fire-and-forget on the application scope **in parallel** with the window's [addMember] calls, so a
     * full-row read-modify-write (like [addMember]) would lose-update — a late location write would roll
     * back `present`/`complete`, or an [addMember] would clobber the coordinate. A column-scoped `UPDATE`
     * serializes atomically around [addMember]'s `@Transaction` (SQLite won't interleave it) and touches
     * only `loc*` and `uploaded*`, so there is no race with `present`/`complete`.
     *
     * Resets `uploadedLocal`/`uploadedCloud` to 0 so that if the row was already sent to a target before
     * the GPS fix arrived, it is re-queued and the server eventually receives the anti-cheat coordinate.
     * The server handles duplicate IDs idempotently (upsert by `id`). A missing row is a silent no-op.
     */
    @Query(
        "UPDATE marks SET locLat = :lat, locLon = :lon, locAccuracy = :accuracy, " +
            "locAltitude = :altitude, locVerticalAccuracy = :verticalAccuracy, " +
            "locGpsTimeMs = :gpsTimeMs, locElapsedRealtimeAt = :elapsedRealtimeAt, " +
            "uploadedLocal = 0, uploadedCloud = 0 WHERE id = :id",
    )
    suspend fun attachLocation(
        id: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        altitude: Double?,
        verticalAccuracy: Float?,
        gpsTimeMs: Long?,
        elapsedRealtimeAt: Long,
    )

    // Per-target upload progress for one scope (mirror of TrackDao.uploadCounts): explicit CASE over the
    // Boolean column (SUM(boolean) is codegen-fragile), COALESCE(...,0) guards the empty-scope NULL, and
    // aliases match UploadCounts property names so Room maps by name.
    //
    // Phase 2: a photo mark (photoPath NOT NULL) counts as uploaded for a target only when BOTH its
    // metadata (uploadedX) and its frames (photosUploadedX) have landed — else the status row would read
    // "uploaded" while frames are still pending. A non-photo row is unaffected (photoPath IS NULL short-
    // circuits the OR).
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN uploadedLocal AND (photoPath IS NULL OR photosUploadedLocal) " +
            "THEN 1 ELSE 0 END), 0) AS local, " +
            "COALESCE(SUM(CASE WHEN uploadedCloud AND (photoPath IS NULL OR photosUploadedCloud) " +
            "THEN 1 ELSE 0 END), 0) AS cloud " +
            "FROM marks WHERE teamId = :teamId AND raceId = :raceId"
    )
    fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts>

    // Metadata-only variant of [uploadCounts]: answers "did the take row reach the server?"
    // independent of its photo frames (the Фото upload section covers frame completion
    // separately via [photoFrameRows]/`foldPhotoFrameCounts`).
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN uploadedLocal THEN 1 ELSE 0 END), 0) AS local, " +
            "COALESCE(SUM(CASE WHEN uploadedCloud THEN 1 ELSE 0 END), 0) AS cloud " +
            "FROM marks WHERE teamId = :teamId AND raceId = :raceId"
    )
    fun uploadCountsMetadata(teamId: Int, raceId: Int): Flow<UploadCounts>

    // Raw per-mark frame flags for every photo-carrying row in scope — folded into frame-granular
    // UploadCounts by `foldPhotoFrameCounts` (MarkRepository), since a mark's `photoPath` JSON list
    // can hold more than one frame and only the caller knows how to decode it.
    @Query(
        "SELECT photoPath, photosUploadedLocal, photosUploadedCloud FROM marks " +
            "WHERE teamId = :teamId AND raceId = :raceId AND photoPath IS NOT NULL"
    )
    fun photoFrameRows(teamId: Int, raceId: Int): Flow<List<PhotoFrameRow>>

    // Upload queries are scoped by (raceId, teamId): a batch goes to /race/<raceId>/marks/, so it must
    // never sweep up another race/team's rows. Note the explicit `= :raceId AND = :teamId` — a bare
    // `WHERE raceId AND teamId` would read the columns as truthy expressions and break the filter.
    // All rows (complete=true AND false) upload; the server recomputes completeness from present[].
    //
    // Phase 2 drops the Phase-1 `method != 'photo'` filter: photo-mark metadata (empty cpUid/cpCode,
    // method="photo") now shares this batch with NFC marks — the `/marks/` backend contract requires
    // partial-accept (accepted[] minus rejected rows, never a whole-batch 400) so a photo row can never
    // strand valid NFC marks in the same batch (see the plan's backend-contract gate).
    @Query(
        "SELECT * FROM marks WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedLocal = 0 " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id " +
            "LIMIT :limit"
    )
    suspend fun unuploadedLocal(raceId: Int, teamId: Int, limit: Int): List<MarkEntity>

    @Query(
        "SELECT * FROM marks WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedCloud = 0 " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id " +
            "LIMIT :limit"
    )
    suspend fun unuploadedCloud(raceId: Int, teamId: Int, limit: Int): List<MarkEntity>

    // Frame-drain candidates for one target: metadata-first ordering (uploadedX = 1 gates this — the
    // server must have the mark row before frames can attach) AND this mark's frames not yet fully
    // accepted by this target AND it actually carries frames. Method-agnostic on purpose — an NFC take
    // with attached photos drains here too, not just method="photo" rows.
    @Query(
        "SELECT * FROM marks WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedLocal = 1 AND photosUploadedLocal = 0 AND photoPath IS NOT NULL " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id " +
            "LIMIT :limit"
    )
    suspend fun framePendingLocal(raceId: Int, teamId: Int, limit: Int): List<MarkEntity>

    /** Same frame-drain candidate query as [framePendingLocal], for the cloud target. */
    @Query(
        "SELECT * FROM marks WHERE raceId = :raceId AND teamId = :teamId " +
            "AND uploadedCloud = 1 AND photosUploadedCloud = 0 AND photoPath IS NOT NULL " +
            "ORDER BY COALESCE(trustedTakenAt, takenAt), id " +
            "LIMIT :limit"
    )
    suspend fun framePendingCloud(raceId: Int, teamId: Int, limit: Int): List<MarkEntity>

    @Query("UPDATE marks SET uploadedLocal = 1 WHERE id IN (:ids)")
    suspend fun markUploadedLocal(ids: List<String>)

    @Query("UPDATE marks SET uploadedCloud = 1 WHERE id IN (:ids)")
    suspend fun markUploadedCloud(ids: List<String>)


    /**
     * Mark one row as locally uploaded only when its `updatedAt` still matches [updatedAt] — i.e.
     * [addMember] has **not** mutated it between the batch fetch and this call. If [addMember] ran
     * (bumping `updatedAt`), the guard fails and the row stays `uploadedLocal = 0` so the next
     * trigger re-fetches the updated present list and re-uploads it. Use for rows whose DTO was
     * serialized with `location != null` (GPS is already in the DTO; only the addMember race remains).
     */
    @Query("UPDATE marks SET uploadedLocal = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markUploadedLocalIfUnchanged(id: String, updatedAt: Long)

    /** Same [updatedAt] version guard as [markUploadedLocalIfUnchanged] for the cloud target. */
    @Query("UPDATE marks SET uploadedCloud = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markUploadedCloudIfUnchanged(id: String, updatedAt: Long)

    /**
     * Combines the [updatedAt] version guard (prevents marking after an [addMember] mutation) with
     * the `locLat IS NULL` guard (prevents marking before a GPS fix is re-uploaded). If either
     * guard fails the row stays pending and the next trigger re-uploads with fresh data. Use for
     * rows whose DTO was serialized with `location = null`.
     */
    @Query("UPDATE marks SET uploadedLocal = 1 WHERE id = :id AND updatedAt = :updatedAt AND locLat IS NULL")
    suspend fun markUploadedLocalIfUnchangedAndNoLocation(id: String, updatedAt: Long)

    /** Same combined guard as [markUploadedLocalIfUnchangedAndNoLocation] for the cloud target. */
    @Query("UPDATE marks SET uploadedCloud = 1 WHERE id = :id AND updatedAt = :updatedAt AND locLat IS NULL")
    suspend fun markUploadedCloudIfUnchangedAndNoLocation(id: String, updatedAt: Long)

    /**
     * Flips the local frame-drain flag once all of a mark's frames have been accepted by the local
     * target — guarded by [updatedAt] like [markUploadedLocalIfUnchanged]: if `attachPhotos` appended a
     * new frame between the drain's fetch and this call (bumping `updatedAt` and resetting the flag to
     * 0), the guard no-ops and the next trigger re-drains including the new frame instead of falsely
     * flipping to "all uploaded" with one frame stranded.
     */
    @Query("UPDATE marks SET photosUploadedLocal = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun setPhotosUploadedLocalIfUnchanged(id: String, updatedAt: Long)

    /** Same [updatedAt] version guard as [setPhotosUploadedLocalIfUnchanged] for the cloud target. */
    @Query("UPDATE marks SET photosUploadedCloud = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun setPhotosUploadedCloudIfUnchanged(id: String, updatedAt: Long)

    // Every (raceId, teamId) pair that still has a row not yet fully delivered to one of the targets —
    // the opportunistic re-send walks all of them, not just the current selection. Phase 2 widens this
    // beyond bare metadata: a scope whose metadata is fully uploaded but whose frames are still pending
    // (photoPath NOT NULL AND photosUploadedX = 0) must still be returned, or uploadAllPending would never
    // re-trigger the frame drain for it.
    @Query(
        "SELECT DISTINCT raceId, teamId FROM marks " +
            "WHERE (uploadedLocal = 0 OR uploadedCloud = 0) " +
            "OR (photoPath IS NOT NULL AND (photosUploadedLocal = 0 OR photosUploadedCloud = 0))"
    )
    suspend fun pendingUploadScopes(): List<TrackScope>

    /**
     * Append [newPaths] to an existing take's [MarkEntity.photoPath] JSON list — **column-scoped** like
     * [attachLocation]: it reads the current paths, merges, and writes back **only** `photoPath` and
     * `updatedAt`. A full-row read-modify-write would lose-update the parallel fire-and-forget
     * `present`/`complete`/`loc*` writes (see [attachLocation]'s rationale), so the merge runs inside a
     * `@Transaction` and the write is a column-scoped `UPDATE`.
     *
     * **`uploaded*` is deliberately NOT reset.** This attaches to an already-uploaded NFC row, but
     * `photoPath` is not part of the marks DTO — re-queuing the marks metadata would be a useless POST.
     * **`photosUploaded*` IS reset to 0** — appending frames re-queues the frame drain (the new frames
     * haven't been sent), while the metadata upload flags stay untouched.
     */
    @Transaction
    suspend fun attachPhotos(id: String, newPaths: List<String>, now: Long) {
        val mark = getById(id) ?: return
        val merged = (photoPaths(mark.photoPath) + newPaths.filter(::isSafeRelativePhotoPath)).distinct()
        updatePhotoPath(id, encodePhotoPaths(merged), now)
    }

    /**
     * Column-scoped UPDATE touching only `photoPath`, `updatedAt`, and `photosUploaded*` (see
     * [attachPhotos]) — resets the frame-upload flags so newly-appended frames get drained.
     */
    @Query(
        "UPDATE marks SET photoPath = :photoPath, updatedAt = :now, " +
            "photosUploadedLocal = 0, photosUploadedCloud = 0 WHERE id = :id"
    )
    suspend fun updatePhotoPath(id: String, photoPath: String, now: Long)
}
