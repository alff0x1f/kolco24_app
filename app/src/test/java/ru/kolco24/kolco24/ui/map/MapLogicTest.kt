package ru.kolco24.kolco24.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.map.Bounds
import ru.kolco24.kolco24.data.track.TrackPointLike
import java.util.TimeZone

class MapLogicTest {

    private fun mark(
        id: String = "m",
        checkpointId: Int = 1,
        number: Int = 31,
        cost: Int = 3,
        complete: Boolean = true,
        takenAt: Long = 1_000L,
        trustedTakenAt: Long? = null,
        lat: Double? = 55.0,
        lon: Double? = 37.0,
    ) = MarkEntity(
        id = id,
        raceId = 1,
        teamId = 1,
        checkpointId = checkpointId,
        checkpointNumber = number,
        cost = cost,
        method = "nfc",
        cpUid = "uid",
        cpCode = "code",
        present = listOf(1, 2),
        expectedCount = 2,
        complete = complete,
        takenAt = takenAt,
        updatedAt = takenAt,
        trustedTakenAt = trustedTakenAt,
        locLat = lat,
        locLon = lon,
    )

    private data class Pt(
        override val lat: Double,
        override val lon: Double,
        override val id: String = "p",
        override val accuracy: Float = 5f,
        override val elapsedRealtimeAt: Long = 0L,
        override val bootCount: Int? = null,
        override val wallMs: Long = 0L,
        override val trustedMs: Long? = null,
        override val segmentId: String = "seg",
    ) : TrackPointLike

    // ---- mapPins ----

    @Test
    fun markWithoutFixIsNotPinned() {
        val pins = mapPins(
            listOf(
                mark(id = "a", checkpointId = 1, lat = null, lon = null),
                mark(id = "b", checkpointId = 2, lat = 55.0, lon = null),
                mark(id = "c", checkpointId = 3, lat = null, lon = 37.0),
            ),
            emptyMap(),
        )
        assertTrue(pins.isEmpty())
    }

    @Test
    fun unconfirmedCloudTakeIsStillPinned() {
        // Deliberate (check-method plan): the map shows where the team WAS — an unconfirmed cloud/local
        // take is still a real visit with a GPS fix, so pins stay on `complete`, not `isCounted`.
        val unconfirmed = mark(checkpointId = 5).copy(checkMethod = "cloud", confirmedAt = null)
        assertEquals(listOf(5), mapPins(listOf(unconfirmed), emptyMap()).map { it.checkpointId })
    }

    @Test
    fun incompleteMarkIsNotPinned() {
        assertTrue(mapPins(listOf(mark(complete = false)), emptyMap()).isEmpty())
    }

    @Test
    fun duplicateCheckpointYieldsOnePinFromEarliestTake() {
        val pins = mapPins(
            listOf(
                mark(id = "late", checkpointId = 7, takenAt = 5_000L, lat = 1.0, lon = 1.0),
                mark(id = "early", checkpointId = 7, takenAt = 9_000L, trustedTakenAt = 2_000L, lat = 2.0, lon = 2.0),
                mark(id = "partial", checkpointId = 7, takenAt = 100L, complete = false, lat = 3.0, lon = 3.0),
            ),
            emptyMap(),
        )
        assertEquals(1, pins.size)
        assertEquals(2.0, pins[0].lat, 0.0)
        assertEquals(2_000L, pins[0].timeMs)
    }

    @Test
    fun costPrefersLegendThenFallsBackToMark() {
        val pins = mapPins(
            listOf(
                mark(id = "a", checkpointId = 1, cost = 3, takenAt = 1L),
                mark(id = "b", checkpointId = 2, cost = 5, takenAt = 2L),
            ),
            mapOf(1 to 9),
        )
        assertEquals(9, pins.first { it.checkpointId == 1 }.cost)
        assertEquals(5, pins.first { it.checkpointId == 2 }.cost)
    }

