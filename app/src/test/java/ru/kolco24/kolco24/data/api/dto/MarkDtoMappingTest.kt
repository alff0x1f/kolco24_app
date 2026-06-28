package ru.kolco24.kolco24.data.api.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.db.MarkMemberSnapshot

class MarkDtoMappingTest {

    private fun mark(
        present: List<Int> = emptyList(),
        presentDetails: List<MarkMemberSnapshot>? = null,
        locLat: Double? = null,
    ) = MarkEntity(
        id = "mark-1",
        raceId = 7,
        teamId = 42,
        checkpointId = 264,
        checkpointNumber = 12,
        cost = 5,
        method = "nfc",
        cpUid = "04A2B3C4D5E680",
        cpCode = "9f1a2b3c4d5e6f70",
        present = present,
        presentDetails = presentDetails,
        expectedCount = 4,
        complete = true,
        takenAt = 1_718_900_000_000L,
        updatedAt = 1_718_900_000_500L,
        trustedTakenAt = 1_718_900_000_123L,
        elapsedRealtimeAt = 9_876_543L,
        bootCount = 7,
        locLat = locLat,
        locLon = if (locLat != null) 37.61 else null,
        locAccuracy = if (locLat != null) 12.4f else null,
        locAltitude = if (locLat != null) 184.2 else null,
        locVerticalAccuracy = if (locLat != null) 3.2f else null,
        locGpsTimeMs = if (locLat != null) 1_718_900_000_001L else null,
        locElapsedRealtimeAt = if (locLat != null) 9_870_000L else null,
    )

    @Test
    fun fullSnapshot_buildsPresentWithUidAndNumber() {
        val dto = mark(
            present = listOf(1, 2),
            presentDetails = listOf(
                MarkMemberSnapshot(numberInTeam = 1, nfcUid = "04F1E2", number = 101, code = "c3d4"),
                MarkMemberSnapshot(numberInTeam = 2, nfcUid = "041122", number = 102, code = null),
            ),
        ).toDto()

        assertEquals(2, dto.present.size)
        val m1 = dto.present.single { it.numberInTeam == 1 }
        assertEquals("04F1E2", m1.nfcUid)
        assertEquals("c3d4", m1.code)
        assertEquals(101, m1.number)
        val m2 = dto.present.single { it.numberInTeam == 2 }
        assertEquals("041122", m2.nfcUid)
        assertNull(m2.code)
        assertEquals(102, m2.number)
    }

    @Test
    fun nullPresentDetails_mergesAllPresentAsSentinels() {
        val dto = mark(present = listOf(1, 2), presentDetails = null).toDto()

        assertEquals(2, dto.present.size)
        dto.present.forEach {
            assertNull(it.nfcUid)
            assertNull(it.code)
            assertEquals(0, it.number)
        }
        assertEquals(listOf(1, 2), dto.present.map { it.numberInTeam })
    }

    @Test
    fun partialDetails_enrichesMatchedSlotsSentinelsTheRest() {
        val dto = mark(
            present = listOf(1, 2, 3),
            presentDetails = listOf(
                MarkMemberSnapshot(numberInTeam = 2, nfcUid = "0422", number = 202, code = null),
            ),
        ).toDto()

        assertEquals(3, dto.present.size)
        val m2 = dto.present.single { it.numberInTeam == 2 }
        assertEquals("0422", m2.nfcUid)
        assertEquals(202, m2.number)
        // 1 and 3 are sentinels (no snapshot)
        listOf(1, 3).forEach { num ->
            val s = dto.present.single { it.numberInTeam == num }
            assertNull(s.nfcUid)
            assertEquals(0, s.number)
        }
    }

    @Test
    fun location_nonNull_mapsAllSevenFields() {
        val dto = mark(present = listOf(1), locLat = 55.75).toDto()
        val loc = dto.location!!
        assertEquals(55.75, loc.lat, 0.0)
        assertEquals(37.61, loc.lon, 0.0)
        assertEquals(12.4f, loc.accuracy!!, 0f)
        assertEquals(184.2, loc.altitude!!, 0.0)
        assertEquals(3.2f, loc.verticalAccuracy!!, 0f)
        assertEquals(1_718_900_000_001L, loc.gpsTimeMs)
        assertEquals(9_870_000L, loc.elapsedAt)
    }

    @Test
    fun location_nullWhenNoFix() {
        val dto = mark(present = listOf(1), locLat = null).toDto()
        assertNull(dto.location)
    }

    @Test
    fun nullableTimes_passThrough() {
        val entity = mark(present = listOf(1)).copy(
            trustedTakenAt = null,
            elapsedRealtimeAt = null,
            bootCount = null,
        )
        val dto = entity.toDto()
        assertNull(dto.trustedMs)
        assertNull(dto.elapsedAt)
        assertNull(dto.bootCount)
        // wall_ms always present (the only fallback when trusted_ms is null)
        assertEquals(1_718_900_000_000L, dto.wallMs)
    }

    @Test
    fun renamedFields_comeFromCorrectEntityColumns() {
        val dto = mark(present = listOf(1)).toDto()
        assertEquals("9f1a2b3c4d5e6f70", dto.cpCode)
        assertEquals("04A2B3C4D5E680", dto.cpNfcUid)
        assertEquals(1_718_900_000_000L, dto.wallMs)
        assertEquals(1_718_900_000_123L, dto.trustedMs)
        assertEquals(9_876_543L, dto.elapsedAt)
        assertEquals(264, dto.checkpointId)
        assertEquals("nfc", dto.method)
        assertEquals(4, dto.expectedCount)
        assertEquals(true, dto.complete)
    }

    @Test
    fun emptyPresent_producesEmptyArray() {
        val dto = mark(present = emptyList()).toDto()
        assertEquals(0, dto.present.size)
    }
}
