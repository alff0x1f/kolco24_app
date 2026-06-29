package ru.kolco24.kolco24.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkMemberSnapshotListConverterTest {

    private val converter = MarkMemberSnapshotListConverter()

    @Test
    fun roundTripPreservesValuesAndOrder() {
        val values = listOf(
            MarkMemberSnapshot(numberInTeam = 2, nfcUid = "04AABBCC", number = 17, code = "deadbeef"),
            MarkMemberSnapshot(numberInTeam = 1, nfcUid = null, number = 3, code = null),
        )

        val restored = converter.fromJson(converter.toJson(values))

        assertEquals(values, restored)
    }

    @Test
    fun roundTripEmptyList() {
        val restored = converter.fromJson(converter.toJson(emptyList()))

        assertTrue(restored!!.isEmpty())
    }

    @Test
    fun emptyListSerializesToJsonArray() {
        assertEquals("[]", converter.toJson(emptyList()))
    }

    @Test
    fun nullRoundTripsToNull() {
        assertNull(converter.toJson(null))
        assertNull(converter.fromJson(null))
    }

    @Test
    fun fromJson_malformedInput_returnsNull() {
        assertNull(converter.fromJson("not-json"))
        assertNull(converter.fromJson("{\"key\":\"value\"}"))
    }

    @Test
    fun fromJson_unknownFieldsIgnored_forwardCompat() {
        // Validates that ignoreUnknownKeys = true is active: a future wire format adding new fields
        // must not break deserialization and silently lose the entire snapshot list.
        val json = """[{"numberInTeam":1,"nfcUid":null,"number":3,"code":null,"futureField":"x"}]"""
        val result = converter.fromJson(json)
        assertEquals(1, result!!.size)
        assertEquals(1, result[0].numberInTeam)
        assertEquals(3, result[0].number)
    }
}
