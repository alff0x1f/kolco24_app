package ru.kolco24.kolco24

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Covers the pure mint decision for the recording-session segment id. */
class SegmentIdTest {

    @Test
    fun freshStart_nullCurrent_mintsNew() {
        val result = nextSegmentId(current = null, wasTearingDown = false) { "minted" }
        assertEquals("minted", result)
    }

    @Test
    fun idempotentReEntry_keepsCurrent() {
        var minted = false
        val result = nextSegmentId(current = "existing", wasTearingDown = false) {
            minted = true
            "minted"
        }
        assertEquals("existing", result)
        // mint must not even be called on the keep path
        assertEquals(false, minted)
    }

    @Test
    fun teardownInFlight_replacesWithNew_evenWhenCurrentNonNull() {
        val result = nextSegmentId(current = "existing", wasTearingDown = true) { "minted" }
        assertEquals("minted", result)
    }

    @Test
    fun teardownInFlight_nullCurrent_mintsNew() {
        val result = nextSegmentId(current = null, wasTearingDown = true) { "minted" }
        assertEquals("minted", result)
    }

    @Test
    fun stopThenStart_producesTwoDistinctSegments() {
        var n = 0
        val mint = { "seg-${n++}" }
        // First session.
        val first = nextSegmentId(current = null, wasTearingDown = false, mint = mint)
        // finishTeardown() reset segmentId to null; the next start mints a fresh one.
        val second = nextSegmentId(current = null, wasTearingDown = false, mint = mint)
        assertNotEquals(first, second)
    }
}