    @Test
    fun timeIsTrustedThenWall() {
        val pins = mapPins(
            listOf(
                mark(id = "a", checkpointId = 1, takenAt = 1_000L, trustedTakenAt = 1_500L),
                mark(id = "b", checkpointId = 2, takenAt = 3_000L, trustedTakenAt = null),
            ),
            emptyMap(),
        )
        assertEquals(1_500L, pins.first { it.checkpointId == 1 }.timeMs)
        assertEquals(3_000L, pins.first { it.checkpointId == 2 }.timeMs)
    }

    @Test
    fun pinsAreSortedByTakeTime() {
        val pins = mapPins(
            listOf(
                mark(id = "a", checkpointId = 1, number = 31, takenAt = 5_000L),
                mark(id = "b", checkpointId = 2, number = 32, takenAt = 2_000L, trustedTakenAt = 9_000L),
                mark(id = "c", checkpointId = 3, number = 33, takenAt = 1_000L),
            ),
            emptyMap(),
        )
        assertEquals(listOf(33, 31, 32), pins.map { it.number })
    }

    @Test
    fun pinCarriesNumberAndCoordinates() {
        val pin = mapPins(listOf(mark(checkpointId = 4, number = 42, lat = 55.5, lon = 37.5)), emptyMap()).single()
        assertEquals(MapPin(4, 42, 3, 1_000L, 55.5, 37.5), pin)
    }

    // ---- GeoJSON ----

    private fun geometryOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.single().jsonObject["geometry"]!!.jsonObject

