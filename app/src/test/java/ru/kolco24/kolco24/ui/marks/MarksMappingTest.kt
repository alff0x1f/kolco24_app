package ru.kolco24.kolco24.ui.marks

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.marks.encodePhotoPaths
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore
import ru.kolco24.kolco24.ui.legend.CheckpointColor

class MarksMappingTest {

    private fun mark(
        id: String,
        point: Int,
        number: Int,
        cost: Int,
        method: String = "nfc",
        complete: Boolean = true,
        takenAt: Long = 1_000L,
        trustedTakenAt: Long? = null,
        photoPath: String? = null,
    ) = MarkEntity(
        id = id,
        raceId = 1,
        teamId = 7,
        checkpointId = point,
        checkpointNumber = number,
        cost = cost,
        method = method,
        cpUid = "UID",
        cpCode = "CODE",
        present = emptyList(),
        expectedCount = 0,
        complete = complete,
        takenAt = takenAt,
        updatedAt = takenAt,
        trustedTakenAt = trustedTakenAt,
        photoPath = photoPath,
    )

    private fun hhmm(epoch: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(epoch))

    @Test
    fun `tile per event reverses to oldest-first so new takes append to the end`() {
        // Input is newest-first (as observeMarks delivers); tiles come back oldest-first.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, takenAt = 3_000L), // newest
            mark("b", point = 2, number = 4, cost = 3, takenAt = 2_000L),
            mark("c", point = 3, number = 7, cost = 2, takenAt = 1_000L), // oldest
        )
        val tiles = marksToTiles(marks)
        assertEquals(3, tiles.size)
        assertEquals(listOf("07", "04", "01"), tiles.map { it.number })
    }

    @Test
    fun `number is zero-padded to two digits`() {
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 5, cost = 1)))
        assertEquals("05", tiles.single().number)
    }

    @Test
    fun `method maps to kind`() {
        val tiles = marksToTiles(
            listOf(
                mark("a", point = 1, number = 1, cost = 1, method = "nfc", takenAt = 2_000L),
                mark("b", point = 2, number = 2, cost = 1, method = "photo", takenAt = 1_000L),
            ),
        )
        // Oldest-first: the photo take (1_000L) comes first, the nfc take (2_000L) last.
        assertEquals(MarkKind.PHOTO, tiles[0].kind)
        assertEquals(MarkKind.NFC, tiles[1].kind)
    }

    @Test
    fun `time formats takenAt as HH colon mm`() {
        val epoch = 5_000_000L
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = epoch)))
        assertEquals(hhmm(epoch), tiles.single().time)
    }

    @Test
    fun `time prefers trustedTakenAt when present`() {
        // Trusted and wall differ by minutes (a phone clock reset): the tile must show trusted.
        val wall = 5_000_000L
        val trusted = wall + 7 * 60_000L // 7 minutes apart so HH:mm differs
        val tiles = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = wall, trustedTakenAt = trusted)),
        )
        assertEquals(hhmm(trusted), tiles.single().time)
        assertTrue(hhmm(trusted) != hhmm(wall))
    }

    @Test
    fun `time falls back to takenAt when trustedTakenAt is null`() {
        val wall = 5_000_000L
        val tiles = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = wall, trustedTakenAt = null)),
        )
        assertEquals(hhmm(wall), tiles.single().time)
    }

    @Test
    fun `dateTime formats the effective take time as date and time`() {
        val epoch = 5_000_000L
        val expected = SimpleDateFormat("dd.MM.yyyy '·' HH:mm", Locale.US).format(Date(epoch))
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = epoch)))
        assertEquals(expected, tiles.single().dateTime)
    }

    @Test
    fun `dateTime prefers trustedTakenAt like time does`() {
        val wall = 5_000_000L
        val trusted = wall + 7 * 60_000L
        val expected = SimpleDateFormat("dd.MM.yyyy '·' HH:mm", Locale.US).format(Date(trusted))
        val tiles = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 1, takenAt = wall, trustedTakenAt = trusted)),
        )
        assertEquals(expected, tiles.single().dateTime)
    }

    @Test
    fun `empty marks yield no tiles`() {
        assertTrue(marksToTiles(emptyList()).isEmpty())
    }

    @Test
    fun `incomplete takes are not tiled`() {
        // КП scanned without the whole team: an empty (0 members) and a partial take both stay
        // complete=false and must not appear as tiles.
        val marks = listOf(
            mark("empty", point = 1, number = 1, cost = 2, complete = false, takenAt = 3_000L),
            mark("partial", point = 2, number = 4, cost = 3, complete = false, takenAt = 2_000L),
        )
        assertTrue(marksToTiles(marks).isEmpty())
    }

    @Test
    fun `only completed takes are tiled in oldest-first order`() {
        // Newest event is incomplete and filtered out; the rest are tiled oldest-first.
        val marks = listOf(
            mark("incomplete", point = 1, number = 1, cost = 2, complete = false, takenAt = 4_000L),
            mark("done-new", point = 2, number = 4, cost = 3, complete = true, takenAt = 3_000L),
            mark("done-old", point = 3, number = 7, cost = 5, complete = true, takenAt = 2_000L),
        )
        val tiles = marksToTiles(marks)
        assertEquals(listOf("07", "04"), tiles.map { it.number })
    }

    @Test
    fun `colorOf resolves the per-take checkpoint color`() {
        val tiles = marksToTiles(
            listOf(
                mark("a", point = 1, number = 1, cost = 1, takenAt = 2_000L),
                mark("b", point = 2, number = 2, cost = 1, takenAt = 1_000L),
            ),
        ) { if (it.checkpointId == 1) CheckpointColor.BLUE else null }
        // Oldest-first: point 2 (null) first, point 1 (BLUE) last.
        assertEquals(null, tiles[0].color)
        assertEquals(CheckpointColor.BLUE, tiles[1].color)
    }

    @Test
    fun `color defaults to null without a resolver`() {
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1)))
        assertEquals(null, tiles.single().color)
    }

    @Test
    fun `taken count is distinct complete points and repeat does not double score`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, complete = true),
            mark("b", point = 1, number = 1, cost = 2, complete = true), // repeat same point
            mark("c", point = 2, number = 4, cost = 3, complete = true),
            mark("d", point = 3, number = 7, cost = 5, complete = false), // partial, not scored
        )
        assertEquals(2, takenPointCount(marks))
        assertEquals(5, totalScore(marks)) // 2 + 3, repeat of point 1 not double-counted
    }

    @Test
    fun `takenPointCount with a live cost resolver excludes technical checkpoints`() {
        // Point 1 is a technical checkpoint (cost 0, e.g. test point / transfer zone) — not scoring.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 0, complete = true),
            mark("b", point = 2, number = 4, cost = 3, complete = true),
        )
        assertEquals(1, takenPointCount(marks) { it.cost })
    }

    @Test
    fun `takenPointCount live resolver does not double-count a repeated point`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, complete = true),
            mark("b", point = 1, number = 1, cost = 2, complete = true), // repeat same point
            mark("c", point = 2, number = 4, cost = 3, complete = true),
        )
        assertEquals(2, takenPointCount(marks) { it.cost })
    }

    @Test
    fun `takenPointCount live resolver ignores incomplete takes`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, complete = false),
            mark("b", point = 2, number = 4, cost = 3, complete = true),
        )
        assertEquals(1, takenPointCount(marks) { it.cost })
    }

    @Test
    fun `totalScore with a live cost resolver scores off current cost not the snapshot`() {
        // Point 1 was taken when its cost was 0 (stale snapshot); the legend now says 5.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 0, complete = true),
            mark("b", point = 2, number = 4, cost = 3, complete = true),
        )
        val liveCost = mapOf(1 to 5, 2 to 3)
        // Snapshot path under-counts (0 + 3); the live resolver matches the legend (5 + 3).
        assertEquals(3, totalScore(marks))
        assertEquals(8, totalScore(marks) { liveCost[it.checkpointId] ?: it.cost })
    }

    @Test
    fun `totalScore live resolver falls back to the snapshot for a point absent from the legend`() {
        val marks = listOf(mark("a", point = 9, number = 1, cost = 4, complete = true))
        // Point 9 dropped from the legend → resolver misses → snapshot (4) is used.
        assertEquals(4, totalScore(marks) { emptyMap<Int, Int>()[it.checkpointId] ?: it.cost })
    }

    @Test
    fun `marksToTiles costOf resolves the live tile cost`() {
        val tiles = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 0, complete = true)),
            costOf = { mapOf(1 to 5)[it.checkpointId] ?: it.cost },
        )
        assertEquals(5, tiles.single().cost)
    }

    @Test
    fun `marksToTiles cost defaults to the snapshot without a resolver`() {
        val tiles = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 7)))
        assertEquals(7, tiles.single().cost)
    }

    @Test
    fun `photoPaths and photoCount map from the encoded photoPath column`() {
        val paths = listOf("marks/a/1.jpg", "marks/a/2.jpg", "marks/a/3.jpg")
        val tiles = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 1, photoPath = encodePhotoPaths(paths))),
        )
        val tile = tiles.single()
        // Order is preserved and the badge count is the derived list size.
        assertEquals(paths, tile.photoPaths)
        assertEquals(3, tile.photoCount)
    }

    @Test
    fun `a tile without photos has empty paths and zero count`() {
        val tile = marksToTiles(listOf(mark("a", point = 1, number = 1, cost = 1))).single()
        assertTrue(tile.photoPaths.isEmpty())
        assertEquals(0, tile.photoCount)
    }

    @Test
    fun `an nfc take can carry photo evidence so the badge shows on a colored tile`() {
        // NFC tile keeps its kind, but photoCount > 0 drives the «📷×N» badge independently.
        val tile = marksToTiles(
            listOf(
                mark(
                    "a", point = 1, number = 1, cost = 2, method = "nfc",
                    photoPath = encodePhotoPaths(listOf("marks/a/1.jpg")),
                ),
            ),
        ).single()
        assertEquals(MarkKind.NFC, tile.kind)
        assertEquals(1, tile.photoCount)
    }

    @Test
    fun `a photo mark with empty present and complete is tiled`() {
        // A standalone photo take (no NFC roster scanned) is present=[] complete=true; it must still tile.
        val marks = listOf(
            mark(
                "p", point = 1, number = 3, cost = 4, method = "photo", complete = true,
                photoPath = encodePhotoPaths(listOf("marks/p/1.jpg")),
            ),
        )
        val tiles = marksToTiles(marks)
        assertEquals(1, tiles.size)
        assertEquals(MarkKind.PHOTO, tiles.single().kind)
        assertEquals(1, tiles.single().photoCount)
    }

    @Test
    fun `a corrupted photoPath column degrades to no photos`() {
        // photoPaths(...) never throws — garbage decodes to emptyList, so the tile simply shows no badge.
        val tile = marksToTiles(
            listOf(mark("a", point = 1, number = 1, cost = 1, photoPath = "{not json")),
        ).single()
        assertTrue(tile.photoPaths.isEmpty())
        assertEquals(0, tile.photoCount)
    }

    @Test
    fun `lightboxPhotos flattens every take's frames in grid order carrying the owning mark`() {
        // Tiles arrive oldest-first (as marksToTiles delivers); the strip preserves that order and
        // concatenates each take's own frame list.
        val a = Mark(number = "01", cost = 2, kind = MarkKind.PHOTO, time = "10:00",
            photoPaths = listOf("marks/a/1.jpg", "marks/a/2.jpg"))
        val b = Mark(number = "04", cost = 3, kind = MarkKind.NFC, time = "10:05",
            photoPaths = listOf("marks/b/1.jpg"))
        val strip = lightboxPhotos(listOf(a, b))
        assertEquals(
            listOf("marks/a/1.jpg", "marks/a/2.jpg", "marks/b/1.jpg"),
            strip.map { it.path },
        )
        // Each frame carries its owning take so the per-page КП chip resolves correctly.
        assertEquals(listOf(a, a, b), strip.map { it.mark })
    }

    @Test
    fun `lightboxPhotos skips takes without photos`() {
        val withPhoto = Mark(number = "01", cost = 2, kind = MarkKind.PHOTO, time = "10:00",
            photoPaths = listOf("marks/a/1.jpg"))
        val noPhoto = Mark(number = "04", cost = 3, kind = MarkKind.NFC, time = "10:05")
        val strip = lightboxPhotos(listOf(noPhoto, withPhoto, noPhoto))
        assertEquals(listOf("marks/a/1.jpg"), strip.map { it.path })
    }

    @Test
    fun `lightboxPhotos of no tiles is empty`() {
        assertTrue(lightboxPhotos(emptyList()).isEmpty())
    }

    @Test
    fun `photoReviewSummary is null when there are no photo takes`() {
        assertEquals(null, photoReviewSummary(emptyList()))
        assertEquals(
            null,
            photoReviewSummary(listOf(mark("a", point = 1, number = 1, cost = 2, method = "nfc"))),
        )
    }

    @Test
    fun `photoReviewSummary counts complete photo takes and sums their points`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, method = "photo"),
            mark("b", point = 2, number = 2, cost = 3, method = "photo"),
            mark("c", point = 3, number = 3, cost = 5, method = "nfc"),
        )
        assertEquals(
            PhotoReviewSummary(count = 2, points = 5, tokens = listOf("3-02", "2-01")),
            photoReviewSummary(marks),
        )
    }

    @Test
    fun `photoReviewSummary counts a repeat photo take of the same КП once`() {
        // Checkpoint-level, mirroring the metrics' distinctBy(checkpointId): two photo takes of one КП
        // are one checkpoint awaiting review, and its points count once.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, method = "photo", takenAt = 2_000L),
            mark("b", point = 1, number = 1, cost = 2, method = "photo", takenAt = 1_000L),
        )
        assertEquals(
            PhotoReviewSummary(count = 1, points = 2, tokens = listOf("2-01")),
            photoReviewSummary(marks),
        )
    }

    @Test
    fun `photoReviewSummary excludes a КП that also has a complete NFC take`() {
        // The chip already proves the visit (the score comes from the NFC take), so a separate photo
        // take of the same КП needs no judge review.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, method = "photo", takenAt = 2_000L),
            mark("b", point = 1, number = 1, cost = 2, method = "nfc", takenAt = 1_000L),
            mark("c", point = 2, number = 2, cost = 3, method = "photo", takenAt = 3_000L),
        )
        assertEquals(
            PhotoReviewSummary(count = 1, points = 3, tokens = listOf("3-02")),
            photoReviewSummary(marks),
        )
    }

    @Test
    fun `photoReviewSummary ignores an incomplete NFC take when excluding chip-verified КП`() {
        // An incomplete NFC take never scored — the photo take is still the checkpoint's only proof.
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, method = "photo", takenAt = 2_000L),
            mark("b", point = 1, number = 1, cost = 2, method = "nfc", complete = false, takenAt = 1_000L),
        )
        assertEquals(
            PhotoReviewSummary(count = 1, points = 2, tokens = listOf("2-01")),
            photoReviewSummary(marks),
        )
    }

    @Test
    fun `photoReviewSummary skips incomplete photo takes`() {
        val marks = listOf(
            mark("a", point = 1, number = 1, cost = 2, method = "photo"),
            mark("b", point = 2, number = 2, cost = 3, method = "photo", complete = false),
        )
        assertEquals(
            PhotoReviewSummary(count = 1, points = 2, tokens = listOf("2-01")),
            photoReviewSummary(marks),
        )
    }

    @Test
    fun `photoReviewSummary ignores an NFC take that merely attached photo evidence`() {
        // The chip was read — judges have nothing to verify, so it never joins the review notice.
        val marks = listOf(
            mark(
                "a", point = 1, number = 1, cost = 2, method = "nfc",
                photoPath = encodePhotoPaths(listOf("marks/a/1.jpg")),
            ),
        )
        assertEquals(null, photoReviewSummary(marks))
    }

    @Test
    fun `photoReviewSummary scores through the live costOf not the snapshot`() {
        // A photo take of a still-locked КП snapshots cost=0; the live legend cost wins on reveal.
        val marks = listOf(mark("a", point = 7, number = 1, cost = 0, method = "photo"))
        val live = mapOf(7 to 30)
        val summary = photoReviewSummary(marks) { live[it.checkpointId] ?: it.cost }
        assertEquals(PhotoReviewSummary(count = 1, points = 30, tokens = listOf("30-01")), summary)
    }

    @Test
    fun `photoReviewSummary tokens follow grid order and drop the cost prefix on a zero-cost КП`() {
        // Input is newest-first (as observeMarks delivers); tokens come back oldest-first, matching the
        // tile grid. A zero-cost КП (still-locked in the legend) is a bare zero-padded number, mirroring
        // the tile token.
        val marks = listOf(
            mark("a", point = 3, number = 4, cost = 5, method = "photo", takenAt = 3_000L), // newest
            mark("b", point = 2, number = 3, cost = 0, method = "photo", takenAt = 2_000L),
            mark("c", point = 1, number = 2, cost = 1, method = "photo", takenAt = 1_000L), // oldest
        )
        assertEquals(listOf("1-02", "03", "5-04"), photoReviewSummary(marks)?.tokens)
    }

    @Test
    fun `hiddenTakenTokens masks distinct complete takes of locked checkpoints oldest-first`() {
        // Input is newest-first; tokens come back oldest-first («?-NN» — the ? where the cost digit
        // would sit). A repeat take of the same locked КП counts once; open КП never show.
        val marks = listOf(
            mark("a", point = 3, number = 7, cost = 0, method = "photo", takenAt = 4_000L), // newest
            mark("b", point = 2, number = 5, cost = 3, method = "nfc", takenAt = 3_000L),   // open
            mark("c", point = 3, number = 7, cost = 0, method = "photo", takenAt = 2_000L), // repeat
            mark("d", point = 1, number = 4, cost = 0, method = "photo", takenAt = 1_000L), // oldest
        )
        assertEquals(listOf("?-04", "?-07"), hiddenTakenTokens(marks, lockedIds = setOf(1, 3)))
    }

    @Test
    fun `hiddenTakenTokens skips incomplete takes and is empty when nothing locked is taken`() {
        val marks = listOf(
            mark("a", point = 1, number = 4, cost = 0, method = "photo", complete = false),
            mark("b", point = 2, number = 5, cost = 3, method = "nfc"),
        )
        assertTrue(hiddenTakenTokens(marks, lockedIds = setOf(1)).isEmpty())
        assertTrue(hiddenTakenTokens(emptyList(), lockedIds = setOf(1)).isEmpty())
        assertTrue(hiddenTakenTokens(marks, lockedIds = emptySet()).isEmpty())
    }

    @Test
    fun `tokensLabel joins up to three tokens and collapses a longer tail to an ellipsis`() {
        assertEquals("1-02", tokensLabel(listOf("1-02")))
        assertEquals("1-02, 2-03, 5-04", tokensLabel(listOf("1-02", "2-03", "5-04")))
        assertEquals(
            "1-02, 2-03, 5-04, …",
            tokensLabel(listOf("1-02", "2-03", "5-04", "3-05", "4-06")),
        )
    }
}
