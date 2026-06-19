package ru.kolco24.kolco24.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {

    private fun kp(point: Int = 42, number: Int = 7, cost: Int = 50) =
        ScanEvent.Kp(point = point, number = number, cost = cost, cpUid = "04AABBCC", cpCode = "DEADBEEF")

    @Test
    fun kp_onNullSession_fillsCheckpointFields() {
        val s = reduce(null, kp(), now = 1_000L)!!
        assertEquals(42, s.point)
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
    fun member_isIdempotent() {
        var s = reduce(null, kp(), now = 0L)
        s = reduce(s, ScanEvent.Member(1), now = 100L)
        s = reduce(s, ScanEvent.Member(1), now = 200L)
        assertEquals(setOf(1), s!!.present)
        // a repeated member still refreshes the window
        assertEquals(200L, s.lastScanAt)
    }

    @Test
    fun membersBeforeKp_areBuffered_thenDrainedOnKp() {
        var s = reduce(null, ScanEvent.Member(1), now = 0L)
        s = reduce(s, ScanEvent.Member(2), now = 50L)
        assertNull(s!!.point)
        assertEquals(setOf(1, 2), s.bufferedBeforeKp)
        assertTrue(s.present.isEmpty())

        s = reduce(s, kp(), now = 100L)
        assertEquals(42, s!!.point)
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
        assertNull(s!!.point)
        assertEquals(setOf(5), s.bufferedBeforeKp)
        assertEquals(42L, s.lastScanAt)
    }
}
