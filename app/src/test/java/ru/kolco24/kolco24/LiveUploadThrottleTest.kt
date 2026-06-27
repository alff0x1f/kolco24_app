package ru.kolco24.kolco24

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure throttle decision for in-recording live track uploads. */
class LiveUploadThrottleTest {

    @Test
    fun deltaBelowInterval_doesNotUpload() {
        assertFalse(
            shouldLiveUpload(
                nowElapsed = 599_999,
                lastUploadElapsed = 0,
                minIntervalMs = LIVE_UPLOAD_MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun deltaAtInterval_uploads() {
        assertTrue(
            shouldLiveUpload(
                nowElapsed = 600_000,
                lastUploadElapsed = 0,
                minIntervalMs = LIVE_UPLOAD_MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun deltaAboveInterval_uploads() {
        assertTrue(
            shouldLiveUpload(
                nowElapsed = 600_001,
                lastUploadElapsed = 0,
                minIntervalMs = LIVE_UPLOAD_MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun neverUploaded_uploadsRegardlessOfNow() {
        assertTrue(
            shouldLiveUpload(
                nowElapsed = 999_999_999,
                lastUploadElapsed = null,
                minIntervalMs = LIVE_UPLOAD_MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun neverUploaded_justBooted_uploads() {
        // Reboot edge a 0L sentinel would break: now < interval but no prior upload → must fire.
        assertTrue(
            shouldLiveUpload(
                nowElapsed = 5_000,
                lastUploadElapsed = null,
                minIntervalMs = LIVE_UPLOAD_MIN_INTERVAL_MS
            )
        )
    }
}
