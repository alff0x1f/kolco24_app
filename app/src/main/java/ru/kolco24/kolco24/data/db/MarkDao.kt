package ru.kolco24.kolco24.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkDao {
    // Order by the scoring/take time: trusted time when present, else the raw wall time. After a
    // clock change the tiles render trusted time, so they must also sort by it (untrusted rows fall
    // back to wall — the best source available).
    @Query("SELECT * FROM marks WHERE teamId = :teamId ORDER BY COALESCE(trustedTakenAt, takenAt) DESC")
    fun observeForTeam(teamId: Int): Flow<List<MarkEntity>>

    @Query("SELECT * FROM marks WHERE id = :id")
    suspend fun getById(id: String): MarkEntity?

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
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN uploadedLocal THEN 1 ELSE 0 END), 0) AS local, " +
            "COALESCE(SUM(CASE WHEN uploadedCloud THEN 1 ELSE 0 END), 0) AS cloud " +
            "FROM marks WHERE teamId = :teamId AND raceId = :raceId"
    )
    fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts>

    // Upload queries are scoped by (raceId, teamId): a batch goes to /race/<raceId>/marks/, so it must
    // never sweep up another race/team's rows. Note the explicit `= :raceId AND = :teamId` — a bare
    // `WHERE raceId AND teamId` would read the columns as truthy expressions and break the filter.
    // All rows (complete=true AND false) upload; the server recomputes completeness from present[].
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

    @Query("UPDATE marks SET uploadedLocal = 1 WHERE id IN (:ids)")
    suspend fun markUploadedLocal(ids: List<String>)

    @Query("UPDATE marks SET uploadedCloud = 1 WHERE id IN (:ids)")
    suspend fun markUploadedCloud(ids: List<String>)

    /**
     * Like [markUploadedLocal] but only marks rows where `locLat IS NULL` — i.e. the GPS fix had
     * not yet arrived when the row was fetched for upload. If [attachLocation] wrote a fix between
     * DTO creation and this call, the row's `locLat` is now non-null and this update skips it,
     * leaving `uploadedLocal = 0` so the next trigger re-uploads the row **with** the GPS data.
     * Use for rows whose DTO was serialized with `location = null`.
     */
    @Query("UPDATE marks SET uploadedLocal = 1 WHERE id IN (:ids) AND locLat IS NULL")
    suspend fun markUploadedLocalIfNoLocation(ids: List<String>)

    /** Same guard as [markUploadedLocalIfNoLocation] for the cloud target. */
    @Query("UPDATE marks SET uploadedCloud = 1 WHERE id IN (:ids) AND locLat IS NULL")
    suspend fun markUploadedCloudIfNoLocation(ids: List<String>)

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

    // Every (raceId, teamId) pair that still has a row not yet delivered to one of the targets — the
    // opportunistic re-send walks all of them, not just the current selection.
    @Query("SELECT DISTINCT raceId, teamId FROM marks WHERE uploadedLocal = 0 OR uploadedCloud = 0")
    suspend fun pendingUploadScopes(): List<TrackScope>
}
