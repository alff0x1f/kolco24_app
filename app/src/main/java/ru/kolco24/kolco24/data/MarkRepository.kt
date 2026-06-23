package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.time.TimeSample

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
     * Open a new take for [point] (КП chip just scanned). Generates a fresh UUID, snapshots the
     * checkpoint metadata ([number]/[cost]) and roster size ([expectedCount]), seeds `present` with any
     * members already buffered before the chip ([bufferedMembers], deduplicated), recomputes `complete`,
     * and upserts the row. Returns the new id.
     *
     * The take time comes from a [TimeSample] captured at the moment of the touch: `takenAt`/`updatedAt`
     * keep the raw wall ([TimeSample.wallMs]), `trustedTakenAt` gets the monotonic-anchored trusted time
     * ([TimeSample.trustedMs], NULL when no clock sync has happened), and `elapsedRealtimeAt`/`bootCount`
     * record the monotonic mark plus its boot session for forensic Δelapsed reconciliation.
     */
    suspend fun startKpTake(
        raceId: Int,
        teamId: Int,
        point: Int,
        number: Int,
        cost: Int,
        cpUid: String,
        cpCode: String,
        expectedCount: Int,
        bufferedMembers: Set<Int>,
        sample: TimeSample,
    ): String {
        val id = UUID.randomUUID().toString()
        val present = bufferedMembers.toList()
        val complete = expectedCount > 0 && present.size >= expectedCount
        markDao.upsert(
            MarkEntity(
                id = id,
                raceId = raceId,
                teamId = teamId,
                point = point,
                checkpointNumber = number,
                cost = cost,
                method = "nfc",
                cpUid = cpUid,
                cpCode = cpCode,
                present = present,
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
     * Add one member ([numberInTeam]) to the take [markId] with set semantics (idempotent rescan).
     * A missing row is a no-op. [point] is unused now that scoring is derived (kept for the call-site's
     * readability and a future per-point recompute), so it is accepted but not consulted.
     */
    suspend fun addMember(
        markId: String,
        point: Int,
        numberInTeam: Int,
        expectedCount: Int,
        sample: TimeSample,
    ) {
        markDao.addMember(markId, numberInTeam, sample.wallMs, expectedCount)
    }
}

/** Distinct checkpoints scored (complete) across the given take events. */
fun takenPointCount(marks: List<MarkEntity>): Int =
    marks.filter { it.complete }.map { it.point }.distinct().size

/**
 * The set of checkpoint ids (points) scored by these marks — i.e. the team's "взято" checkpoints,
 * derived from its own complete takes. The legend uses this instead of a persisted per-checkpoint flag
 * so that switching teams within a race shows each team's own progress.
 */
fun takenPoints(marks: List<MarkEntity>): Set<Int> =
    marks.filter { it.complete }.mapTo(HashSet()) { it.point }

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
    marks.filter { it.complete }.distinctBy { it.point }.sumOf { costOf(it) }
