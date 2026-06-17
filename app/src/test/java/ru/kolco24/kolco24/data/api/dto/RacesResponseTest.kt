package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RacesResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesApiDocExample() {
        // The example payload from docs/API.md, GET /app/races/.
        val payload = """
            {
              "races": [
                {
                  "id": 8,
                  "name": "Кольцо24 2026",
                  "slug": "kolco24-2026",
                  "date": "2026-06-20",
                  "date_end": "2026-06-21",
                  "place": "Сосновый бор",
                  "reg_status": "open"
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<RacesResponse>(payload)

        assertEquals(1, response.races.size)
        val race = response.races[0]
        assertEquals(8, race.id)
        assertEquals("Кольцо24 2026", race.name)
        assertEquals("kolco24-2026", race.slug)
        assertEquals("2026-06-20", race.date)
        assertEquals("2026-06-21", race.dateEnd)
        assertEquals("Сосновый бор", race.place)
        assertEquals("open", race.regStatus)
    }

    @Test
    fun parsesWithoutLegendVisibleField() {
        // The new contract dropped is_legend_visible from /app/races/ entirely.
        val payload = """
            {
              "races": [
                {
                  "id": 8,
                  "name": "Кольцо24 2026",
                  "slug": "kolco24-2026",
                  "date": "2026-06-20",
                  "date_end": "2026-06-21",
                  "place": "Сосновый бор",
                  "reg_status": "open"
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<RacesResponse>(payload)

        assertEquals(8, response.races[0].id)
    }

    @Test
    fun nullDateEndParsesToNull() {
        val payload = """
            {
              "races": [
                {
                  "id": 1,
                  "name": "One-day race",
                  "slug": "one-day",
                  "date": "2026-07-01",
                  "date_end": null,
                  "place": "Лес",
                  "reg_status": "upcoming",
                  "is_legend_visible": false
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<RacesResponse>(payload)

        assertNull(response.races[0].dateEnd)
    }

    @Test
    fun unknownFieldDoesNotBreakParsing() {
        val payload = """
            {
              "races": [
                {
                  "id": 8,
                  "name": "Кольцо24 2026",
                  "slug": "kolco24-2026",
                  "date": "2026-06-20",
                  "date_end": "2026-06-21",
                  "place": "Сосновый бор",
                  "reg_status": "open",
                  "is_legend_visible": true,
                  "future_server_field": "ignored"
                }
              ],
              "extra_top_level": 42
            }
        """.trimIndent()

        val response = json.decodeFromString<RacesResponse>(payload)

        assertEquals(8, response.races[0].id)
    }

    @Test
    fun missingRequiredFieldThrows() {
        // "name" is omitted — it has no default, so deserialization must fail.
        val payload = """
            {
              "races": [
                {
                  "id": 8,
                  "slug": "kolco24-2026",
                  "date": "2026-06-20",
                  "place": "Сосновый бор",
                  "reg_status": "open",
                  "is_legend_visible": true
                }
              ]
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RacesResponse>(payload)
        }
    }
}
