package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAutoControlTest {

    private val bools = listOf(false, true)

    @Test
    fun test_neverActs() {
        for (recording in bools) for (finishTaken in bools) {
            assertEquals(TrackAutoAction.None, trackAutoAction("test", recording, finishTaken))
        }
    }

    @Test
    fun nullType_neverActs() {
        for (recording in bools) for (finishTaken in bools) {
            assertEquals(TrackAutoAction.None, trackAutoAction(null, recording, finishTaken))
        }
    }

    @Test
    fun startKpOrUnknown_notRecording_noFinish_starts() {
        for (type in listOf("start", "kp", "bonus")) {
            assertEquals(TrackAutoAction.Start, trackAutoAction(type, recording = false, finishTaken = false))
        }
    }

    @Test
    fun startKpOrUnknown_alreadyRecording_doesNothing() {
        for (type in listOf("start", "kp", "bonus")) for (finishTaken in bools) {
            assertEquals(TrackAutoAction.None, trackAutoAction(type, recording = true, finishTaken = finishTaken))
        }
    }

    @Test
    fun startKpOrUnknown_afterFinish_doesNotStart() {
        for (type in listOf("start", "kp", "bonus")) {
            assertEquals(TrackAutoAction.None, trackAutoAction(type, recording = false, finishTaken = true))
        }
    }

    @Test
    fun finish_recording_stops() {
        assertEquals(TrackAutoAction.Stop, trackAutoAction("finish", recording = true, finishTaken = false))
    }

    @Test
    fun finish_secondFinishAfterManualRestart_stops() {
        assertEquals(TrackAutoAction.Stop, trackAutoAction("finish", recording = true, finishTaken = true))
    }

    @Test
    fun finish_notRecording_doesNothing() {
        for (finishTaken in bools) {
            assertEquals(TrackAutoAction.None, trackAutoAction("finish", recording = false, finishTaken = finishTaken))
        }
    }

    @Test
    fun typeVariants_normalised() {
        for (t in listOf("Finish", "FINISH", " finish", "finish\n")) {
            assertEquals(TrackAutoAction.Stop, trackAutoAction(t, recording = true, finishTaken = false))
        }
        for (t in listOf("TEST", " Test ")) {
            assertEquals(TrackAutoAction.None, trackAutoAction(t, recording = false, finishTaken = false))
        }
    }

    @Test
    fun blankType_fallsThroughToStart() {
        for (t in listOf("", "  ")) {
            assertEquals(TrackAutoAction.Start, trackAutoAction(t, recording = false, finishTaken = false))
        }
    }

    // --- hasFinishTake ---

    private val types = mapOf(1 to "start", 2 to "kp", 3 to "finish", 4 to "Finish")

    @Test
    fun hasFinishTake_emptyMarks_false() {
        assertFalse(hasFinishTake(emptyList()) { types[it] })
    }

    @Test
    fun hasFinishTake_noFinish_false() {
        assertFalse(hasFinishTake(listOf(1, 2, 2)) { types[it] })
    }

    @Test
    fun hasFinishTake_unknownCheckpoint_false() {
        assertFalse(hasFinishTake(listOf(99)) { types[it] })
    }

    @Test
    fun hasFinishTake_anyFinishTake_true() {
        assertTrue(hasFinishTake(listOf(1, 3)) { types[it] })
        assertTrue(hasFinishTake(listOf(4)) { types[it] })
    }

    // --- trackAutoDecision ---

    @Test
    fun decision_startPermitted_startsAndClearsPending() {
        assertEquals(
            TrackAutoDecision(TrackAutoAction.Start, pending = false),
            trackAutoDecision("kp", recordingTeamId = null, takeTeamId = 7, finishTaken = false, permitted = true),
        )
    }

    @Test
    fun decision_startNoPermission_parks() {
        assertEquals(
            TrackAutoDecision(TrackAutoAction.None, pending = true),
            trackAutoDecision("kp", recordingTeamId = null, takeTeamId = 7, finishTaken = false, permitted = false),
        )
    }

    @Test
    fun decision_recordingAnotherTeam_countsAsNotRecording() {
        assertEquals(
            TrackAutoDecision(TrackAutoAction.Start, pending = false),
            trackAutoDecision("kp", recordingTeamId = 8, takeTeamId = 7, finishTaken = false, permitted = true),
        )
        assertEquals(
            TrackAutoDecision(TrackAutoAction.None, pending = false),
            trackAutoDecision("finish", recordingTeamId = 8, takeTeamId = 7, finishTaken = false, permitted = true),
        )
    }

    @Test
    fun decision_finishNotRecording_clearsPending() {
        for (permitted in bools) {
            assertEquals(
                TrackAutoDecision(TrackAutoAction.None, pending = false),
                trackAutoDecision("finish", recordingTeamId = null, takeTeamId = 7, finishTaken = false, permitted = permitted),
            )
        }
    }

    @Test
    fun decision_finishRecording_stopsAndClearsPending() {
        assertEquals(
            TrackAutoDecision(TrackAutoAction.Stop, pending = false),
            trackAutoDecision("finish", recordingTeamId = 7, takeTeamId = 7, finishTaken = true, permitted = true),
        )
    }

    @Test
    fun decision_noActionNonFinish_leavesPending() {
        // test КП, already recording, finish latch, unknown КП: pending untouched.
        assertEquals(TrackAutoDecision(TrackAutoAction.None, null),
            trackAutoDecision("test", recordingTeamId = null, takeTeamId = 7, finishTaken = false, permitted = false))
        assertEquals(TrackAutoDecision(TrackAutoAction.None, null),
            trackAutoDecision("kp", recordingTeamId = 7, takeTeamId = 7, finishTaken = false, permitted = true))
        assertEquals(TrackAutoDecision(TrackAutoAction.None, null),
            trackAutoDecision("kp", recordingTeamId = null, takeTeamId = 7, finishTaken = true, permitted = false))
        assertEquals(TrackAutoDecision(TrackAutoAction.None, null),
            trackAutoDecision(null, recordingTeamId = null, takeTeamId = 7, finishTaken = false, permitted = false))
    }

    // --- resolvePendingTrackStart ---

    private fun resolve(
        pending: Boolean = true,
        settled: Boolean = true,
        raceId: Int? = 1,
        teamId: Int? = 7,
        recording: Boolean = false,
        permitted: Boolean = false,
        askedThisSession: Boolean = false,
    ) = resolvePendingTrackStart(pending, settled, raceId, teamId, recording, permitted, askedThisSession)

    @Test
    fun resolve_notPending_waits() {
        assertEquals(PendingTrackStart.Wait, resolve(pending = false, permitted = true))
    }

    @Test
    fun resolve_notSettled_waits() {
        assertEquals(PendingTrackStart.Wait, resolve(settled = false, permitted = true))
        assertEquals(PendingTrackStart.Wait, resolve(settled = false))
    }

    @Test
    fun resolve_idsUnknown_waitsKeepingFlag() {
        assertEquals(PendingTrackStart.Wait, resolve(raceId = null, permitted = true))
        assertEquals(PendingTrackStart.Wait, resolve(teamId = null, permitted = true))
    }

    @Test
    fun resolve_alreadyRecording_drops() {
        assertEquals(PendingTrackStart.Drop, resolve(recording = true, permitted = true))
    }

    @Test
    fun resolve_permitted_startsNow() {
        for (asked in bools) assertEquals(PendingTrackStart.StartNow, resolve(permitted = true, askedThisSession = asked))
    }

    @Test
    fun resolve_notPermitted_notAsked_asks() {
        assertEquals(PendingTrackStart.AskPermission, resolve())
    }

    @Test
    fun resolve_notPermitted_alreadyAsked_drops() {
        assertEquals(PendingTrackStart.Drop, resolve(askedThisSession = true))
    }

    // --- shouldAskNotificationsAfterAutoStart ---

    @Test
    fun notificationsAsk_onlyWhenRuntimeUngrantedFreshAndUnasked() {
        assertTrue(shouldAskNotificationsAfterAutoStart(true, granted = false, deniedBefore = false, askedThisSession = false))
        assertFalse(shouldAskNotificationsAfterAutoStart(false, granted = false, deniedBefore = false, askedThisSession = false))
        assertFalse(shouldAskNotificationsAfterAutoStart(true, granted = true, deniedBefore = false, askedThisSession = false))
        assertFalse(shouldAskNotificationsAfterAutoStart(true, granted = false, deniedBefore = true, askedThisSession = false))
        assertFalse(shouldAskNotificationsAfterAutoStart(true, granted = false, deniedBefore = false, askedThisSession = true))
    }

    // --- activeRecordingTeamId ---

    @Test
    fun activeRecordingTeamId_idleIsNull() {
        assertEquals(null, TrackState.Idle.activeRecordingTeamId())
    }

    @Test
    fun activeRecordingTeamId_recordingIsTeam() {
        assertEquals(7, TrackState.Recording(teamId = 7, pointCount = 3).activeRecordingTeamId())
    }

    @Test
    fun activeRecordingTeamId_stoppingIsNull_soATakeRestarts() {
        val stopping = TrackState.Recording(teamId = 7, pointCount = 3, stopping = true)
        assertEquals(null, stopping.activeRecordingTeamId())
        val decision = trackAutoDecision("kp", stopping.activeRecordingTeamId(), takeTeamId = 7, finishTaken = false, permitted = true)
        assertEquals(TrackAutoAction.Start, decision.action)
    }
}
