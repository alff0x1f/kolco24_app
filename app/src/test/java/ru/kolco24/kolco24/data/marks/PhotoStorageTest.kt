package ru.kolco24.kolco24.data.marks

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStorageTest {

    @Test
    fun scaledDimensionsLeavesSmallImageUnchanged() {
        assertEquals(800 to 600, scaledDimensions(800, 600, 1600))
        assertEquals(1600 to 1200, scaledDimensions(1600, 1200, 1600))
    }

    @Test
    fun scaledDimensionsShrinksLandscapeToCapOnLongestEdge() {
        // 4000x3000 → longest 4000 scaled to 1600 → 1600x1200.
        assertEquals(1600 to 1200, scaledDimensions(4000, 3000, 1600))
    }

    @Test
    fun scaledDimensionsShrinksPortraitToCapOnLongestEdge() {
        // 3000x4000 → longest 4000 scaled to 1600 → 1200x1600.
        assertEquals(1200 to 1600, scaledDimensions(3000, 4000, 1600))
    }

    @Test
    fun scaledDimensionsNeverYieldsZeroEdge() {
        val (w, h) = scaledDimensions(4000, 1, 1600)
        assertEquals(1600, w)
        assertEquals(1, h)
    }

    @Test
    fun scaledDimensionsHandlesNonPositiveInput() {
        assertEquals(0 to 0, scaledDimensions(0, 0, 1600))
        assertEquals(-1 to 10, scaledDimensions(-1, 10, 1600))
    }

    @Test
    fun orphanPhotoDirsReturnsDirsWithNoLiveMark() {
        val dirs = listOf("m1", "m2", "m3")
        val known = setOf("m2")

        assertEquals(listOf("m1", "m3"), orphanPhotoDirs(dirs, known))
    }

    @Test
    fun orphanPhotoDirsEmptyWhenAllKnown() {
        val dirs = listOf("m1", "m2")

        assertEquals(emptyList<String>(), orphanPhotoDirs(dirs, setOf("m1", "m2")))
    }

    @Test
    fun orphanPhotoDirsAllOrphanWhenNoneKnown() {
        val dirs = listOf("m1", "m2")

        assertEquals(dirs, orphanPhotoDirs(dirs, emptySet()))
    }
}
