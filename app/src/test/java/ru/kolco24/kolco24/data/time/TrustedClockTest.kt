package ru.kolco24.kolco24.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedClockTest {

    /** Mutable, injectable time sources for driving the clock deterministically. */
    private class Fakes(
        var elapsed: Long = 0L,
        var wall: Long = 0L,
        var boot: Int? = 1,
    ) {
        val persisted = mutableListOf<ClockAnchor>()
        val elapsedProvider: () -> Long = { elapsed }
        val wallProvider: () -> Long = { wall }
        val bootProvider: () -> Int? = { boot }
        val persist: (ClockAnchor) -> Unit = { persisted.add(it) }
    }

    private fun clock(
        f: Fakes,
        persistedAnchor: ClockAnchor? = null,
        persist: (ClockAnchor) -> Unit = f.persist,
    ) = TrustedClock(
        elapsedProvider = f.elapsedProvider,
        wallProvider = f.wallProvider,
        bootCountProvider = f.bootProvider,
        persist = persist,
        persisted = persistedAnchor,
    )

    @Test
    fun trustedFormula_afterSync() {
        val f = Fakes(elapsed = 1_000L, wall = 5_000_000L, boot = 1)
        val c = clock(f)
        // server says epoch = 10_000_000 when monotonic was 1_000.
        c.onServerTime(serverMs = 10_000_000L, anchorElapsed = 1_000L, wallNow = 5_000_000L, bootNow = 1)
        // advance monotonic by 2 s.
        f.elapsed = 3_000L
        assertEquals(10_002_000L, c.trusted())
    }

    @Test
    fun signingSeconds_trustedWhenVerified_wallWhenNoSync() {
        val f = Fakes(elapsed = 1_000L, wall = 5_000_000L, boot = 1)
        val c = clock(f)
        // No sync yet → falls back to wall.
        assertEquals(5_000_000L / 1000, c.signingSeconds())
        c.onServerTime(10_000_000L, 1_000L, 5_000_000L, 1)
        f.elapsed = 1_000L
        assertEquals(10_000_000L / 1000, c.signingSeconds())
    }

    @Test
    fun warmStart_sameBootCount_trustsImmediatelyWithoutSync() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 7)
        val anchor = ClockAnchor(serverEpochMs = 10_000_000L, anchorElapsedMs = 1_000L, capturedWallMs = 0L, bootCount = 7)
        val c = clock(f, persistedAnchor = anchor)
        // verified at construction; elapsed advanced 4 s since the anchor.
        assertEquals(10_004_000L, c.trusted())
    }

    @Test
    fun warmStart_bothBootNull_doesNotVerify() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = null)
        val anchor = ClockAnchor(10_000_000L, 1_000L, 0L, bootCount = null)
        val c = clock(f, persistedAnchor = anchor)
        assertNull(c.trusted())
        assertEquals(ClockStatus.NoSync, c.status.value)
    }

    @Test
    fun warmStart_differentBootCount_doesNotVerify() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 8)
        val anchor = ClockAnchor(10_000_000L, 1_000L, 0L, bootCount = 7)
        val c = clock(f, persistedAnchor = anchor)
        assertNull(c.trusted())
        assertEquals(ClockStatus.NoSync, c.status.value)
    }

    @Test
    fun rebootDetect_onRead_monotonicRegression_invalidates() {
        val f = Fakes(elapsed = 10_000L, wall = 0L, boot = 5)
        val c = clock(f)
        c.onServerTime(10_000_000L, 9_000L, 0L, 5)
        assertNotNull(c.trusted())
        // Reboot in same process: elapsed resets below the anchor's reading.
        f.elapsed = 100L
        assertNull(c.trusted())
    }

    @Test
    fun p0_bootCountNull_staleAnchorAfterReboot_acceptsNewSyncUnconditionally() {
        // persisted anchor with a LARGE anchorElapsedMs; both boot ids null so no warm verify and no
        // boot-id reboot signal — only monotonic regression can save us.
        val f = Fakes(elapsed = 50L, wall = 0L, boot = null)
        val anchor = ClockAnchor(10_000_000L, anchorElapsedMs = 900_000L, capturedWallMs = 0L, bootCount = null)
        val c = clock(f, persistedAnchor = anchor)
        // New sync after reboot: small elapsedNow. Must be accepted (regression), not blocked by
        // ordering (incoming anchorElapsed 40 < stale 900_000).
        c.onServerTime(serverMs = 20_000_000L, anchorElapsed = 40L, wallNow = 0L, bootNow = null)
        f.elapsed = 60L
        assertEquals(20_000_000L + (60L - 40L), c.trusted())
    }

    @Test
    fun initialStatus_verifiedPersisted_isOkNotNoSync() {
        val f = Fakes(elapsed = 1_000L, wall = 10_000_000L, boot = 3)
        val anchor = ClockAnchor(serverEpochMs = 10_000_000L, anchorElapsedMs = 1_000L, capturedWallMs = 10_000_000L, bootCount = 3)
        val c = clock(f, persistedAnchor = anchor)
        // wall == trusted at construction → Ok immediately (not NoSync).
        assertEquals(ClockStatus.Ok, c.status.value)
    }

    @Test
    fun outOfOrder_lateSmallerAnchorElapsed_sameSession_isRejected() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 4_000L, wallNow = 0L, bootNow = 1)
        // late, out-of-order, smaller anchorElapsed, same session, current monotonically valid.
        c.onServerTime(99_999_999L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1)
        f.elapsed = 4_000L
        // still anchored on the first (server 10_000_000 at elapsed 4_000) → trusted == 10_000_000.
        assertEquals(10_000_000L, c.trusted())
    }

    @Test
    fun bootNowNull_doesNotDowngradeGoodAnchor() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, 4_000L, 0L, bootNow = 1)
        // a later sample where boot id is momentarily unreadable must not invalidate.
        f.boot = null
        f.elapsed = 6_000L
        assertEquals(10_000_000L + (6_000L - 4_000L), c.trusted())
    }

    @Test
    fun scrambledOrder_persistAndStatus_matchWinner_byLargestAnchorElapsed() {
        val f = Fakes(elapsed = 10_000L, wall = 0L, boot = 1)
        val c = clock(f)
        // a sequence arriving in scrambled order; winner is the largest anchorElapsed (8_000).
        c.onServerTime(1L, 2_000L, 0L, 1)
        c.onServerTime(2L, 8_000L, 0L, 1) // winner
        c.onServerTime(3L, 5_000L, 0L, 1) // rejected (smaller)
        c.onServerTime(4L, 3_000L, 0L, 1) // rejected
        val lastPersisted = f.persisted.last()
        assertEquals(8_000L, lastPersisted.anchorElapsedMs)
        assertEquals(2L, lastPersisted.serverEpochMs)
        // in-memory anchor agrees with the persisted winner.
        f.elapsed = 8_000L
        assertEquals(2L, c.trusted())
    }

    @Test
    fun persist_calledOnAccept() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        assertTrue(f.persisted.isEmpty())
        c.onServerTime(10_000_000L, 1_000L, 0L, 1)
        assertEquals(1, f.persisted.size)
    }

    @Test
    fun persistThrows_onServerTimeDoesNotPropagate_stateStillUpdated() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f, persist = { throw RuntimeException("disk full") })
        // must not throw. wall == trusted so status resolves to Ok.
        c.onServerTime(10_000_000L, 1_000L, wallNow = 10_000_000L, bootNow = 1)
        // ref updated and status flow set despite persist failure.
        assertEquals(10_000_000L, c.trusted())
        assertEquals(ClockStatus.Ok, c.status.value)
    }

    @Test
    fun skewThreshold_boundaries() {
        fun skewStatusFor(skew: Long): ClockStatus {
            val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
            val c = clock(f)
            // trusted = serverMs at elapsed==anchor; set wall = trusted + skew.
            c.onServerTime(serverMs = 1_000_000L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1)
            f.wall = 1_000_000L + skew
            c.recomputeStatus()
            return c.status.value
        }
        assertEquals(ClockStatus.Ok, skewStatusFor(59_999L))
        assertEquals(ClockStatus.Ok, skewStatusFor(60_000L))
        assertEquals(ClockStatus.Skewed(60_001L), skewStatusFor(60_001L))
    }

    @Test
    fun skewSign_negativeWhenWallBehind() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(1_000_000L, 1_000L, 0L, 1)
        f.wall = 1_000_000L - 90_000L // wall 90 s behind trusted.
        c.recomputeStatus()
        assertEquals(ClockStatus.Skewed(-90_000L), c.status.value)
    }

    @Test
    fun sample_noSync_trustedMsIsNull() {
        val f = Fakes(elapsed = 1_000L, wall = 5_000L, boot = 1)
        val c = clock(f)
        // No sync yet — trusted time must be null so MarkRepository persists NULL trustedTakenAt.
        val s = c.sample()
        assertNull(s.trustedMs)
        assertEquals(1_000L, s.elapsedMs)
        assertEquals(5_000L, s.wallMs)
    }

    @Test
    fun onServerTime_differentBootId_acceptsNewAnchorUnconditionally() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 4_000L, wallNow = 0L, bootNow = 1)
        assertEquals(10_000_000L + (5_000L - 4_000L), c.trusted())
        // Reboot: boot id changes. New anchor has smaller anchorElapsed but must still be accepted
        // (case c: both boot ids non-null and differ → unconditional accept, same as reboot).
        c.onServerTime(20_000_000L, anchorElapsed = 100L, wallNow = 0L, bootNow = 2)
        f.boot = 2
        f.elapsed = 200L
        assertEquals(20_000_000L + (200L - 100L), c.trusted())
    }

    @Test
    fun sample_isConsistentSnapshot() {
        val f = Fakes(elapsed = 2_000L, wall = 5_000L, boot = 4)
        val c = clock(f)
        c.onServerTime(10_000_000L, 1_000L, 0L, 4)
        f.elapsed = 2_000L
        val s = c.sample()
        assertEquals(2_000L, s.elapsedMs)
        assertEquals(5_000L, s.wallMs)
        assertEquals(4, s.bootCount)
        assertEquals(10_000_000L + (2_000L - 1_000L), s.trustedMs)
    }
}
