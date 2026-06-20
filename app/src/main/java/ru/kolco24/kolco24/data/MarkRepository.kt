package ru.kolco24.kolco24.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.MarkDao
import ru.kolco24.kolco24.data.db.MarkEntity

/**
 * Single source of truth for the **local-only** checkpoint-taking events (взятия КП). Wraps [MarkDao]
 * for the event rows and [CheckpointDao] for the `taken` flip that scores a checkpoint. This data is
 * never uploaded yet — the table only carries upload-seed flags.
 *
 * A take is a two-phase row: [startKpTake] is called the moment the КП chip is scanned (creating a
 * row with a client UUID so the take survives process death and merges cleanly across two servers),
 * then [addMember] accumulates each member's `numberInTeam` within the rolling scan window. Whenever a
 * row's `present` set covers the whole roster ([MarkEntity.expectedCount]) it becomes [MarkEntity.complete]
 * and the underlying checkpoint flips to `taken` (= scored). A partial collect is stored for the future
 * server log but not scored, and a repeat take of the same checkpoint produces a **new** row.
 */
class MarkRepository(
    private val markDao: MarkDao,
    private val checkpointDao: CheckpointDao,
) {
    /** Live take events for one team, newest first. */
    fun observeMarks(teamId: Int): Flow<List<MarkEntity>> = markDao.observeForTeam(teamId)

    /**
     * Open a new take for [point] (КП chip just scanned). Generates a fresh UUID, snapshots the
     * checkpoint metadata ([number]/[cost]) and roster size ([expectedCount]), seeds `present` with any
     * members already buffered before the chip ([bufferedMembers], deduplicated), recomputes `complete`,
     * upserts the row, and — if already complete — flips the checkpoint to `taken`. Returns the new id.
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
        now: Long,
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
                takenAt = now,
                updatedAt = now,
            ),
        )
        if (complete) checkpointDao.markTaken(point)
        return id
    }

    /**
     * Add one member ([numberInTeam]) to the take [markId] with set semantics (idempotent rescan), then
     * flip [point] to `taken` if the row is now complete. A missing row is a no-op.
     */
    suspend fun addMember(
        markId: String,
        point: Int,
        numberInTeam: Int,
        expectedCount: Int,
        now: Long,
    ) {
        markDao.addMember(markId, numberInTeam, now, expectedCount)
        val mark = markDao.getById(markId) ?: return
        if (mark.complete) checkpointDao.markTaken(point)
    }
}

/** Distinct checkpoints scored (complete) across the given take events. */
fun takenPointCount(marks: List<MarkEntity>): Int =
    marks.filter { it.complete }.map { it.point }.distinct().size

/** Sum of cost over distinct scored checkpoints — a repeat take of the same point does not double-count. */
fun totalScore(marks: List<MarkEntity>): Int =
    marks.filter { it.complete }.distinctBy { it.point }.sumOf { it.cost }
