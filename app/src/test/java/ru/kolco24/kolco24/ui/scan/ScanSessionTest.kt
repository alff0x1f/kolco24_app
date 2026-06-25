package ru.kolco24.kolco24.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        s = reduce(s, ScanEvent.Member(3), now = 30L)
        assertTrue(isComplete(s, rosterSize = 3))
    }

    @Test
    fun isComplete_nullSession_isFalse() {
        assertFalse(isComplete(null, rosterSize = 3))
    }

    @Test
    fun isComplete_noKp_onlyBuffered_isFalse() {
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 10L)
        s = reduce(s, ScanEvent.Member(3), now = 20L)
        // КП not yet scanned — members are buffered, present is empty, so not complete.
        assertFalse(isComplete(s, rosterSize = 3))
    }

    @Test
    fun isComplete_partialRoster_isFalse() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        assertFalse(isComplete(s, rosterSize = 3))
    }

    @Test
    fun isComplete_rosterZero_isFalse() {
        val s = reduce(null, kp(), now = 0L)
        assertFalse(isComplete(s, rosterSize = 0))
    }

    @Test
    fun isComplete_presentLargerThanRoster_isTrue() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        s = reduce(s, ScanEvent.Member(3), now = 30L)
        assertTrue(isComplete(s, rosterSize = 2))
    }

    @Test
    fun isComplete_allBufferedThenKp_isTrue() {
        // Members scan before the КП chip — they land in bufferedBeforeKp, then are drained into
        // present when the KP chip is scanned. isComplete must be true immediately after the drain.
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 10L)
        s = reduce(s, ScanEvent.Member(3), now = 20L)
        s = reduce(s, kp(), now = 30L)
        assertTrue(isComplete(s, rosterSize = 3))
    }

    @Test
    fun isComplete_afterKpSwitch_isFalse() {
        // Switching to a different КП resets present to empty; isComplete must return false.
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 10L)
        s = reduce(s, ScanEvent.Member(2), now = 20L)
        val kpB = ScanEvent.Kp(checkpointId = 99, number = 12, cost = 80, cpUid = "04BBBBBB", cpCode = "CAFEBABE")
        s = reduce(s, kpB, now = 30L)
        assertFalse(isComplete(s, rosterSize = 2))
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
}
