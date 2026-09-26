package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLinesTest {

    /** Test fake; [tag] is a field only this type has, to prove [trackLines] keeps the element type. */
    private data class Pt(
        override val id: String,
        override val lat: Double,
        override val lon: Double,
        override val accuracy: Float,
        override val wallMs: Long,
        override val segmentId: String = "seg",
        val tag: String = "",
        override val elapsedRealtimeAt: Long = 0L,
        override val bootCount: Int? = null,
        override val trustedMs: Long? = null,
    ) : TrackPointLike

    private val baseLat = 55.0
    private val baseLon = 37.0

    /** Meters of latitude per degree for the 6_371_000 m sphere — points along one meridian. */
    private val mPerDeg = 6_371_000.0 * Math.PI / 180.0

    /** A point [northM] meters north of the base, [tSec] seconds in. */
    private fun pt(
        id: String,
        tSec: Long,
        northM: Double,
        acc: Float = 10f,
        seg: String = "seg",
    ) = Pt(
        id = id,
        lat = baseLat + northM / mPerDeg,
        lon = baseLon,
        accuracy = acc,
        wallMs = tSec * 1000L,
        segmentId = seg,
    )

    /** A point [eastM] meters east of the base (along the base parallel), [tSec] seconds in. */
    private fun ptEast(id: String, tSec: Long, eastM: Double, acc: Float = 10f) = Pt(
        id = id,
        lat = baseLat,
        lon = baseLon + eastM / (mPerDeg * Math.cos(Math.toRadians(baseLat))),
        accuracy = acc,
        wallMs = tSec * 1000L,
    )

    /** A walking chain: [n] points every 15 s, 20 m apart, starting at [startSec]/[startM]. */
    private fun walk(prefix: String, n: Int, startSec: Long, startM: Double, acc: Float = 10f) =
        (0 until n).map { i -> pt("$prefix$i", startSec + 15L * i, startM + 20.0 * i, acc) }

    private fun ids(lines: List<List<Pt>>) = lines.map { line -> line.map { it.id } }

    // ---- haversineMeters ----

    @Test
    fun haversine_oneDegreeOfLatitude() {
        assertEquals(mPerDeg, haversineMeters(55.0, 37.0, 56.0, 37.0), 0.01)
    }

    @Test
    fun haversine_oneDegreeOfLongitudeAtEquator() {
        assertEquals(mPerDeg, haversineMeters(0.0, 10.0, 0.0, 11.0), 0.01)
    }

    @Test
    fun haversine_oneDegreeOfLongitudeAtMidLatitude() {
        // Along the 55° parallel the great circle is ~0.2 m shorter than the parallel arc — within 1 m.
        assertEquals(mPerDeg * Math.cos(Math.toRadians(55.0)), haversineMeters(55.0, 37.0, 55.0, 38.0), 1.0)
    }

    @Test
    fun haversine_symmetricAcrossDifferentLatitudes() {
        val there = haversineMeters(55.0, 37.0, 56.5, 39.0)
        assertEquals(there, haversineMeters(56.5, 39.0, 55.0, 37.0), 1e-6)
        // Moscow → Saint Petersburg (≈ 634 km great-circle on the 6371 km sphere).
        assertEquals(634_000.0, haversineMeters(55.7558, 37.6173, 59.9343, 30.3351), 3_000.0)
    }

    // ---- isReachable ----

    @Test
    fun isReachable_speedBoundaryAround14mps() {
        val a = pt("a", 0, 0.0, acc = 0f)
        // 10 s: 139.9 m → 13.99 m/s reachable, 140.1 m → 14.01 m/s not.
        assertTrue(isReachable(a, pt("b", 10, 139.9, acc = 0f)))
        assertFalse(isReachable(a, pt("b", 10, 140.1, acc = 0f)))
    }

    @Test
    fun isReachable_subtractsTheBetterAccuracyAsNoiseAllowance() {
        // 250 m in 15 s: raw 16.7 m/s; minus min(acc) = 60 → 12.7 m/s reachable.
        assertTrue(isReachable(pt("a", 0, 0.0, acc = 60f), pt("b", 15, 250.0, acc = 90f)))
        // Same step with the better accuracy 10 → (250 − 10) / 15 = 16 m/s unreachable (min, not max).
        assertFalse(isReachable(pt("a", 0, 0.0, acc = 10f), pt("b", 15, 250.0, acc = 90f)))
    }

    @Test
    fun isReachable_usesTrustedTimeOverWallTime() {
        // 1000 m: by trusted time 100 s apart → 9.9 m/s reachable; by wall time 15 s → 66 m/s.
        val a = pt("a", 0, 0.0).copy(trustedMs = 0L)
        val b = pt("b", 15, 1000.0).copy(trustedMs = 100_000L)
        assertTrue(isReachable(a, b))
        assertFalse(isReachable(a.copy(trustedMs = null), b.copy(trustedMs = null)))
    }

    @Test
    fun haversine_samePointIsZero() {
        assertEquals(0.0, haversineMeters(55.75, 37.62, 55.75, 37.62), 0.0)
    }

    // ---- trackLines, filter on ----

    @Test
    fun spikeBetweenShortChains_bypassReachable_droppedIntoOneLine() {
        val a = pt("A", 0, 0.0)
        val b = pt("B", 15, 20.0)
        val x = pt("X", 30, 1000.0)
        val c = pt("C", 45, 60.0)
        val d = pt("D", 60, 80.0)
        assertEquals(listOf(listOf("A", "B", "C", "D")), ids(trackLines(listOf(a, b, x, c, d), filter = true)))
    }

    @Test
    fun shortTailAfterShortChain_keptAsOwnLine() {
        // A→B: (200 − 5) / 15 = 13 m/s reachable; B→C: (300 − 10) / 15 ≈ 19.3 m/s unreachable.
        val a = pt("A", 0, 0.0, acc = 5f)
        val b = pt("B", 15, 200.0, acc = 20f)
        val c = pt("C", 30, 500.0, acc = 10f)
        assertEquals(listOf(listOf("A", "B"), listOf("C")), ids(trackLines(listOf(a, b, c), filter = true)))
    }

    @Test
    fun realisticCoarseSpikeBetweenLongChains_dropped() {
        val first = walk("a", 5, 0, 0.0)
        val spike = pt("S", 75, 80.0 + 600.0, acc = 450f)
        val second = walk("b", 5, 90, 120.0)
        val lines = trackLines(first + spike + second, filter = true)
        assertEquals(listOf((first + second).map { it.id }), ids(lines))
    }

    @Test
    fun multipathSpikeWithGoodAccuracy_dropped() {
        val first = walk("a", 5, 0, 0.0, acc = 15f)
        val spike = pt("S", 75, 80.0 + 300.0, acc = 8f)
        val second = walk("b", 5, 90, 120.0, acc = 15f)
        val lines = trackLines(first + spike + second, filter = true)
        assertEquals(listOf((first + second).map { it.id }), ids(lines))
    }

    @Test
    fun slowNoisyJitter_keptAsOneLine() {
        val offsets = listOf(0.0, 80.0, -60.0, 90.0, -80.0, 70.0, 0.0, -90.0)
        val accs = listOf(60f, 100f, 70f, 90f, 80f, 100f, 60f, 75f)
        val points = offsets.indices.map { i -> pt("j$i", 15L * i, offsets[i] + 5.0 * i, acc = accs[i]) }
        assertEquals(listOf(points.map { it.id }), ids(trackLines(points, filter = true)))
    }

    @Test
    fun noisyJitterNeedingNoiseAllowance_keptAsOneLine() {
        // Every step 250 m in 15 s (raw 16.7 m/s) is reachable only thanks to acc ≥ 60 m.
        val points = (0 until 6).map { i -> pt("j$i", 15L * i, if (i % 2 == 0) 0.0 else 250.0, acc = 60f + i) }
        assertEquals(listOf(points.map { it.id }), ids(trackLines(points, filter = true)))
    }

    @Test
    fun eastWestStep_measuredWithLongitudeCosine_oneLine() {
        // 200 m east in 15 s: (200 − 10) / 15 = 12.7 m/s reachable (≈ 349 m without the cos(55°) factor).
        val points = listOf(ptEast("a", 0, 0.0), ptEast("b", 15, 200.0))
        assertEquals(listOf(listOf("a", "b")), ids(trackLines(points, filter = true)))
    }

    @Test
    fun eastWestSpikeBetweenLongChains_dropped() {
        val first = (0 until 5).map { i -> ptEast("a$i", 15L * i, 20.0 * i) }
        val spike = ptEast("S", 75, 80.0 + 800.0)
        val second = (0 until 5).map { i -> ptEast("b$i", 90 + 15L * i, 120.0 + 20.0 * i) }
        assertEquals(
            listOf((first + second).map { it.id }),
            ids(trackLines(first + spike + second, filter = true)),
        )
    }

    @Test
    fun twoMutuallyUnreachableSpikesInARow_bothKeptEachOwnLine() {
        // Documented accepted miss: neither bypass (L→Y, X→R) is reachable, so both spikes stay.
        val l = walk("l", 5, 0, 0.0)
        val x = pt("X", 75, 3000.0)
        val y = pt("Y", 90, -3000.0)
        val r = walk("r", 5, 105, 120.0)
        assertEquals(
            listOf(l.map { it.id }, listOf("X"), listOf("Y"), r.map { it.id }),
            ids(trackLines(l + x + y + r, filter = true)),
        )
    }

    @Test
    fun liveTail_keptUntilNextFixMakesItABypassableInteriorChain() {
        val gps = walk("g", 5, 0, 0.0) // ends at 80 m, 60 s
        val jump = pt("X", 75, 2000.0)
        assertEquals(listOf(gps.map { it.id }, listOf("X")), ids(trackLines(gps + jump, filter = true)))
        val next = pt("g5", 90, 100.0)
        assertEquals(listOf((gps + next).map { it.id }), ids(trackLines(gps + jump + next, filter = true)))
    }

    @Test
    fun headNetworkClusterBeforeLongChain_dropped() {
        val cluster = listOf(
            pt("n0", 0, 3000.0, acc = 300f),
            pt("n1", 15, 3050.0, acc = 320f),
            pt("n2", 30, 2980.0, acc = 280f),
        )
        val gps = walk("g", 6, 45, 0.0)
        assertEquals(listOf(gps.map { it.id }), ids(trackLines(cluster + gps, filter = true)))
    }

    @Test
    fun shortHeadBeforeShortChain_kept() {
        val head = listOf(pt("h0", 0, 0.0), pt("h1", 15, 20.0))
        val next = listOf(pt("s0", 30, 2000.0), pt("s1", 45, 2020.0))
        assertEquals(
            listOf(listOf("h0", "h1"), listOf("s0", "s1")),
            ids(trackLines(head + next, filter = true)),
        )
    }

    @Test
    fun trailingNetworkFixesAfterLongChain_dropped() {
        val gps = walk("g", 5, 0, 0.0, acc = 10f)
        val tail = listOf(pt("n0", 75, 2000.0, acc = 300f), pt("n1", 90, 2050.0, acc = 310f))
        assertEquals(listOf(gps.map { it.id }), ids(trackLines(gps + tail, filter = true)))
    }

    @Test
    fun trailingGpsQualityShortChainAfterBreak_keptAsOwnLine() {
        val gps = walk("g", 5, 0, 0.0, acc = 10f)
        val tail = listOf(pt("t0", 75, 1080.0, acc = 12f), pt("t1", 90, 1100.0, acc = 12f))
        assertEquals(
            listOf(gps.map { it.id }, listOf("t0", "t1")),
            ids(trackLines(gps + tail, filter = true)),
        )
    }

    /** A long chain (5 points, 60 s, 20 m steps) with the given accuracies; median of (5,5,10,50,50) = 10. */
    private fun longWithAccs(accs: List<Float> = listOf(5f, 50f, 10f, 50f, 5f)) =
        accs.mapIndexed { i, acc -> pt("g$i", 15L * i, 20.0 * i, acc = acc) }

    private fun farTail(vararg accs: Float) =
        accs.mapIndexed { i, acc -> pt("t$i", 75 + 15L * i, 2000.0 + 20.0 * i, acc = acc) }

    @Test
    fun tail_exactlyThreeTimesWorseMedian_dropped() {
        // Tail median 30 = 3 × 10 (the long chain's median; its mean is 24, its max 50).
        val gps = longWithAccs()
        assertEquals(listOf(gps.map { it.id }), ids(trackLines(gps + farTail(30f, 30f), filter = true)))
    }

    @Test
    fun tail_justUnderThreeTimesWorseMedian_kept() {
        // 29 < 3 × 10 → kept (it would be dropped against the long chain's minimum, 5).
        val gps = longWithAccs()
        val tail = farTail(29f, 29f)
        assertEquals(listOf(gps.map { it.id }, tail.map { it.id }), ids(trackLines(gps + tail, filter = true)))
    }

    @Test
    fun tail_evenSizeMedianIsMeanOfTheMiddles() {
        val gps = longWithAccs()
        // (29, 31) → 30 → dropped (the lower middle 29 alone would keep it).
        assertEquals(listOf(gps.map { it.id }), ids(trackLines(gps + farTail(29f, 31f), filter = true)))
        // (20, 38) → 29 → kept (the upper middle 38 alone would drop it).
        val tail = farTail(20f, 38f)
        assertEquals(listOf(gps.map { it.id }, tail.map { it.id }), ids(trackLines(gps + tail, filter = true)))
    }

    @Test
    fun tail_afterShortChain_keptEvenWhenMuchWorse() {
        // L, then S (interior, bypass L→T unreachable → kept), then a 30× worse short tail T: the tail
        // rule only fires right after a kept long chain.
        val l = walk("l", 5, 0, 0.0, acc = 10f)
        val s = listOf(pt("s0", 75, 2000.0, acc = 10f), pt("s1", 90, 2020.0, acc = 10f))
        val t = listOf(pt("t0", 105, 6000.0, acc = 300f), pt("t1", 120, 6020.0, acc = 300f))
        assertEquals(
            listOf(l.map { it.id }, s.map { it.id }, t.map { it.id }),
            ids(trackLines(l + s + t, filter = true)),
        )
    }

    @Test
    fun tail_referenceMedianZeroIsFlooredAtOneMeter() {
        val gps = longWithAccs(List(5) { 0f })
        // 2 < 3 × max(0, 1) → kept; 3 ≥ 3 → dropped.
        val kept = farTail(2f, 2f)
        assertEquals(listOf(gps.map { it.id }, kept.map { it.id }), ids(trackLines(gps + kept, filter = true)))
        assertEquals(listOf(gps.map { it.id }), ids(trackLines(gps + farTail(3f, 3f), filter = true)))
    }

    @Test
    fun interiorShortChainWithUnreachableBypass_keptAsOwnLine() {
        val first = walk("a", 5, 0, 0.0)
        val mid = listOf(pt("m0", 75, 1000.0), pt("m1", 90, 1020.0))
        val second = walk("b", 5, 105, 5000.0)
        assertEquals(
            listOf(first.map { it.id }, listOf("m0", "m1"), second.map { it.id }),
            ids(trackLines(first + mid + second, filter = true)),
        )
    }

    @Test
    fun twoLongChainsWithUnreachableStep_twoLines() {
        val first = walk("a", 5, 0, 0.0)
        val second = walk("b", 5, 75, 3000.0)
        assertEquals(
            listOf(first.map { it.id }, second.map { it.id }),
            ids(trackLines(first + second, filter = true)),
        )
    }

    @Test
    fun segmentOfOnlyShortChains_noPointsRemoved() {
        val points = listOf(
            pt("p0", 0, 0.0),
            pt("p1", 15, 2000.0),
            pt("p2", 30, 4000.0),
            pt("p3", 45, 6000.0),
        )
        val lines = trackLines(points, filter = true)
        assertEquals(points.map { it.id }, lines.flatten().map { it.id })
        assertEquals(4, lines.size)
    }

    @Test
    fun differentSegmentIds_neverMerged() {
        val points = listOf(
            pt("a0", 0, 0.0, seg = "a"),
            pt("a1", 15, 20.0, seg = "a"),
            pt("b0", 30, 40.0, seg = "b"),
            pt("b1", 45, 60.0, seg = "b"),
        )
        assertEquals(
            listOf(listOf("a0", "a1"), listOf("b0", "b1")),
            ids(trackLines(points, filter = true)),
        )
    }

    @Test
    fun hardCap_above500Dropped_exactly500Kept() {
        val points = listOf(
            pt("at", 0, 0.0, acc = 500f),
            pt("over", 15, 10.0, acc = 501f),
            pt("fine", 30, 20.0, acc = 10f),
        )
        assertEquals(listOf(listOf("at", "fine")), ids(trackLines(points, filter = true)))
    }

    @Test
    fun hardCap_allOverCap_empty() {
        val points = listOf(pt("a", 0, 0.0, acc = 600f), pt("b", 15, 20.0, acc = 900f))
        assertTrue(trackLines(points, filter = true).isEmpty())
    }

    @Test
    fun hardCap_removesSpikesThatWouldOtherwiseSplitTheTrack() {
        // Without the cap the two mutually unreachable spikes would both stay (see the two-spikes test).
        val l = walk("l", 5, 0, 0.0)
        val x = pt("X", 75, 3000.0, acc = 501f)
        val y = pt("Y", 90, -3000.0, acc = 600f)
        val r = walk("r", 5, 105, 120.0)
        assertEquals(listOf((l + r).map { it.id }), ids(trackLines(l + x + y + r, filter = true)))
    }

    @Test
    fun filterOn_eachSegmentRunFilteredOnItsOwn() {
        val a = walk("a", 3, 0, 0.0).map { it.copy(segmentId = "a") }
        // Run b: a short network head far away, then a long GPS chain → the head is dropped.
        val bHead = listOf(pt("bn0", 60, 5000.0, acc = 300f, seg = "b"), pt("bn1", 75, 5050.0, acc = 300f, seg = "b"))
        val bGps = walk("b", 5, 90, 100.0).map { it.copy(segmentId = "b") }
        // Run a again (non-consecutive): its own line, never merged into the first run a.
        val a2 = walk("c", 2, 180, 200.0).map { it.copy(segmentId = "a") }
        assertEquals(
            listOf(a.map { it.id }, bGps.map { it.id }, a2.map { it.id }),
            ids(trackLines(a + bHead + bGps + a2, filter = true)),
        )
    }

    @Test
    fun zeroDt_usesOneSecondFloor() {
        // (10 − 5) / 1 s = 5 m/s → reachable.
        val near = listOf(pt("a", 0, 0.0, acc = 5f), pt("b", 0, 10.0, acc = 5f))
        assertEquals(listOf(listOf("a", "b")), ids(trackLines(near, filter = true)))
        // (30 − 5) / 1 s = 25 m/s → unreachable; both short → both kept, separate lines.
        val far = listOf(pt("a", 0, 0.0, acc = 5f), pt("b", 0, 30.0, acc = 5f))
        assertEquals(listOf(listOf("a"), listOf("b")), ids(trackLines(far, filter = true)))
    }

    @Test
    fun shortChainBoundaries() {
        assertTrue(isShortChain(listOf(pt("a", 0, 0.0))))
        assertTrue(isShortChain(listOf(pt("a", 0, 0.0), pt("b", 30, 0.0), pt("c", 59, 0.0))))
        assertFalse(isShortChain(listOf(pt("a", 0, 0.0), pt("b", 30, 0.0), pt("c", 60, 0.0))))
        assertFalse(isShortChain(walk("w", 4, 0, 0.0))) // 4 points in 45 s
    }

    @Test
    fun emptyInput_emptyList() {
        assertTrue(trackLines(emptyList<Pt>(), filter = true).isEmpty())
        assertTrue(trackLines(emptyList<Pt>(), filter = false).isEmpty())
    }

    @Test
    fun genericTypePreserved() {
        val points = listOf(pt("a", 0, 0.0).copy(tag = "x"), pt("b", 15, 20.0).copy(tag = "y"))
        val lines: List<List<Pt>> = trackLines(points, filter = true)
        assertEquals(listOf("x", "y"), lines.flatten().map { it.tag })
    }

    // ---- trackLines, filter off («Все точки») ----

    @Test
    fun filterOff_onlySplitsByConsecutiveSegmentId() {
        val points = listOf(
            pt("a0", 0, 0.0, seg = "a"),
            pt("spike", 15, 5000.0, acc = 900f, seg = "a"),
            pt("a1", 30, 40.0, seg = "a"),
            pt("b0", 45, 60.0, seg = "b"),
            pt("a2", 60, 80.0, seg = "a"),
        )
        assertEquals(
            listOf(listOf("a0", "spike", "a1"), listOf("b0"), listOf("a2")),
            ids(trackLines(points, filter = false)),
        )
    }
}
