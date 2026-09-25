package ru.kolco24.kolco24.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun outOfRangeLatitudeBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,-91,38,55")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,55,38,90.5")).bounds)
    }

    @Test
    fun outOfRangeLongitudeBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "-180.1,55,38,56")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,55,181,56")).bounds)
    }

    @Test
    fun swappedSouthNorthBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37.5,55.9,37.8,55.6")).bounds)
    }

    @Test
    fun antimeridianCrossingBoundsIsNull() {
        // west > east — MapLibre's LatLngBounds.from would throw.
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "179,60,-179,61")).bounds)
    }

    @Test
    fun zeroExtentBoundsIsNull() {
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,55,37,56")).bounds)
        assertNull(parseMbtilesMetadata(mapOf("bounds" to "37,55,38,55")).bounds)
    }

    @Test
    fun worldBoundsAreAccepted() {
        assertEquals(
            Bounds(-180.0, -85.0511, 180.0, 85.0511),
            parseMbtilesMetadata(mapOf("bounds" to "-180,-85.0511,180,85.0511")).bounds,
        )
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

    // --- isMbtilesLoadable: whole-value plain-decimal rule guarding MapLibre 13.6.1's std::stoi / std::stod ---

    private fun loadable(meta: Map<String, String>, hasTiles: Boolean = true, min: String = "10", max: String = "16") =
        isMbtilesLoadable(meta, hasTiles, min, max)

    @Test
    fun loadableTypicalMap() {
        assertTrue(loadable(mapOf("format" to "png", "minzoom" to "10", "maxzoom" to "16", "bounds" to "1,2,3,4")))
    }

    @Test
    fun notLoadableWithoutTiles() {
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "16"), hasTiles = false))
    }

    @Test
    fun emptyMetadataFallsBackToTileZooms() {
        assertTrue(loadable(mapOf()))
        assertTrue(loadable(mapOf(), min = "", max = "")) // SQL NULL → "" → 0
        assertFalse(loadable(mapOf(), min = "abc", max = "16"))
    }

    @Test
    fun invalidMetadataZoomRejectedWhenBothPresent() {
        assertFalse(loadable(mapOf("minzoom" to "abc", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "x16")))
        assertFalse(loadable(mapOf("minzoom" to "99999999999", "maxzoom" to "16"))) // out_of_range
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "-")))
    }

    @Test
    fun zoomAcceptedOnlyAsWholePlainDecimalInRange() {
        assertTrue(loadable(mapOf("minzoom" to "", "maxzoom" to ""))) // empty → 0, no stoi
        assertTrue(loadable(mapOf("minzoom" to "0", "maxzoom" to "30")))
        assertTrue(loadable(mapOf("minzoom" to "  10\t", "maxzoom" to "\n16 "))) // C whitespace trimmed
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "31")))
        assertFalse(loadable(mapOf("minzoom" to "-1", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "+10", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "\t16abc"))) // trailing garbage
        assertFalse(loadable(mapOf("minzoom" to " 12abc", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "10.5", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "0x10", "maxzoom" to "16")))
        assertFalse(loadable(mapOf("minzoom" to "10", "maxzoom" to "2147483648")))
        assertFalse(loadable(mapOf("minzoom" to "\u00A010", "maxzoom" to "16"))) // NBSP: strtol doesn't skip it
    }

    @Test
    fun oneMissingZoomReplacesBothWithTileZooms() {
        // Native overwrites both from MIN/MAX(zoom_level) when either key is missing.
        assertTrue(loadable(mapOf("minzoom" to "garbage")))
        assertFalse(loadable(mapOf("maxzoom" to "16"), max = "bad"))
        assertFalse(loadable(mapOf(), min = "-1", max = "16"))
    }

    @Test
    fun scaleAcceptedOnlyAsWholePlainDecimalInRange() {
        assertTrue(loadable(mapOf("scale" to "1")))
        assertTrue(loadable(mapOf("scale" to " 2.5 ")))
        assertTrue(loadable(mapOf("scale" to ".5")))
        assertTrue(loadable(mapOf("scale" to "1.")))
        assertTrue(loadable(mapOf("scale" to "2e0")))
        assertTrue(loadable(mapOf("scale" to "1e6")))
        assertFalse(loadable(mapOf("scale" to "")))
        assertFalse(loadable(mapOf("scale" to "abc")))
        assertFalse(loadable(mapOf("scale" to "0")))
        assertFalse(loadable(mapOf("scale" to "-1")))
        assertFalse(loadable(mapOf("scale" to "1e7")))
        assertFalse(loadable(mapOf("scale" to "1e")))
        assertFalse(loadable(mapOf("scale" to " 2.5x")))
        assertFalse(loadable(mapOf("scale" to " 12abc")))
    }

    @Test
    fun scaleRejectsHexInfNanAndRangeErrors() {
        // Regression: a decimal-prefix match read these as "0", while strtod parses the whole hex float.
        assertFalse(loadable(mapOf("scale" to "0x1p1024"))) // overflow → out_of_range
        assertFalse(loadable(mapOf("scale" to "0x1p-2000"))) // underflow → out_of_range
        assertFalse(loadable(mapOf("scale" to "0x10")))
        assertFalse(loadable(mapOf("scale" to "0X1P0")))
        assertFalse(loadable(mapOf("scale" to "1e400")))
        assertFalse(loadable(mapOf("scale" to "1e-400")))
        assertFalse(loadable(mapOf("scale" to "0.0e-999")))
        assertFalse(loadable(mapOf("scale" to "1e-310"))) // subnormal
        assertFalse(loadable(mapOf("scale" to "inf")))
        assertFalse(loadable(mapOf("scale" to "Infinity")))
        assertFalse(loadable(mapOf("scale" to "nan")))
        assertFalse(loadable(mapOf("scale" to "1d"))) // Kotlin toDouble accepts, strtod stops at 'd'
    }

    @Test
    fun boundsAndJsonAreNotChecked() {
        assertTrue(loadable(mapOf("bounds" to "garbage", "json" to "{not json")))
    }

    // --- isNativeCompressedTile / COMPRESSED_TILE_PROBE_SQL: MapLibre 13.6.1 util::is_compressed ---

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun compressedPrefixesAreFlagged() {
        assertTrue(isNativeCompressedTile(bytes(0x1F, 0x8B, 0, 0, 0, 0, 0, 0, 0, 0))) // reviewer's repro
        assertTrue(isNativeCompressedTile(bytes(0x78, 0x01, 0)))
        assertTrue(isNativeCompressedTile(bytes(0x78, 0x5E, 0)))
        assertTrue(isNativeCompressedTile(bytes(0x78, 0x9C, 0)))
        assertTrue(isNativeCompressedTile(bytes(0x78, 0xDA, 0)))
    }

    @Test
    fun rasterAndShortPayloadsAreNotFlagged() {
        assertFalse(isNativeCompressedTile(bytes(0x89, 0x50, 0x4E, 0x47))) // PNG
        assertFalse(isNativeCompressedTile(bytes(0xFF, 0xD8, 0xFF))) // JPEG
        assertFalse(isNativeCompressedTile(bytes(0x52, 0x49, 0x46, 0x46))) // WebP (RIFF)
        assertFalse(isNativeCompressedTile(bytes(0x78, 0x00, 0))) // not a zlib header native knows
        assertFalse(isNativeCompressedTile(bytes(0x1F, 0x8B))) // native requires size > 2
        assertFalse(isNativeCompressedTile(ByteArray(0)))
    }

    @Test
    fun probeSqlCoversEveryPrefix() {
        assertEquals(
            "SELECT 1 FROM tiles WHERE length(CAST(tile_data AS BLOB)) > 2 AND " +
                "substr(CAST(tile_data AS BLOB), 1, 2) IN (X'1F8B', X'7801', X'785E', X'789C', X'78DA') LIMIT 1",
            COMPRESSED_TILE_PROBE_SQL,
        )
    }
}
