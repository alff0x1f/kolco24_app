package ru.kolco24.kolco24.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MbtilesMetadataTest {

    @Test
    fun fullValidSet() {
        val meta = parseMbtilesMetadata(
            mapOf(
                "name" to "race",
                "format" to "png",
                "bounds" to "37.5,55.6,37.8,55.9",
                "minzoom" to "10",
                "maxzoom" to "16",
            ),
        )

        assertEquals(
            MbtilesMetadata(Bounds(37.5, 55.6, 37.8, 55.9), minZoom = 10, maxZoom = 16),
            meta,
        )
    }

    @Test
    fun zoomsOnly() {
        val meta = parseMbtilesMetadata(mapOf("minzoom" to "12", "maxzoom" to "15"))

        assertEquals(MbtilesMetadata(bounds = null, minZoom = 12, maxZoom = 15), meta)
    }

    @Test
    fun boundsWithThreeComponentsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "1,2,3")).bounds)
    }

    @Test
    fun boundsWithFiveComponentsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "1,2,3,4,5")).bounds)
    }

    @Test
    fun nonNumericBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "a,2,3,4")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "1,,3,4")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "")).bounds)
    }

    @Test
    fun nonFiniteBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "NaN,2,3,4")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "1,Infinity,3,4")).bounds)
    }

    @Test
    fun commaDecimalBoundsIsNull() {
        // Five parts after split — the spec is dot-decimal only.
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,5,55.6,37.8,55.9")).bounds)
    }

    @Test
    fun nonNumericZoomIsNullIndependently() {
        val meta = parseMbtilesMetadata(
            mapOf("bounds" to "1,2,3,4", "minzoom" to "ten", "maxzoom" to "16.5"),
        )

        assertEquals(Bounds(1.0, 2.0, 3.0, 4.0), meta.bounds)
        assertNull(meta.minZoom)
        assertNull(meta.maxZoom)
    }

    @Test
    fun emptyMap() {
        assertEquals(MbtilesMetadata(null, null, null), parseMbtilesMetadata(emptyMap<String, String>()))
    }

    @Test
    fun whitespaceAroundNumbersIsTrimmed() {
        val meta = parseMbtilesMetadata(
            mapOf(
                "bounds" to " -0.5 , 51.2 ,\t0.3, 51.7 ",
                "minzoom" to " 8 ",
                "maxzoom" to "14\n",
            ),
        )

        assertEquals(
            MbtilesMetadata(Bounds(-0.5, 51.2, 0.3, 51.7), minZoom = 8, maxZoom = 14),
            meta,
        )
    }
}
