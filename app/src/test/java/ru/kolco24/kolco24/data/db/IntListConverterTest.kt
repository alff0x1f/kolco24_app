package ru.kolco24.kolco24.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntListConverterTest {

    private val converter = IntListConverter()

    @Test
    fun roundTripPreservesValuesAndOrder() {
        val values = listOf(3, 1, 2)

        val restored = converter.fromJson(converter.toJson(values))

        assertEquals(values, restored)
    }

    @Test
    fun roundTripEmptyList() {
        val restored = converter.fromJson(converter.toJson(emptyList()))

        assertTrue(restored.isEmpty())
    }

    @Test
    fun roundTripPreservesDuplicates() {
        val values = listOf(1, 1, 2, 2, 2)

        val restored = converter.fromJson(converter.toJson(values))

        assertEquals(values, restored)
    }

    @Test
    fun fromJson_malformedInput_returnsEmptyList() {
        assertTrue(converter.fromJson("not-json").isEmpty())
        assertTrue(converter.fromJson("{\"key\":\"value\"}").isEmpty())
    }
}