    @Test
    fun trackGeoJsonTwoLinesIsOneMultiLineStringWithTwoPartsInLonLat() {
        val json = Json.parseToJsonElement(
            trackGeoJson(
                listOf(
                    listOf(Pt(55.0, 37.0), Pt(55.1, 37.1)),
                    listOf(Pt(56.0, 38.0), Pt(56.1, 38.1), Pt(56.2, 38.2)),
                ),
            ),
        ).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val features = json["features"]!!.jsonArray
        assertEquals(1, features.size)
        val geometry = features[0].jsonObject["geometry"]!!.jsonObject
        assertEquals("MultiLineString", geometry["type"]!!.jsonPrimitive.content)
        val parts = geometry["coordinates"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals(2, parts[0].jsonArray.size)
        assertEquals(3, parts[1].jsonArray.size)
        val first = parts[0].jsonArray
        assertEquals(37.0, first[0].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(55.0, first[0].jsonArray[1].jsonPrimitive.double, 0.0)
        assertEquals(37.1, first[1].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(55.1, first[1].jsonArray[1].jsonPrimitive.double, 0.0)
        assertEquals(38.2, parts[1].jsonArray[2].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(56.2, parts[1].jsonArray[2].jsonArray[1].jsonPrimitive.double, 0.0)
    }

    @Test
    fun trackGeoJsonOneLineIsOnePart() {
        val geometry = geometryOf(trackGeoJson(listOf(listOf(Pt(55.0, 37.0), Pt(55.1, 37.1)))))
        assertEquals("MultiLineString", geometry["type"]!!.jsonPrimitive.content)
        assertEquals(1, geometry["coordinates"]!!.jsonArray.size)
    }

    @Test
    fun trackGeoJsonSinglePointLinesBecomeOneDotMultiPointAfterTheLines() {
        val features = Json.parseToJsonElement(
            trackGeoJson(
                listOf(
                    listOf(Pt(54.0, 36.0)),
                    listOf(Pt(55.0, 37.0), Pt(55.1, 37.1)),
                    listOf(Pt(57.0, 39.0)),
                ),
            ),
        ).jsonObject["features"]!!.jsonArray
        assertEquals(2, features.size)
        val lines = features[0].jsonObject
        assertTrue(lines["properties"]!!.jsonObject.isEmpty())
        val lineGeometry = lines["geometry"]!!.jsonObject
        assertEquals("MultiLineString", lineGeometry["type"]!!.jsonPrimitive.content)
        val parts = lineGeometry["coordinates"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals(37.0, parts[0].jsonArray[0].jsonArray[0].jsonPrimitive.double, 0.0)
        val dots = features[1].jsonObject
        assertTrue(dots["properties"]!!.jsonObject[TRACK_DOT_PROPERTY]!!.jsonPrimitive.boolean)
        val dotGeometry = dots["geometry"]!!.jsonObject
        assertEquals("MultiPoint", dotGeometry["type"]!!.jsonPrimitive.content)
        val points = dotGeometry["coordinates"]!!.jsonArray
        assertEquals(2, points.size)
        assertEquals(36.0, points[0].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(54.0, points[0].jsonArray[1].jsonPrimitive.double, 0.0)
        assertEquals(39.0, points[1].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(57.0, points[1].jsonArray[1].jsonPrimitive.double, 0.0)
    }

    @Test
    fun trackGeoJsonOnlySinglePointLinesIsOnlyTheDotFeature() {
        val features = Json.parseToJsonElement(
            trackGeoJson(listOf(emptyList(), listOf(Pt(55.0, 37.0)))),
        ).jsonObject["features"]!!.jsonArray
        val dots = features.single().jsonObject
        assertTrue(dots["properties"]!!.jsonObject[TRACK_DOT_PROPERTY]!!.jsonPrimitive.boolean)
        val geometry = dots["geometry"]!!.jsonObject
        assertEquals("MultiPoint", geometry["type"]!!.jsonPrimitive.content)
        assertEquals(1, geometry["coordinates"]!!.jsonArray.size)
    }

    @Test
    fun trackGeoJsonWithoutPointsIsEmptyCollection() {
        for (lines in listOf(emptyList(), listOf(emptyList<Pt>()))) {
            val json = Json.parseToJsonElement(trackGeoJson(lines)).jsonObject
            assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
            assertTrue(json["features"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun pinsGeoJsonHasPointFeaturesWithIdAndIcon() {
        val pins = listOf(MapPin(4, 42, 3, 0L, 55.5, 37.5), MapPin(5, 7, 1, 0L, 56.0, 38.0))
        val features = Json.parseToJsonElement(pinsGeoJson(pins)).jsonObject["features"]!!.jsonArray
        assertEquals(2, features.size)
        val f = features[0].jsonObject
        assertEquals("Feature", f["type"]!!.jsonPrimitive.content)
        val props = f["properties"]!!.jsonObject
        assertEquals(4, props["id"]!!.jsonPrimitive.int)
        assertEquals("kp-42", props["icon"]!!.jsonPrimitive.content)
        val geometry = f["geometry"]!!.jsonObject
        assertEquals("Point", geometry["type"]!!.jsonPrimitive.content)
        val coords = geometry["coordinates"]!!.jsonArray
        assertEquals(37.5, coords[0].jsonPrimitive.double, 0.0)
        assertEquals(55.5, coords[1].jsonPrimitive.double, 0.0)
        assertEquals("kp-7", features[1].jsonObject["properties"]!!.jsonObject["icon"]!!.jsonPrimitive.content)
    }

    @Test
    fun pinsGeoJsonEmptyIsEmptyCollection() {
        val json = Json.parseToJsonElement(pinsGeoJson(emptyList())).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        assertTrue(json["features"]!!.jsonArray.isEmpty())
    }

    // ---- pinCaption ----

    // 2026-09-25 11:07:00 UTC
    private val t = 1_790_334_420_000L

    @Test
    fun pinCaptionPluralizesAndUsesGivenTimeZone() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("КП 32 · 1 балл · 11:07", pinCaption(MapPin(1, 32, 1, t, 0.0, 0.0), utc))
        assertEquals("КП 32 · 2 балла · 11:07", pinCaption(MapPin(1, 32, 2, t, 0.0, 0.0), utc))
        assertEquals("КП 32 · 5 баллов · 11:07", pinCaption(MapPin(1, 32, 5, t, 0.0, 0.0), utc))
        assertEquals(
            "КП 32 · 4 балла · 14:07",
            pinCaption(MapPin(1, 32, 4, t, 0.0, 0.0), TimeZone.getTimeZone("Europe/Moscow")),
        )
    }

    // ---- formatMapSize ----

    @Test
    fun formatMapSizeKbMbBoundaries() {
        assertEquals("0 КБ", formatMapSize(0))
        assertEquals("1 КБ", formatMapSize(1))
        assertEquals("1 КБ", formatMapSize(1024))
        assertEquals("820 КБ", formatMapSize(820L * 1024))
        assertEquals("1023 КБ", formatMapSize(1023L * 1024))
        // would round to «1024 КБ» → switches to МБ
        assertEquals("1 МБ", formatMapSize(1024L * 1024 - 100))
        assertEquals("1 МБ", formatMapSize(1024L * 1024))
        assertEquals("34 МБ", formatMapSize(34L * 1024 * 1024))
        assertEquals("35 МБ", formatMapSize(34L * 1024 * 1024 + 600L * 1024))
    }

    // ---- dataBounds ----

    @Test
    fun dataBoundsCoversTrackAndPins() {
        val track = listOf(Pt(55.0, 37.0), Pt(55.2, 36.8))
        val pins = listOf(MapPin(1, 31, 3, 0L, lat = 54.9, lon = 37.3))
        assertEquals(Bounds(west = 36.8, south = 54.9, east = 37.3, north = 55.2), dataBounds(track, pins))
    }

    @Test
    fun dataBoundsEmptyIsNull() {
        assertNull(dataBounds(emptyList(), emptyList()))
    }

    @Test
    fun dataBoundsSinglePointIsZeroExtent() {
        assertEquals(Bounds(37.0, 55.0, 37.0, 55.0), dataBounds(emptyList(), listOf(MapPin(1, 31, 3, 0L, 55.0, 37.0))))
        assertEquals(Bounds(37.0, 55.0, 37.0, 55.0), dataBounds(listOf(Pt(55.0, 37.0)), emptyList()))
    }

    // ---- cameraFrame ----

    @Test
    fun cameraFrameFileBoundsWinOverData() {
        val file = Bounds(37.0, 55.0, 38.0, 56.0)
        assertEquals(CameraFrame.FileBounds(file), cameraFrame(file, Bounds(1.0, 2.0, 3.0, 4.0)))
        assertEquals(CameraFrame.FileBounds(file), cameraFrame(file, null))
    }

    @Test
    fun cameraFrameSinglePointZeroExtent() {
        assertEquals(CameraFrame.SinglePoint(lat = 55.0, lon = 37.0), cameraFrame(null, Bounds(37.0, 55.0, 37.0, 55.0)))
    }

    @Test
    fun cameraFrameFitsDataOrFallsBackToNoData() {
        val data = Bounds(37.0, 55.0, 37.5, 55.0)
        assertEquals(CameraFrame.FitData(data), cameraFrame(null, data))
        assertEquals(CameraFrame.NoData, cameraFrame(null, null))
    }

    // ---- styleJson ----

    private fun baseSource(source: MapStyleSource) =
        Json.parseToJsonElement(styleJson(source)).jsonObject["sources"]!!.jsonObject["base"]!!.jsonObject

    @Test
    fun styleJsonOfflineUsesMbtilesUrlWithAbsolutePath() {
        val json = Json.parseToJsonElement(styleJson(MapStyleSource.Offline("/data/maps/8.mbtiles", null))).jsonObject
        assertEquals(8, json["version"]!!.jsonPrimitive.int)
        val base = baseSource(MapStyleSource.Offline("/data/maps/8.mbtiles", null))
        assertEquals("raster", base["type"]!!.jsonPrimitive.content)
        assertEquals("mbtiles:///data/maps/8.mbtiles", base["url"]!!.jsonPrimitive.content)
        assertEquals(256, base["tileSize"]!!.jsonPrimitive.int)
        assertNull(base["tiles"])
        val layer = json["layers"]!!.jsonArray.single().jsonObject
        assertEquals("raster", layer["type"]!!.jsonPrimitive.content)
        assertEquals("base", layer["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun styleJsonOnlineUsesOsmTilesWithAttribution() {
        val base = baseSource(MapStyleSource.Online)
        assertEquals(
            listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
            base["tiles"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(19, base["maxzoom"]!!.jsonPrimitive.int)
        assertEquals("© OpenStreetMap contributors", base["attribution"]!!.jsonPrimitive.content)
        assertEquals(256, base["tileSize"]!!.jsonPrimitive.int)
        assertNull(base["url"])
    }
}
