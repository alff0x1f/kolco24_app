package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun parsesLockedAndOpenCheckpointsWithTags() {
        // The doc's sample legend: open CPs (cost/description) + a locked CP (enc only),
        // plus two tag shapes (open-CP tag with null iv/ct, locked-CP tag with the envelope).
        val payload = """
            {
              "race": 8,
              "checkpoints": [
                {"id": 101, "number": 1, "type": "start", "cost": 1, "description": "Старт, поляна"},
                {"id": 102, "number": 31, "type": "kp", "cost": 3, "description": "Родник у тропы"},
                {"id": 103, "number": 32, "type": "kp",
                 "enc": {"iv": "8f3a", "ct": "b91c"}}
              ],
              "tags": [
                {"bid": "a1b2c3d4e5f60718", "point": 101, "check_method": "nfc",
                 "iv": null, "ct": null},
                {"bid": "9988776655443322", "point": 103, "check_method": "nfc",
                 "iv": "1d2e", "ct": "ff00"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<LegendResponse>(payload)

        assertEquals(8, response.race)
        assertEquals(3, response.checkpoints.size)

        val open = response.checkpoints[0]
        assertEquals(1, open.cost)
        assertEquals("Старт, поляна", open.description)
        assertNull(open.enc)

        val locked = response.checkpoints[2]
        assertEquals(103, locked.id)
        assertNull(locked.cost)
        assertNull(locked.description)
        assertNotNull(locked.enc)
        assertEquals("8f3a", locked.enc!!.iv)
        assertEquals("b91c", locked.enc!!.ct)

        assertEquals(2, response.tags.size)
        val openTag = response.tags[0]
        assertEquals("a1b2c3d4e5f60718", openTag.bid)
        assertEquals(101, openTag.point)
        assertEquals("nfc", openTag.checkMethod)
        assertNull(openTag.iv)
        assertNull(openTag.ct)

        val lockedTag = response.tags[1]
        assertEquals(103, lockedTag.point)
        assertEquals("1d2e", lockedTag.iv)
        assertEquals("ff00", lockedTag.ct)
    }

    @Test
    fun tagsDefaultToEmptyWhenAbsent() {
        val payload = """{"race": 5, "checkpoints": []}"""

        val response = json.decodeFromString<LegendResponse>(payload)

        assertTrue(response.tags.isEmpty())
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
