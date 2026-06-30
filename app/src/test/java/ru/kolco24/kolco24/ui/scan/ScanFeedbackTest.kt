package ru.kolco24.kolco24.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScanFeedbackTest {

    @Test
    fun kp_isSuccess() {
        val event = ScanEvent.Kp(checkpointId = 1, number = 7, cost = 50, cpUid = "UID", cpCode = "CODE")
        assertEquals(ScanFeedbackKind.Success, feedbackFor(event))
    }

    @Test
    fun member_isSuccess() {
        assertEquals(ScanFeedbackKind.Success, feedbackFor(ScanEvent.Member(numberInTeam = 3)))
    }

    @Test
    fun unboundChip_isFailure() {
        assertEquals(ScanFeedbackKind.Failure, feedbackFor(ScanEvent.UnboundChip))
    }

    @Test
    fun badKp_isFailure() {
        assertEquals(ScanFeedbackKind.Failure, feedbackFor(ScanEvent.BadKp("неизвестный чип")))
    }

    @Test
    fun feedbackFor_neverReturnsNeutral() {
        val events = listOf(
            ScanEvent.Kp(checkpointId = 1, number = 7, cost = 50, cpUid = "U", cpCode = "C"),
            ScanEvent.Member(numberInTeam = 1),
            ScanEvent.UnboundChip,
            ScanEvent.BadKp("reason"),
        )
        events.forEach { event ->
            assertNotEquals(ScanFeedbackKind.Neutral, feedbackFor(event))
        }
    }
}
