package ru.kolco24.kolco24.ui.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.track.TargetUploadOutcome
import ru.kolco24.kolco24.data.track.UploadResultKind

class FinishLineVisibilityTest {

    @Test
    fun `no outcome and zero uploaded hides the line`() {
        assertFalse(showFinishLine(TargetLine(uploaded = 0, outcome = null)))
    }

    @Test
    fun `an outcome shows the line even with zero uploaded`() {
        val outcome = TargetUploadOutcome(kind = UploadResultKind.Offline, atWallMs = 0L)
        assertTrue(showFinishLine(TargetLine(uploaded = 0, outcome = outcome)))
    }

    @Test
    fun `uploaded progress without an outcome shows the line`() {
        assertTrue(showFinishLine(TargetLine(uploaded = 1, outcome = null)))
    }
}
