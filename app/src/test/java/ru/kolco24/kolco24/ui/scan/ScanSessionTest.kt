package ru.kolco24.kolco24.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.marks.CheckMethod
import ru.kolco24.kolco24.data.track.UploadTarget

class ScanSessionTest {

    private fun kp(point: Int = 42, number: Int = 7, cost: Int = 50) =
        ScanEvent.Kp(checkpointId = point, number = number, cost = cost, cpUid = "04AABBCC", cpCode = "DEADBEEF")

    @Test
    fun kp_onNullSession_fillsCheckpointFields() {
        val s = reduce(null, kp(), now = 1_000L)!!
        assertEquals(42, s.checkpointId)
        assertEquals(7, s.checkpointNumber)
        assertEquals(50, s.cost)
        assertEquals("04AABBCC", s.cpUid)
        assertEquals("DEADBEEF", s.cpCode)
        assertTrue(s.present.isEmpty())
        assertEquals(1_000L, s.lastScanAt)
    }

    @Test
    fun kp_setsSessionCheckMethod() {
        assertEquals(CheckMethod.Offline, ScanSession.empty(0L).checkMethod)
        assertEquals(CheckMethod.Offline, reduce(null, kp(), now = 0L)!!.checkMethod)
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, cloud, now = 100L)
        assertEquals(CheckMethod.Cloud, s!!.checkMethod)
        // A member scan keeps the method; switching to a local КП replaces it.
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        assertEquals(CheckMethod.Cloud, s!!.checkMethod)
        val local = ScanEvent.Kp(99, 12, 80, "04BB", "CAFE", checkMethod = CheckMethod.Local)
        assertEquals(CheckMethod.Local, reduce(s, local, now = 300L)!!.checkMethod)
    }

    @Test
    fun member_afterKp_accumulatesPresent() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        assertEquals(setOf(1, 2), s!!.present)
        assertEquals(200L, s.lastScanAt)
    }

    @Test
    fun member_isIdempotent_andDoesNotRefreshWindow() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(1), now = 200L)
        assertEquals(setOf(1), s!!.present)
        // A re-scan of an already-present member must NOT refresh the window — otherwise one
        // person could keep the 20 s timer alive alone by re-tapping their own chip.
        assertEquals(100L, s.lastScanAt)
    }

    @Test
    fun member_beforeKp_repeat_doesNotRefreshWindow() {
        var s = reduce(null, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(1), now = 200L)
        assertEquals(setOf(1), s!!.bufferedBeforeKp)
        assertEquals(100L, s.lastScanAt)
    }

    @Test
    fun membersBeforeKp_areBuffered_thenDrainedOnKp() {
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 50L)
        assertNull(s!!.checkpointId)
        assertEquals(setOf(1, 2), s.bufferedBeforeKp)
        assertTrue(s.present.isEmpty())

        s = reduce(s, kp(), now = 100L)
        assertEquals(42, s!!.checkpointId)
        assertEquals(setOf(1, 2), s.present)
        assertTrue(s.bufferedBeforeKp.isEmpty())
        assertEquals(100L, s.lastScanAt)
    }

    @Test
    fun completeCondition_presentSupersetOfRoster() {
        val roster = setOf(1, 2, 3)
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        assertTrue(!s!!.present.containsAll(roster))
        s = reduce(s, ScanEvent.Member(3), now = 30L)
        assertTrue(s!!.present.containsAll(roster))
    }

    @Test
    fun unboundChip_doesNotAdvanceWindow() {
        val before = reduce(null, kp(), now = 1_000L)
        val after = reduce(before, ScanEvent.UnboundChip, now = 5_000L)
        assertEquals(before, after)
        assertEquals(1_000L, after!!.lastScanAt)
    }

    @Test
    fun badKp_doesNotAdvanceWindow() {
        val before = reduce(null, kp(), now = 1_000L)
        val after = reduce(before, ScanEvent.BadKp("чужой"), now = 5_000L)
        assertEquals(before, after)
        assertEquals(1_000L, after!!.lastScanAt)
    }

    @Test
    fun badKp_onNullSession_staysNull() {
        assertNull(reduce(null, ScanEvent.BadKp("чужой"), now = 1_000L))
        assertNull(reduce(null, ScanEvent.UnboundChip, now = 1_000L))
    }

    @Test
    fun member_beforeKp_onNullSession_startsBufferingSession() {
        val s = reduce(null, ScanEvent.Member(5), now = 42L)
        assertNull(s!!.checkpointId)
        assertEquals(setOf(5), s.bufferedBeforeKp)
        assertEquals(42L, s.lastScanAt)
    }

    @Test
    fun kp_repeatScan_preservesPresentAndUpdatesWindow() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        // Re-scan the same КП — members must be kept, window re-stamped.
        s = reduce(s, kp(), now = 300L)
        assertEquals(42, s!!.checkpointId)
        assertEquals(setOf(1, 2), s.present)
        assertEquals(300L, s.lastScanAt)
    }

    @Test
    fun isComplete_kpAndFullRoster_isTrue() {
        var s = reduce(null, kp().copy(expectedCount = 3), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        s = reduce(s, ScanEvent.Member(3), now = 30L)
        assertTrue(isComplete(s))
    }

    @Test
    fun isComplete_nullSession_isFalse() {
        assertFalse(isComplete(null))
    }

    @Test
    fun isComplete_noKp_onlyBuffered_isFalse() {
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 10L)
        s = reduce(s, ScanEvent.Member(3), now = 20L)
        // КП not yet scanned — members are buffered, present is empty, so not complete.
        assertFalse(isComplete(s))
    }

    @Test
    fun isComplete_partialRoster_isFalse() {
        var s = reduce(null, kp().copy(expectedCount = 3), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        assertFalse(isComplete(s))
    }

    @Test
    fun isComplete_rosterZero_isFalse() {
        val s = reduce(null, kp().copy(expectedCount = 0), now = 0L)
        assertFalse(isComplete(s))
    }

    @Test
    fun isComplete_presentLargerThanRoster_isTrue() {
        var s = reduce(null, kp().copy(expectedCount = 2), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        s = reduce(s, ScanEvent.Member(3), now = 30L)
        assertTrue(isComplete(s))
    }

    @Test
    fun isComplete_allBufferedThenKp_isTrue() {
        // Members scan before the КП chip — they land in bufferedBeforeKp, then are drained into
        // present when the KP chip is scanned. isComplete must be true immediately after the drain.
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 10L)
        s = reduce(s, ScanEvent.Member(3), now = 20L)
        s = reduce(s, kp().copy(expectedCount = 3), now = 30L)
        assertTrue(isComplete(s))
    }

    @Test
    fun isComplete_afterKpSwitch_isFalse() {
        // Switching to a different КП resets present to empty; isComplete must return false.
        var s = reduce(null, kp().copy(expectedCount = 2), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        val kpB = ScanEvent.Kp(checkpointId = 99, number = 12, cost = 80, cpUid = "04BBBBBB", cpCode = "CAFEBABE")
        s = reduce(s, kpB.copy(expectedCount = 2), now = 30L)
        assertFalse(isComplete(s))
    }

    // --- Window expiry (isWindowExpired) ---
    // `now`/`lastScanAt` are monotonic elapsedRealtime ms; the boundary is `>=` (exactly 20 s after the
    // last scan is expired). These pin that boundary so a refactor can't silently flip it.

    @Test
    fun windowExpired_nullLastScan_isNeverExpired() {
        assertFalse(isWindowExpired(lastScanAt = null, now = 999_999L))
    }

    @Test
    fun windowExpired_belowWindow_isFalse() {
        // 19_999 ms since last scan — still inside the 20 s window.
        assertFalse(isWindowExpired(lastScanAt = 1_000L, now = 1_000L + 19_999L))
    }

    @Test
    fun windowExpired_exactlyAtWindow_isTrue() {
        // 20_000 ms exactly — `>=` boundary counts as expired.
        assertTrue(isWindowExpired(lastScanAt = 1_000L, now = 1_000L + 20_000L))
    }

    @Test
    fun windowExpired_aboveWindow_isTrue() {
        // 20_001 ms — past the window.
        assertTrue(isWindowExpired(lastScanAt = 1_000L, now = 1_000L + 20_001L))
    }

    @Test
    fun windowExpired_zeroLastScan_isLegalMonotonicReading() {
        // 0L is a legal elapsedRealtime reading right after boot (not a "no scan" sentinel — that is
        // null). It must behave like any other timestamp.
        assertFalse(isWindowExpired(lastScanAt = 0L, now = 19_999L))
        assertTrue(isWindowExpired(lastScanAt = 0L, now = 20_000L))
    }

    @Test
    fun kp_switchCP_resetsPresentAndBufferDrains() {
        val kpB = ScanEvent.Kp(checkpointId = 99, number = 12, cost = 80, cpUid = "04BBBBBB", cpCode = "CAFEBABE")
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        // Switch to a different checkpoint — prior KP's members must NOT carry over.
        s = reduce(s, kpB, now = 300L)
        assertEquals(99, s!!.checkpointId)
        assertTrue(s.present.isEmpty())
        assertTrue(s.bufferedBeforeKp.isEmpty())
        assertEquals(300L, s.lastScanAt)
    }

    // --- completionOnTransition: the scan overlay's confirm-mode entry decision ---

    /** Fold [events] from a fresh session, returning the (wasComplete, session) of the LAST tap. */
    private fun lastTap(rosterSize: Int, vararg events: ScanEvent): Pair<Boolean, ScanSession?> {
        var s: ScanSession? = null
        var was = false
        events.forEachIndexed { i, e ->
            was = isComplete(s)
            // The host stamps every Kp with the persisted take's expectedCount; emulate a steady roster.
            val stamped = if (e is ScanEvent.Kp) e.copy(expectedCount = rosterSize) else e
            s = reduce(s, stamped, now = i * 100L)
        }
        return was to s
    }

    private fun completion(rosterSize: Int, vararg events: ScanEvent): Completion {
        val (was, s) = lastTap(rosterSize, *events)
        return completionOnTransition(was, s)
    }

    @Test
    fun completion_offlineTakeCompletingOnMember_isCounted() {
        assertEquals(Completion.Counted, completion(2, kp(), ScanEvent.Member(1), ScanEvent.Member(2)))
    }

    @Test
    fun completion_cloudTakeCompletingOnMember_confirmsCloud() {
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        assertEquals(
            Completion.Confirm(UploadTarget.Cloud),
            completion(2, cloud, ScanEvent.Member(1), ScanEvent.Member(2)),
        )
    }

    @Test
    fun completion_onKpEvent_whenBufferedMembersDrain_confirmsLocal() {
        val local = kp().copy(checkMethod = CheckMethod.Local)
        assertEquals(
            Completion.Confirm(UploadTarget.Local),
            completion(2, ScanEvent.Member(1), ScanEvent.Member(2), local),
        )
    }

    @Test
    fun completion_stillCollecting_isNone() {
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        assertEquals(Completion.None, completion(3, cloud, ScanEvent.Member(1)))
    }

    @Test
    fun completion_repeatKpAfterCompletion_isNone() {
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        assertEquals(Completion.None, completion(1, cloud, ScanEvent.Member(1), cloud))
    }

    @Test
    fun completion_cloudKpReplacedByOfflineKp_followsLatestRule() {
        // Members buffered, cloud КП 42 lands (complete → confirm), then a different offline КП: present
        // resets, so completing it again follows the NEW (offline) rule.
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        val offline = kp(point = 43, number = 8)
        assertEquals(
            Completion.Counted,
            completion(1, cloud, offline, ScanEvent.Member(1)),
        )
    }

    @Test
    fun completion_offlineKpReplacedByCloudKp_confirms() {
        val offline = kp()
        val cloud = kp(point = 43, number = 8).copy(checkMethod = CheckMethod.Cloud)
        assertEquals(
            Completion.Confirm(UploadTarget.Cloud),
            completion(2, offline, ScanEvent.Member(1), cloud, ScanEvent.Member(1), ScanEvent.Member(2)),
        )
    }

    @Test
    fun completion_nullSessionOrEmptyRoster_isNone() {
        assertEquals(Completion.None, completionOnTransition(false, null))
        val s = reduce(null, kp().copy(checkMethod = CheckMethod.Cloud, expectedCount = 0), now = 0L)
        assertEquals(Completion.None, completionOnTransition(false, s))
    }

    // --- Take snapshots: method kept on a same-КП re-scan; expectedCount always from the host's event ---

    @Test
    fun kp_sameKpRescan_keepsMethod_takesHostExpectedCount() {
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud, expectedCount = 2)
        var s = reduce(null, cloud, now = 0L)
        // Same КП, a different physical tag now says `local`; the host reused the row and re-stamps its
        // persisted count (2) onto the event.
        s = reduce(s, kp().copy(checkMethod = CheckMethod.Local, expectedCount = 2), now = 100L)
        assertEquals(CheckMethod.Cloud, s!!.checkMethod)
        assertEquals(2, s.expectedCount)
        assertEquals(100L, s.lastScanAt)
    }

    @Test
    fun kp_differentKp_replacesMethodAndExpectedCount() {
        var s = reduce(null, kp().copy(checkMethod = CheckMethod.Cloud, expectedCount = 2), now = 0L)
        s = reduce(s, kp(point = 43, number = 8).copy(checkMethod = CheckMethod.Local, expectedCount = 3), now = 100L)
        assertEquals(CheckMethod.Local, s!!.checkMethod)
        assertEquals(3, s.expectedCount)
    }

    @Test
    fun completion_sameKpRescanWithChangedMethod_confirmsOriginalTarget() {
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud)
        val local = kp().copy(checkMethod = CheckMethod.Local)
        assertEquals(
            Completion.Confirm(UploadTarget.Cloud),
            completion(1, cloud, local, ScanEvent.Member(1)),
        )
    }

    @Test
    fun completion_rosterShrankWhileKpCallbackSuspended_followsPersistedCount() {
        // Regression: the host persisted the take with expectedCount = 2 (roster snapshot before its
        // NFC/Room suspension); a sync shrank the roster to one meanwhile. The session must follow the
        // count carried on the event (the DB row's), so one member does NOT enter confirm mode for a
        // take the DB still holds incomplete.
        val cloud = kp().copy(checkMethod = CheckMethod.Cloud, expectedCount = 2)
        var s = reduce(null, cloud, now = 0L)
        var was = isComplete(s)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        assertEquals(Completion.None, completionOnTransition(was, s))
        was = isComplete(s)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        assertEquals(Completion.Confirm(UploadTarget.Cloud), completionOnTransition(was, s))
    }

    @Test
    fun completion_bufferedMemberThenKp_waitsForPersistedCount() {
        // Session opened by a member tap while the roster had one member; the host persisted the КП take
        // with two expected. The first member alone must not complete it.
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        var was = isComplete(s)
        s = reduce(s, kp().copy(checkMethod = CheckMethod.Cloud, expectedCount = 2), now = 100L)
        assertEquals(Completion.None, completionOnTransition(was, s))
        was = isComplete(s)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        assertEquals(Completion.Confirm(UploadTarget.Cloud), completionOnTransition(was, s))
    }

    @Test
    fun completion_offlineTake_followsPersistedCount() {
        // Offline path: the take still needs all three the host persisted, whatever the live roster does.
        var s = reduce(null, kp().copy(expectedCount = 3), now = 0L)
        var was = isComplete(s)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        assertEquals(Completion.None, completionOnTransition(was, s))
        was = isComplete(s)
        s = reduce(s, ScanEvent.Member(2), now = 200L)
        assertEquals(Completion.None, completionOnTransition(was, s))
        was = isComplete(s)
        s = reduce(s, ScanEvent.Member(3), now = 300L)
        assertEquals(Completion.Counted, completionOnTransition(was, s))
    }

    @Test
    fun kp_unstampedEvent_neverCompletes() {
        // classifyTag leaves expectedCount at 0; an event the host never stamped must not complete.
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        assertFalse(isComplete(s))
    }
}
