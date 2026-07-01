package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.PhotoFrameRow
import ru.kolco24.kolco24.data.marks.encodePhotoPaths

/** Unit tests for [foldPhotoFrameCounts] — the mark-granular-tick / frame-granular-denominator fold. */
class PhotoFrameCountsTest {

    private fun row(count: Int, local: Boolean, cloud: Boolean) = PhotoFrameRow(
        photoPath = encodePhotoPaths((1..count).map { "marks/m/$it.jpg" }),
        photosUploadedLocal = local,
        photosUploadedCloud = cloud,
    )

    @Test
    fun `empty list yields zero counts`() {
        val counts = foldPhotoFrameCounts(emptyList())
        assertEquals(0, counts.total)
        assertEquals(0, counts.local)
        assertEquals(0, counts.cloud)
    }

    @Test
    fun `row with an empty encoded list contributes no frames`() {
        val counts = foldPhotoFrameCounts(listOf(row(count = 0, local = true, cloud = true)))
        assertEquals(0, counts.total)
        assertEquals(0, counts.local)
        assertEquals(0, counts.cloud)
    }

    @Test
    fun `single mark with both flags set counts all frames on both targets`() {
        val counts = foldPhotoFrameCounts(listOf(row(count = 3, local = true, cloud = true)))
        assertEquals(3, counts.total)
        assertEquals(3, counts.local)
        assertEquals(3, counts.cloud)
    }

    @Test
    fun `mid-drain mark contributes to total but not to either numerator`() {
        val counts = foldPhotoFrameCounts(listOf(row(count = 4, local = false, cloud = false)))
        assertEquals(4, counts.total)
        assertEquals(0, counts.local)
        assertEquals(0, counts.cloud)
    }

    @Test
    fun `mixed rows sum asymmetrically per target`() {
        val rows = listOf(
            row(count = 2, local = true, cloud = false),
            row(count = 5, local = false, cloud = true),
        )
        val counts = foldPhotoFrameCounts(rows)
        assertEquals(7, counts.total)
        assertEquals(2, counts.local)
        assertEquals(5, counts.cloud)
    }
}
