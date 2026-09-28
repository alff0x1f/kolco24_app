package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class TrackSpeedTest {

    private data class Pt(
        override val id: String,
        override val lat: Double,
        override val lon: Double,
        override val accuracy: Float,
        override val wallMs: Long,
        override val segmentId: String = "seg",
        override val elapsedRealtimeAt: Long = 0L,
        override val bootCount: Int? = null,
        override val trustedMs: Long? = null,
    ) : TrackPointLike

    private val baseLat = 55.0
    private val baseLon = 37.0

    /** Meters of latitude per degree for the 6_371_000 m sphere — points along one meridian. */
    private val mPerDeg = 6_371_000.0 * Math.PI / 180.0

    /** 4 km/h in m/s and the distance covered in 15 s at that speed. */
    private val walkMps = 4.0 / 3.6
    private val walkStepM = walkMps * 15

    private val stop = SpeedStroke.Band(SpeedBand.Stop)

    private fun pt(tSec: Long, northM: Double, eastM: Double = 0.0, acc: Float = 10f, seg: String = "seg") =
        ptMs(tSec * 1000, northM, eastM, acc, seg)

    private fun ptMs(ms: Long, northM: Double, eastM: Double = 0.0, acc: Float = 10f, seg: String = "seg") = Pt(
        id = "p$ms",
        lat = baseLat + northM / mPerDeg,
        lon = baseLon + eastM / (mPerDeg * cos(Math.toRadians(baseLat))),
        accuracy = acc,
        wallMs = ms,
        segmentId = seg,
    )

    /** Deterministic non-periodic noise within ±[amplitude] m (golden ratio — no repeats). */
    private fun jitter(i: Int, amplitude: Double): Pair<Double, Double> {
        val phi = 0.618_033_988_75
        val u = (i * phi) % 1
        val v = (i * phi * phi + 0.3) % 1
        return (u * 2 - 1) * amplitude to (v * 2 - 1) * amplitude
    }

    /** [count] points every 15 s from [startSec]/[startM] at [mps]. */
    private fun steady(count: Int, startSec: Long = 0, startM: Double = 0.0, mps: Double) =
        (0 until count).map { i -> pt(startSec + i * 15L, startM + i * 15 * mps) }

    private fun isClose(a: Double, b: Double, tol: Double = 1e-3) = abs(a - b) <= tol

    // ---- speedBand ----

    @Test
    fun speedBand_boundariesAreLowerInclusive() {
        assertEquals(SpeedBand.Stop, speedBand(0.0))
        assertEquals(SpeedBand.Stop, speedBand(0.99))
        assertEquals(SpeedBand.Slow, speedBand(1.0))
        assertEquals(SpeedBand.Slow, speedBand(2.99))
        assertEquals(SpeedBand.Walk, speedBand(3.0))
        assertEquals(SpeedBand.Walk, speedBand(4.99))
        assertEquals(SpeedBand.Brisk, speedBand(5.0))
        assertEquals(SpeedBand.Brisk, speedBand(6.99))
        assertEquals(SpeedBand.Fast, speedBand(7.0))
        assertEquals(SpeedBand.Fast, speedBand(60.0))
    }

    @Test
    fun legendLabels_followBandLimits() {
        assertEquals(listOf("<1", "1–3", "3–5", "5–7", "7+"), SpeedBand.entries.map(::speedBandLegendLabel))
    }

    // ---- stepSpeedsMps ----

    @Test
    fun steadyWalk_givesWalkSpeedOnEveryStep() {
        val speeds = stepSpeedsMps(steady(count = 20, mps = walkMps))
        assertEquals(19, speeds.size)
        assertTrue(speeds.all { isClose(it, walkMps) })
    }

    @Test
    fun stationaryJitter_staysBelowOneKmh() {
        val line = (0 until 20).map { i -> pt(i * 15L, if (i % 2 == 0) -10.0 else 10.0) }
        assertTrue(stepSpeedsMps(line).all { it * 3.6 < 1 })
    }

    @Test
    fun longStep_usesOwnSpeed_andWindowDoesNotCrossIt() {
        // 4 km/h, then a 300 s step of 30 m, then 8 km/h.
        val left = steady(count = 7, mps = walkMps)
        val right = steady(count = 7, startSec = 90 + 300, startM = 6 * walkStepM + 30, mps = 2 * walkMps)
        val speeds = stepSpeedsMps(left + right)

        assertEquals(13, speeds.size)
        assertTrue(speeds.subList(0, 6).all { isClose(it, walkMps) })
        assertTrue(isClose(speeds[6], 30.0 / 300.0))
        assertTrue(speeds.subList(7, 13).all { isClose(it, 2 * walkMps) })
    }

    @Test
    fun windowAtLineStart_growsRight() {
        // First step moves, then standing: step 0's window grows right to 90 s.
        val line = listOf(pt(0, 0.0)) + (1 until 10).map { i -> pt(i * 15L, walkStepM) }
        assertTrue(isClose(stepSpeedsMps(line)[0], walkStepM / 90))
    }

    @Test
    fun windowAtLineEnd_growsLeft() {
        val line = (0 until 9).map { i -> pt(i * 15L, 0.0) } + pt(135, walkStepM)
        assertTrue(isClose(stepSpeedsMps(line)[8], walkStepM / 90))
    }

    @Test
    fun lineShorterThanWindow_usesWholeLine() {
        val speeds = stepSpeedsMps(listOf(pt(0, 0.0), pt(15, 20.0), pt(30, 50.0)))
        assertEquals(2, speeds.size)
        assertTrue(speeds.all { isClose(it, 50.0 / 30.0) })
    }

    @Test
    fun twoPointLine_isDistanceOverTime() {
        val speeds = stepSpeedsMps(listOf(pt(0, 0.0), pt(15, 30.0)))
        assertEquals(1, speeds.size)
        assertTrue(isClose(speeds[0], 2.0))
    }

    @Test
    fun zeroDt_usesOneSecondFloor() {
        assertTrue(isClose(stepSpeedsMps(listOf(pt(0, 0.0), pt(0, 5.0)))[0], 5.0))
    }

    @Test
    fun shortInputs_giveNoSpeeds() {
        assertTrue(stepSpeedsMps(emptyList()).isEmpty())
        assertTrue(stepSpeedsMps(listOf(pt(0, 0.0))).isEmpty())
    }

    // ---- stepStrokes ----

    @Test
    fun normalSteps_mapToBands() {
        assertTrue(stepStrokes(steady(count = 10, mps = walkMps)).all { it == SpeedStroke.Band(SpeedBand.Walk) })
        assertTrue(stepStrokes(steady(count = 10, mps = 2 / 3.6)).all { it == SpeedStroke.Band(SpeedBand.Slow) })
    }

    @Test
    fun longStepAtRest_isStop() {
        assertEquals(listOf(stop), stepStrokes(listOf(pt(0, 0.0), pt(600, 5.0))))
    }

    @Test
    fun longStepWithDisplacement_isGap() {
        assertEquals(listOf(SpeedStroke.Gap), stepStrokes(listOf(pt(0, 0.0), pt(600, 700.0))))
    }

    @Test
    fun longOutageWhileMoving_isGapNotStop() {
        // An hour without fixes, ends 800 m apart: 0.8 km/h average, but it is movement, not a stop.
        val line = listOf(pt(0, 0.0), pt(3600, 800.0))
        assertEquals(listOf(SpeedStroke.Gap), stepStrokes(line))
        assertTrue(trackStops(listOf(line)).isEmpty())
    }

    @Test
    fun longStepStopRadius_followsAccuracy() {
        assertEquals(listOf(stop), stepStrokes(listOf(pt(0, 0.0), pt(600, 40.0))))
        assertEquals(listOf(SpeedStroke.Gap), stepStrokes(listOf(pt(0, 0.0), pt(600, 100.0))))
        assertEquals(listOf(stop), stepStrokes(listOf(pt(0, 0.0, acc = 60f), pt(600, 100.0, acc = 60f))))
        assertEquals(
            listOf(SpeedStroke.Gap),
            stepStrokes(listOf(pt(0, 0.0, acc = 400f), pt(600, 400.0, acc = 400f))),
        )
    }

    @Test
    fun longStepBoundary_isStrictlyAboveThreeMinutes() {
        assertEquals(
            listOf(SpeedStroke.Band(SpeedBand.Fast)),
            stepStrokes(listOf(ptMs(0, 0.0), ptMs(180_000, 700.0))),
        )
        assertEquals(listOf(SpeedStroke.Gap), stepStrokes(listOf(ptMs(0, 0.0), ptMs(180_001, 700.0))))
    }

    // ---- speedRuns ----

    @Test
    fun singleBand_isOneRunWithAllPoints() {
        val line = steady(count = 10, mps = walkMps)
        assertEquals(listOf(SpeedRun(SpeedStroke.Band(SpeedBand.Walk), line)), speedRuns(listOf(line)))
    }

    @Test
    fun bandChange_splitsRunsSharingBoundaryPoint() {
        // 7 walking points (0…90 s), then 12 running points from 105 s.
        val walk = steady(count = 7, mps = walkMps)
        val run = steady(count = 12, startSec = 105, startM = 7 * walkStepM, mps = 3 * walkMps)
        val line = walk + run
        val runs = speedRuns(listOf(line))

        assertTrue(runs.size >= 2)
        assertEquals(SpeedStroke.Band(SpeedBand.Walk), runs.first().stroke)
        assertEquals(SpeedStroke.Band(SpeedBand.Fast), runs.last().stroke)
        runs.zipWithNext().forEach { (lhs, rhs) ->
            assertTrue(lhs.stroke != rhs.stroke)
            assertEquals(lhs.points.last(), rhs.points.first())
        }
        assertEquals(line.first(), runs.first().points.first())
        assertEquals(line.last(), runs.last().points.last())
        assertTrue(runs.all { it.points.size >= 2 })
    }

    @Test
    fun gapRun_sitsBetweenSpeedRuns() {
        val left = steady(count = 7, mps = walkMps)
        val right = steady(count = 7, startSec = 90 + 600, startM = 6 * walkStepM + 700, mps = walkMps)
        val runs = speedRuns(listOf(left + right))

        val walk = SpeedStroke.Band(SpeedBand.Walk)
        assertEquals(listOf(walk, SpeedStroke.Gap, walk), runs.map { it.stroke })
        assertEquals(listOf(left.last(), right.first()), runs[1].points)
    }

    @Test
    fun linesNeverMerge_andOnePointLinesGiveNoRuns() {
        val a = steady(count = 5, mps = walkMps)
        val b = steady(count = 5, startSec = 1000, startM = 1000.0, mps = walkMps)
        val walk = SpeedStroke.Band(SpeedBand.Walk)
        assertEquals(
            listOf(SpeedRun(walk, a), SpeedRun(walk, b)),
            speedRuns(listOf(a, listOf(pt(500, 500.0)), b)),
        )
        assertTrue(speedRuns(emptyList()).isEmpty())
    }

    // ---- trackStops ----

    @Test
    fun restWithoutFixes_isOneStop() {
        // Walking, 10 minutes without fixes (phone at rest), then walking on from the same place.
        val left = steady(count = 7, mps = walkMps)
        val right = steady(count = 7, startSec = 90 + 600, startM = 6 * walkStepM + 5, mps = walkMps)
        val stops = trackStops(listOf(left + right))

        assertEquals(1, stops.size)
        val s = stops[0]
        assertEquals(90_000L, s.startMs)
        assertEquals(690_000L, s.endMs)
        assertEquals((left.last().lat + right.first().lat) / 2, s.lat, 1e-9)
        assertEquals(baseLon, s.lon, 1e-9)
    }

    @Test
    fun slowDrift_isStop() {
        val stops = trackStops(listOf(steady(count = 25, mps = 0.5 / 3.6)))
        assertEquals(1, stops.size)
        assertEquals(0L, stops[0].startMs)
        assertEquals(360_000L, stops[0].endMs)
    }

    @Test
    fun steadySlowWalk_isNotStop() {
        assertTrue(trackStops(listOf(steady(count = 41, mps = 1 / 3.6))).isEmpty())
    }

    @Test
    fun stop_needsFiveMinutes() {
        assertTrue(trackStops(listOf(listOf(pt(0, 0.0), pt(299, 2.0)))).isEmpty())
        assertEquals(1, trackStops(listOf(listOf(pt(0, 0.0), pt(300, 2.0)))).size)
        assertTrue(trackStops(listOf((0 until 17).map { i -> pt(i * 15L, 0.0) })).isEmpty()) // 4 min
    }

    @Test
    fun longStepWithDisplacement_isNotStop() {
        assertTrue(trackStops(listOf(listOf(pt(0, 0.0), pt(600, 700.0)))).isEmpty())
    }

    @Test
    fun walkRestWalkWithIrregularNoise_isOneStop() {
        // 5 min walking, 8 min standing with non-periodic 2D noise ±6 m, 5 min walking.
        val walk1 = steady(count = 21, mps = walkMps)
        val restM = 20 * walkStepM
        val rest = (1..32).map { i ->
            val (north, east) = jitter(i, 6.0)
            pt(300 + i * 15L, restM + north, eastM = east)
        }
        val walk2 = steady(count = 21, startSec = 300 + 33 * 15, startM = restM, mps = walkMps)
        val stops = trackStops(listOf(walk1 + rest + walk2))

        assertEquals(1, stops.size)
        val s = stops[0]
        val minutes = (s.endMs - s.startMs) / 60_000.0
        assertTrue(minutes in 6.0..8.5)
        assertTrue(s.startMs >= 240_000 && s.endMs <= 855_000)
    }

    @Test
    fun twoRests_giveTwoStops() {
        val rest1 = (0 until 33).map { i -> pt(i * 15L, 0.0) }
        val walk = steady(count = 13, startSec = 480, startM = 0.0, mps = walkMps).drop(1)
        val walkEndM = 12 * walkStepM
        val rest2 = (0 until 33).map { i -> pt(675 + i * 15L, walkEndM) }
        assertEquals(2, trackStops(listOf(rest1 + walk + rest2)).size)
    }

    @Test
    fun stops_neverCrossLines() {
        // 4 minutes in each line: 8 together, but lines are never joined.
        val a = (0 until 17).map { i -> pt(i * 15L, 0.0) }
        val b = (0 until 17).map { i -> pt(240 + i * 15L, 0.0, seg = "seg2") }
        assertTrue(trackStops(listOf(a, b)).isEmpty())
    }

    // ---- formatStopDuration ----

    @Test
    fun stopDurationFormat() {
        assertEquals("3 мин", formatStopDuration(180_000))
        assertEquals("3 мин", formatStopDuration(239_999))
        assertEquals("59 мин", formatStopDuration(59 * 60_000L))
        assertEquals("1 ч 00 мин", formatStopDuration(60 * 60_000L))
        assertEquals("1 ч 05 мин", formatStopDuration(65 * 60_000L))
        assertEquals("2 ч 05 мин", formatStopDuration(125 * 60_000L))
    }

    // ---- SpeedTrack ----

    @Test
    fun speedTrack_combinesRunsAndStops() {
        val lines = listOf(steady(count = 25, mps = 0.5 / 3.6))
        val track = SpeedTrack.of(lines)
        assertEquals(speedRuns(lines), track.runs)
        assertEquals(trackStops(lines), track.stops)
        assertEquals(1, track.stops.size)
    }
}
