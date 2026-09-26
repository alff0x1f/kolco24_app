package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
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
}
