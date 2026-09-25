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

    /**
     * Legacy-shaped convenience wrapper: the pre-uncertainty call sites offered a network `Date`
     * candidate. Defaults [uncertaintyMs] so same-uncertainty tests keep asserting the "larger
     * anchorElapsed wins" behaviour (identical uncertainty → the older duplicate has a larger
     * effective uncertainty and is rejected).
     */
    private fun TrustedClock.onServerTime(
        serverMs: Long,
        anchorElapsed: Long,
        wallNow: Long,
        bootNow: Int?,
        uncertaintyMs: Long = 500L,
    ) = onTimeCandidate(TimeCandidate(serverMs, anchorElapsed, uncertaintyMs), wallNow, bootNow)

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
    fun trustedAt_pastFix_givesTimeEarlierThanNow_byDeltaElapsed() {
        val f = Fakes(elapsed = 600_000L, wall = 0L, boot = 1)
        val c = clock(f)
        // anchor: server 10_000_000 at monotonic 600_000 (== "now").
        c.onServerTime(serverMs = 10_000_000L, anchorElapsed = 600_000L, wallNow = 0L, bootNow = 1)
        // a fix from a batch ~4 min before now: elapsedAt 360_000 (240 s earlier).
        val at = c.trustedAt(elapsedAt = 360_000L, bootAt = 1)
        assertEquals(10_000_000L - 240_000L, at)
        // and it is earlier than "now".
        assertTrue(at!! < c.trusted()!!)
    }

    @Test
    fun trustedAt_preAnchorPoint_sameBootSession_isNotNull() {
        // Key review case: a point captured BEFORE the network set the anchor (elapsedAt < anchor)
        // in the SAME boot session must extrapolate (NOT be invalidated as a reboot).
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 7)
        val c = clock(f)
        c.onServerTime(serverMs = 10_000_000L, anchorElapsed = 5_000L, wallNow = 0L, bootNow = 7)
        // point captured at elapsed 2_000, 3 s before the anchor's reading.
        val at = c.trustedAt(elapsedAt = 2_000L, bootAt = 7)
        assertNotNull(at)
        assertEquals(10_000_000L - 3_000L, at)
    }

    @Test
    fun trustedAt_differentBootSession_isNull() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 7)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 5_000L, wallNow = 0L, bootNow = 7)
        // the fix's boot id differs from the anchor's → cannot compare monotonic scales.
        assertNull(c.trustedAt(elapsedAt = 2_000L, bootAt = 8))
    }

    @Test
    fun trustedAt_bothBootNull_fallsBackToTrustExtrapolate() {
        // No reboot evidence when either boot id is null → documented fallback: trust & extrapolate.
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = null)
        val anchor = ClockAnchor(10_000_000L, anchorElapsedMs = 5_000L, capturedWallMs = 0L, bootCount = null)
        // warm-start verify requires non-null matching boot, so sync via onServerTime to verify.
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 5_000L, wallNow = 0L, bootNow = null)
        assertEquals(10_000_000L - 3_000L, c.trustedAt(elapsedAt = 2_000L, bootAt = null))
    }

    @Test
    fun trustedAt_knownAnchorBoot_nullCallSiteBoot_fallsBackToTrustExtrapolate() {
        // Anchor has a known boot id; call-site passes null (e.g. bootCountProvider returned null for
        // that fix). A lone null does NOT prove a reboot — documented fallback: trust & extrapolate.
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 7)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 5_000L, wallNow = 0L, bootNow = 7)
        assertEquals(10_000_000L - 3_000L, c.trustedAt(elapsedAt = 2_000L, bootAt = null))
    }

    @Test
    fun trustedAt_noSync_isNull() {
        val f = Fakes(elapsed = 5_000L, wall = 0L, boot = 1)
        val c = clock(f)
        // never synced → not verified.
        assertNull(c.trustedAt(elapsedAt = 2_000L, bootAt = 1))
    }

    @Test
    fun uncertainty_goodAnchorNotOverwrittenByBadCandidate() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        // Good anchor: tiny uncertainty (RTT 50 ms).
        c.onServerTime(10_000_000L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1, uncertaintyMs = 50L)
        // A poor candidate (RTT 9.9 s) arrives in the same session — must be rejected.
        f.elapsed = 2_000L
        c.onTimeCandidate(TimeCandidate(serverMs = 99_999_999L, anchorElapsedMs = 2_000L, uncertaintyMs = 9_900L), 0L, 1)
        // Still anchored on the good one: 10_000_000 + (2_000 − 1_000).
        assertEquals(10_001_000L, c.trusted())
    }

    @Test
    fun uncertainty_badAnchorOverwrittenByGoodCandidate() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        // Poor anchor first.
        c.onServerTime(5_000_000L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1, uncertaintyMs = 9_900L)
        // A good candidate supersedes it.
        f.elapsed = 2_000L
        c.onTimeCandidate(TimeCandidate(serverMs = 10_000_000L, anchorElapsedMs = 2_000L, uncertaintyMs = 50L), 0L, 1)
        f.elapsed = 3_000L
        assertEquals(10_000_000L + (3_000L - 2_000L), c.trusted())
    }

    @Test
    fun uncertainty_staleGoodAnchorLosesToFreshAverage_afterEnoughDrift() {
        val f = Fakes(elapsed = 0L, wall = 0L, boot = 1)
        val c = clock(f)
        // Ideal old anchor (uncertainty 50) captured at elapsed 0.
        c.onServerTime(10_000_000L, anchorElapsed = 0L, wallNow = 0L, bootNow = 1, uncertaintyMs = 50L)
        // ~50 min later: drift penalty on the old anchor = 3_000_000 * 20 / 1e6 = 60 ms → effective 110.
        // Fresh average candidate (uncertainty 100) at the same elapsed → effective 100 ≤ 110 → wins.
        f.elapsed = 3_000_000L
        c.onTimeCandidate(TimeCandidate(serverMs = 20_000_000L, anchorElapsedMs = 3_000_000L, uncertaintyMs = 100L), 0L, 1)
        assertEquals(20_000_000L, c.trusted())
    }

    @Test
    fun uncertainty_staleGoodAnchorStillWins_beforeEnoughDrift() {
        val f = Fakes(elapsed = 0L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 0L, wallNow = 0L, bootNow = 1, uncertaintyMs = 50L)
        // Only 10 s later: old effective = 50 + 10_000*20/1e6 = 50; fresh (100) > 50 → rejected.
        f.elapsed = 10_000L
        c.onTimeCandidate(TimeCandidate(serverMs = 20_000_000L, anchorElapsedMs = 10_000L, uncertaintyMs = 100L), 0L, 1)
        assertEquals(10_000_000L + (10_000L - 0L), c.trusted())
    }

    @Test
    fun uncertainty_pastAnchorElapsedCandidate_acceptedWhenBetterEffective() {
        // A GPS-style candidate whose anchorElapsed is in the PAST of elapsedNow is allowed and
        // accepted when its effective uncertainty beats the current anchor's.
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1, uncertaintyMs = 5_000L)
        f.elapsed = 2_000L
        // captured at elapsed 500 (before now), tight uncertainty 500.
        c.onTimeCandidate(TimeCandidate(serverMs = 15_000_000L, anchorElapsedMs = 500L, uncertaintyMs = 500L), 0L, 1)
        // 15_000_000 + (2_000 − 500).
        assertEquals(15_001_500L, c.trusted())
    }

    @Test
    fun uncertainty_rebootAcceptsWorseCandidateUnconditionally() {
        val f = Fakes(elapsed = 10_000L, wall = 0L, boot = 5)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 9_000L, wallNow = 0L, bootNow = 5, uncertaintyMs = 50L)
        // Reboot: monotonic regresses below the anchor. A far worse candidate must still be accepted
        // (the reboot branch precedes the uncertainty comparison).
        f.elapsed = 100L
        c.onTimeCandidate(TimeCandidate(serverMs = 20_000_000L, anchorElapsedMs = 40L, uncertaintyMs = 9_900L), 0L, 5)
        f.elapsed = 60L
        assertEquals(20_000_000L + (60L - 40L), c.trusted())
    }

    @Test
    fun unverifiedAnchor_isSupersededByFirstRealCandidate_evenWithLargerUncertainty() {
        // Warm start whose boot continuity can't be confirmed (persisted anchor with a null bootCount):
        // UNVERIFIED → NoSync → wall-clock signing, despite a tiny stored uncertainty. That stored
        // uncertainty must NOT let the anchor reject the first real network/GPS/LAN candidate — the
        // candidate IS the (re-)verification, even though its own uncertainty is far larger.
        val f = Fakes(elapsed = 2_000L, wall = 0L, boot = 1)
        val anchor = ClockAnchor(
            serverEpochMs = 10_000_000L, anchorElapsedMs = 1_000L, capturedWallMs = 0L,
            bootCount = null, uncertaintyMs = 50L, // tiny uncertainty, but unverified
        )
        val c = clock(f, persistedAnchor = anchor)
        // Unverified at construction: NoSync, and signing falls back to wall.
        assertEquals(ClockStatus.NoSync, c.status.value)
        assertNull(c.trusted())
        // First real candidate: uncertainty 5 s (much larger than the stored 50 ms) but it must win
        // because the current anchor is unverified. Capture wall == its trusted so status resolves Ok.
        c.onTimeCandidate(
            TimeCandidate(serverMs = 20_000_000L, anchorElapsedMs = 2_000L, uncertaintyMs = 5_000L),
            wallNow = 20_000_000L,
            bootNow = 1,
        )
        // Clock is now verified and anchored on the candidate.
        assertEquals(ClockStatus.Ok, c.status.value)
        f.elapsed = 3_000L
        assertEquals(20_000_000L + (3_000L - 2_000L), c.trusted())
    }

    @Test
    fun verifiedAnchor_isNotOverwrittenByWorseCandidate() {
        // Counterpart to the unverified case: a legitimately VERIFIED anchor (warm start with matching
        // boot continuity) still earns the effective-uncertainty comparison — a worse same-session
        // candidate is rejected, exactly as before. Guards that the fix did not weaken verified anchors.
        val f = Fakes(elapsed = 2_000L, wall = 0L, boot = 7)
        val anchor = ClockAnchor(
            serverEpochMs = 10_000_000L, anchorElapsedMs = 1_000L, capturedWallMs = 0L,
            bootCount = 7, uncertaintyMs = 50L,
        )
        val c = clock(f, persistedAnchor = anchor)
        // Verified at construction (matching boot 7).
        assertNotNull(c.trusted())
        // A far worse candidate in the same session must be rejected.
        c.onTimeCandidate(
            TimeCandidate(serverMs = 99_999_999L, anchorElapsedMs = 2_000L, uncertaintyMs = 9_900L),
            wallNow = 0L,
            bootNow = 7,
        )
        f.elapsed = 3_000L
        // Still anchored on the verified original: 10_000_000 + (3_000 − 1_000).
        assertEquals(10_002_000L, c.trusted())
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

    @Test
    fun anchorRevision_bumpsOnEveryAcceptedAnchor_evenWhenStatusStaysOk() {
        val f = Fakes(elapsed = 1_000L, wall = 10_000_000L, boot = 1)
        val c = clock(f)
        assertEquals(0L, c.anchorRevision.value)
        c.onServerTime(10_000_000L, anchorElapsed = 1_000L, wallNow = 10_000_000L, bootNow = 1, uncertaintyMs = 9_900L)
        assertEquals(ClockStatus.Ok, c.status.value)
        assertEquals(1L, c.anchorRevision.value)
        // A better candidate shifts the anchor by 5 s: status stays Ok (deduped, no emission), but the
        // revision must still change so re-anchored past takes are recomputed.
        f.elapsed = 2_000L
        c.onServerTime(10_006_000L, anchorElapsed = 2_000L, wallNow = 10_001_000L, bootNow = 1, uncertaintyMs = 50L)
        assertEquals(ClockStatus.Ok, c.status.value)
        assertEquals(2L, c.anchorRevision.value)
        assertEquals(10_005_000L, c.trustedAt(elapsedAt = 1_000L, bootAt = 1))
    }

    @Test
    fun anchorRevision_unchangedByRejectedCandidateAndStatusRecompute() {
        val f = Fakes(elapsed = 1_000L, wall = 0L, boot = 1)
        val c = clock(f)
        c.onServerTime(10_000_000L, anchorElapsed = 1_000L, wallNow = 0L, bootNow = 1, uncertaintyMs = 50L)
        assertEquals(1L, c.anchorRevision.value)
        f.elapsed = 2_000L
        c.onTimeCandidate(TimeCandidate(serverMs = 99_999_999L, anchorElapsedMs = 2_000L, uncertaintyMs = 9_900L), 0L, 1)
        c.recomputeStatus()
        assertEquals(1L, c.anchorRevision.value)
    }
}
