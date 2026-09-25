package ru.kolco24.kolco24.ui.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity

class ControlTimeTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val base = 1_700_000_000_000L

    private val types = mapOf(1 to "start", 2 to "kp", 3 to "finish")

    private fun mark(
        id: String,
        checkpointId: Int,
        takenAt: Long,
        method: String = "nfc",
        trustedTakenAt: Long? = null,
        elapsedRealtimeAt: Long? = null,
        bootCount: Int? = null,
        complete: Boolean = true,
    ) = MarkEntity(
        id = id,
        raceId = 1,
        teamId = 7,
        checkpointId = checkpointId,
        checkpointNumber = checkpointId,
        cost = 1,
        method = method,
        cpUid = "UID",
        cpCode = "CODE",
        present = emptyList(),
        expectedCount = 0,
        complete = complete,
        takenAt = takenAt,
        updatedAt = takenAt,
        trustedTakenAt = trustedTakenAt,
        elapsedRealtimeAt = elapsedRealtimeAt,
        bootCount = bootCount,
    )

    private fun state(
        marks: List<MarkEntity>,
        controlMinutes: Int = 480,
        nowMs: Long = base,
        checkpointTypes: Map<Int, String> = types,
    ) = controlTimeState(marks, checkpointTypes, controlMinutes, nowMs) { it.takenAt }

    // --- resolveMarkTime ---

    @Test
    fun `resolveMarkTime prefers trustedTakenAt`() {
        val m = mark("a", 1, takenAt = 100L, trustedTakenAt = 200L, elapsedRealtimeAt = 5L, bootCount = 1)
        assertEquals(200L, resolveMarkTime(m) { _, _ -> 999L })
    }

    @Test
    fun `resolveMarkTime re-anchors elapsed via trustedAt with boot count`() {
        val m = mark("a", 1, takenAt = 100L, elapsedRealtimeAt = 5L, bootCount = 3)
        var seen: Pair<Long, Int?>? = null
        val t = resolveMarkTime(m) { e, b -> seen = e to b; 777L }
        assertEquals(777L, t)
        assertEquals(5L to 3, seen)
    }

    @Test
    fun `resolveMarkTime falls back to takenAt when trustedAt is null`() {
        val m = mark("a", 1, takenAt = 100L, elapsedRealtimeAt = 5L, bootCount = 3)
        assertEquals(100L, resolveMarkTime(m) { _, _ -> null })
    }

    @Test
    fun `resolveMarkTime falls back to takenAt without elapsedRealtimeAt`() {
        val m = mark("a", 1, takenAt = 100L)
        assertEquals(100L, resolveMarkTime(m) { _, _ -> 999L })
    }

    // --- states ---

    @Test
    fun `unknown when control time is not set`() {
        assertEquals(ControlTimeState.Unknown, state(emptyList(), controlMinutes = 0))
        assertEquals(
            ControlTimeState.Unknown,
            state(listOf(mark("s", 1, base)), controlMinutes = 0, nowMs = base + hour),
        )
    }

    @Test
    fun `not started shows the limit`() {
        assertEquals(ControlTimeState.NotStarted(8 * hour), state(emptyList()))
    }

    @Test
    fun `running counts down from the start`() {
        val s = state(listOf(mark("s", 1, base)), nowMs = base + hour + 30_000)
        assertEquals(ControlTimeState.Running(7 * hour - 30_000), s)
    }

    @Test
    fun `overtime once the limit is reached`() {
        assertEquals(
            ControlTimeState.Overtime(0),
            state(listOf(mark("s", 1, base)), nowMs = base + 8 * hour),
        )
        assertEquals(
            ControlTimeState.Overtime(12 * min),
            state(listOf(mark("s", 1, base)), nowMs = base + 8 * hour + 12 * min),
        )
    }

    @Test
    fun `finished within control time`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 7 * hour + 48 * min))
        assertEquals(ControlTimeState.Finished(7 * hour + 48 * min, false), state(marks))
    }

    @Test
    fun `finished late is flagged late`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 8 * hour + 12 * min))
        assertEquals(ControlTimeState.Finished(8 * hour + 12 * min, true), state(marks))
    }

    @Test
    fun `finish exactly at control time is not late`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 8 * hour))
        assertEquals(ControlTimeState.Finished(8 * hour, false), state(marks))
    }

    @Test
    fun `finish at the start instant counts`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base))
        assertEquals(ControlTimeState.Finished(0, false), state(marks))
    }

    @Test
    fun `last ms before control time is still running`() {
        assertEquals(
            ControlTimeState.Running(1),
            state(listOf(mark("s", 1, base)), nowMs = base + 8 * hour - 1),
        )
    }

    @Test
    fun `now before start is not clamped`() {
        // Mixed trusted/wall scales: the countdown exceeds the КВ rather than freezing the tick schedule.
        assertEquals(
            ControlTimeState.Running(8 * hour + 3 * min),
            state(listOf(mark("s", 1, base)), nowMs = base - 3 * min),
        )
    }

    @Test
    fun `negative control time is unknown`() {
        assertEquals(ControlTimeState.Unknown, state(listOf(mark("s", 1, base)), controlMinutes = -5))
    }

    @Test
    fun `incomplete start take still starts the clock`() {
        assertEquals(
            ControlTimeState.Running(7 * hour),
            state(listOf(mark("s", 1, base, complete = false)), nowMs = base + hour),
        )
    }

    // --- rules ---

    @Test
    fun `earliest of two start takes wins`() {
        val marks = listOf(mark("s2", 1, base + hour), mark("s1", 1, base))
        assertEquals(ControlTimeState.Running(6 * hour), state(marks, nowMs = base + 2 * hour))
    }

    @Test
    fun `finish before start is ignored`() {
        val marks = listOf(mark("f", 3, base - min), mark("s", 1, base))
        assertEquals(ControlTimeState.Running(7 * hour), state(marks, nowMs = base + hour))
    }

    @Test
    fun `earliest finish at or after start wins`() {
        val marks = listOf(
            mark("s", 1, base),
            mark("f2", 3, base + 3 * hour),
            mark("f1", 3, base + 2 * hour),
        )
        assertEquals(ControlTimeState.Finished(2 * hour, false), state(marks))
    }

    @Test
    fun `finish without start is not started`() {
        assertEquals(ControlTimeState.NotStarted(8 * hour), state(listOf(mark("f", 3, base))))
    }

    @Test
    fun `photo mark on start is not a start`() {
        assertEquals(
            ControlTimeState.NotStarted(8 * hour),
            state(listOf(mark("s", 1, base, method = "photo"))),
        )
    }

    @Test
    fun `photo mark on finish does not finish`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + hour, method = "photo"))
        assertEquals(ControlTimeState.Running(6 * hour), state(marks, nowMs = base + 2 * hour))
    }

    @Test
    fun `kp take is neither start nor finish`() {
        val marks = listOf(mark("k", 2, base - hour), mark("s", 1, base), mark("k2", 2, base + hour))
        assertEquals(ControlTimeState.Running(6 * hour), state(marks, nowMs = base + 2 * hour))
        assertEquals(ControlTimeState.NotStarted(8 * hour), state(listOf(mark("k", 2, base))))
    }

    @Test
    fun `zero time is ignored`() {
        assertEquals(ControlTimeState.NotStarted(8 * hour), state(listOf(mark("s", 1, 0L))))
    }

    @Test
    fun `without legend types there is no start`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + hour))
        assertEquals(ControlTimeState.NotStarted(8 * hour), state(marks, checkpointTypes = emptyMap()))
        assertEquals(
            ControlTimeState.Unknown,
            state(marks, controlMinutes = 0, checkpointTypes = emptyMap()),
        )
    }

    @Test
    fun `timeOf drives the start time`() {
        val m = mark("s", 1, takenAt = base, trustedTakenAt = base + hour)
        val s = controlTimeState(listOf(m), types, 480, base + 2 * hour) { it.trustedTakenAt ?: it.takenAt }
        assertEquals(ControlTimeState.Running(7 * hour), s)
    }

    // --- rounding ---

    @Test
    fun `finish 59 s late is not overtime`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 8 * hour + 59_000))
        assertEquals(ControlTimeState.Finished(8 * hour + 59_000, false), state(marks))
    }

    @Test
    fun `finish 60 s late is overtime`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 8 * hour + 60_000))
        assertEquals(ControlTimeState.Finished(8 * hour + min, true), state(marks))
    }

    @Test
    fun `finished without control time is never late`() {
        val marks = listOf(mark("s", 1, base), mark("f", 3, base + 20 * hour))
        assertEquals(ControlTimeState.Finished(20 * hour, false), state(marks, controlMinutes = 0))
    }

    // --- formatHoursMinutes ---

    @Test
    fun `formatHoursMinutes floors minutes`() {
        assertEquals("0:00", formatHoursMinutes(0))
        assertEquals("0:00", formatHoursMinutes(59_999))
        assertEquals("8:00", formatHoursMinutes(8 * hour))
        assertEquals("3:27", formatHoursMinutes(3 * hour + 27 * min + 59_000))
        assertEquals("10:05", formatHoursMinutes(10 * hour + 5 * min))
        assertEquals("26:40", formatHoursMinutes(26 * hour + 40 * min))
    }

    // --- msUntilNextChange ---

    @Test
    fun `msUntilNextChange ticks on the minute from start`() {
        assertEquals(1L, msUntilNextChange(ControlTimeState.Running(60_000)))
        assertEquals(30_001L, msUntilNextChange(ControlTimeState.Running(90_000)))
        assertEquals(59_999L, msUntilNextChange(ControlTimeState.Running(59_999)))
        assertEquals(1L, msUntilNextChange(ControlTimeState.Running(1)))
        assertEquals(60_000L, msUntilNextChange(ControlTimeState.Overtime(0)))
        assertEquals(59_000L, msUntilNextChange(ControlTimeState.Overtime(61_000)))
    }

    @Test
    fun `msUntilNextChange is null for static states`() {
        assertNull(msUntilNextChange(ControlTimeState.Unknown))
        assertNull(msUntilNextChange(ControlTimeState.NotStarted(8 * hour)))
        assertNull(msUntilNextChange(ControlTimeState.Finished(hour, false)))
        assertNull(msUntilNextChange(ControlTimeState.Finished(9 * hour, true)))
    }

    @Test
    fun `msUntilNextChange lands exactly on the next label change`() {
        val start = listOf(mark("s", 1, base))
        val offsets = listOf(
            0L, 1L, 30_000L, 59_999L, 60_000L, hour + 12_345L,
            8 * hour - 60_001L, 8 * hour - 60_000L, 8 * hour - 59_999L, 8 * hour - 1,
            8 * hour, 8 * hour + 1, 8 * hour + 61_000L,
        )
        for (offset in offsets) {
            val now = base + offset
            val kv = state(start, nowMs = now)
            val d = msUntilNextChange(kv)!!
            assertEquals("offset $offset", controlTimeLabel(kv), controlTimeLabel(state(start, nowMs = now + d - 1)))
            assertNotEquals("offset $offset", controlTimeLabel(kv), controlTimeLabel(state(start, nowMs = now + d)))
        }
    }

    // --- controlTimeLabel ---

    @Test
    fun `controlTimeLabel covers every state`() {
        assertEquals(ControlTimeLabel("КВ", "—", false), controlTimeLabel(ControlTimeState.Unknown))
        assertEquals(
            ControlTimeLabel("КВ", "8:00", false),
            controlTimeLabel(ControlTimeState.NotStarted(8 * hour)),
        )
        assertEquals(
            ControlTimeLabel("ДО КВ", "3:27", false),
            controlTimeLabel(ControlTimeState.Running(3 * hour + 27 * min + 30_000)),
        )
        assertEquals(
            ControlTimeLabel("ОПОЗДАНИЕ", "+0:12", true),
            controlTimeLabel(ControlTimeState.Overtime(12 * min + 5_000)),
        )
        assertEquals(
            ControlTimeLabel("ВРЕМЯ", "7:48", false),
            controlTimeLabel(ControlTimeState.Finished(7 * hour + 48 * min, false)),
        )
        assertEquals(
            ControlTimeLabel("ВРЕМЯ", "8:12", true),
            controlTimeLabel(ControlTimeState.Finished(8 * hour + 12 * min, true)),
        )
    }
}
