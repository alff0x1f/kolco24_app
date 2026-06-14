package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegendResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRealBackendResponse() {
        val payload = """
            {
              "race": 8,
              "checkpoints": [
                {
                  "id": 101,
                  "number": 5,
                  "cost": 10,
                  "type": "kp",
                  "description": "У пня"
                },
                {
                  "id": 102,
                  "number": 12,
                  "cost": 20,
                  "type": "start",
                  "description": "Старт"
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<LegendResponse>(payload)

        assertEquals(8, response.race)
        assertEquals(2, response.checkpoints.size)
        val cp = response.checkpoints[0]
        assertEquals(101, cp.id)
        assertEquals(5, cp.number)
        assertEquals(10, cp.cost)
        assertEquals("kp", cp.type)
        assertEquals("У пня", cp.description)
        val cp2 = response.checkpoints[1]
        assertEquals(102, cp2.id)
        assertEquals(12, cp2.number)
        assertEquals(20, cp2.cost)
        assertEquals("start", cp2.type)
        assertEquals("Старт", cp2.description)
    }

    @Test
    fun emptyCheckpointsList_parsesAsHiddenLegend() {
        val payload = """{"race": 5, "checkpoints": []}"""

        val response = json.decodeFromString<LegendResponse>(payload)

        assertEquals(5, response.race)
        assertTrue(response.checkpoints.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val payload = """
            {
              "race": 8,
              "checkpoints": [
                {
                  "id": 1,
                  "number": 1,
                  "cost": 5,
                  "type": "kp",
                  "description": "test",
                  "future_field": "ignored"
                }
              ],
              "future_top_level": "also_ignored"
            }
        """.trimIndent()

        val response = json.decodeFromString<LegendResponse>(payload)

        assertEquals(1, response.checkpoints.size)
        assertEquals(1, response.checkpoints[0].id)
        assertEquals("test", response.checkpoints[0].description)
    }
}
