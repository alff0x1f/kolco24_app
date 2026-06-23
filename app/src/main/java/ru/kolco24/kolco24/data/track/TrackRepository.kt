package ru.kolco24.kolco24.data.track

import kotlinx.coroutines.flow.Flow
import ru.kolco24.kolco24.data.db.TrackDao
import ru.kolco24.kolco24.data.db.TrackPointEntity
import ru.kolco24.kolco24.data.time.TrustedClock

/**
 * Single source of truth for the **local-only** GPS track points. Wraps [TrackDao] and owns the
 * impure mapping from a pure [RawFix] to a stored [TrackPointEntity] — the recording service only
 * forwards `List<RawFix>`, no time logic lives there.
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
) {
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
}
