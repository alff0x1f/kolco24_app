package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.db.MarkMemberSnapshot
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.data.track.RawFix

/**
 * Single source of truth for the **local-only** checkpoint-taking events (взятия КП). Wraps [MarkDao]
 * for the event rows. This data is never uploaded yet — the table only carries upload-seed flags.
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
) {
    /** Live take events for one team, newest first. */
    fun observeMarks(teamId: Int): Flow<List<MarkEntity>> = markDao.observeForTeam(teamId)

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
