package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRealBackendResponse() {
        // A realistic payload from GET /app/race/<race_id>/teams/ with start_number present.
        val payload = """
            {
              "race": 8,
              "categories": [
                {
                  "id": 1,
                  "code": "M",
                  "short_name": "МУЖ",
                  "name": "Мужская",
                  "order": 0
                }
              ],
              "teams": [
                {
                  "id": 42,
                  "teamname": "Лоси",
                  "start_number": "201",
                  "category2": 1,
                  "ucount": 2,
                  "paid_people": 2.0,
                  "start_time": 1718870400,
                  "finish_time": 1718956800,
                  "members": [
                    { "name": "Иван Петров", "number_in_team": 1 },
                    { "name": "Пётр Иванов", "number_in_team": 2 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<TeamsResponse>(payload)

        assertEquals(8, response.race)
        assertEquals(1, response.categories.size)
        val category = response.categories[0]
        assertEquals(1, category.id)
        assertEquals("M", category.code)
        assertEquals("МУЖ", category.shortName)
        assertEquals("Мужская", category.name)
        assertEquals(0, category.order)

        assertEquals(1, response.teams.size)
        val team = response.teams[0]
        assertEquals(42, team.id)
        assertEquals("Лоси", team.teamname)
        assertEquals("201", team.startNumber)
        assertEquals(1, team.category2)
        assertEquals(2, team.ucount)
        assertEquals(2.0, team.paidPeople, 0.0)
        assertEquals(1718870400L, team.startTime)
        assertEquals(1718956800L, team.finishTime)
        assertEquals(2, team.members.size)
        assertEquals("Иван Петров", team.members[0].name)
        assertEquals(1, team.members[0].numberInTeam)
        assertEquals("Пётр Иванов", team.members[1].name)
        assertEquals(2, team.members[1].numberInTeam)
    }

    @Test
    fun emptyStartNumberParsesToEmptyString() {
        // Django model default="" — the main "no number" case is an empty string, not null.
        val payload = """
            {
              "race": 1,
              "categories": [],
              "teams": [
                {
                  "id": 1,
                  "teamname": "Без номера",
                  "start_number": "",
                  "category2": null,
                  "ucount": 1,
                  "paid_people": 0.0,
                  "start_time": 0,
                  "finish_time": 0,
                  "members": []
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<TeamsResponse>(payload)

        val team = response.teams[0]
        assertEquals("", team.startNumber)
        assertNull(team.category2)
        assertTrue(team.members.isEmpty())
    }

    @Test
    fun missingStartNumberParsesToNull() {
        // Old format from API.md — no start_number field at all.
        val payload = """
            {
              "race": 1,
              "categories": [],
              "teams": [
                {
                  "id": 1,
                  "teamname": "Старый формат",
                  "category2": null,
                  "ucount": 0,
                  "paid_people": 0.0,
                  "start_time": 0,
                  "finish_time": 0,
                  "members": []
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<TeamsResponse>(payload)

        assertNull(response.teams[0].startNumber)
    }

    @Test
    fun emptyTeamsAndCategoriesParse() {
        val payload = """
            {
              "race": 5,
              "categories": [],
              "teams": []
            }
        """.trimIndent()

        val response = json.decodeFromString<TeamsResponse>(payload)

        assertEquals(5, response.race)
        assertTrue(response.categories.isEmpty())
        assertTrue(response.teams.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val payload = """
            {
              "race": 8,
              "categories": [
                {
                  "id": 1,
                  "code": "M",
                  "short_name": "МУЖ",
                  "name": "Мужская",
                  "order": 0,
                  "future_category_field": "ignored"
                }
              ],
              "teams": [
                {
                  "id": 42,
                  "teamname": "Лоси",
                  "start_number": "201",
                  "category2": 1,
                  "ucount": 2,
                  "paid_people": 2.0,
                  "start_time": 0,
                  "finish_time": 0,
                  "members": [
                    { "name": "Иван", "number_in_team": 1, "future_member_field": true }
                  ],
                  "future_team_field": 99
                }
              ],
              "future_top_level": "ignored"
            }
        """.trimIndent()

        val response = json.decodeFromString<TeamsResponse>(payload)

        assertEquals(42, response.teams[0].id)
        assertEquals(1, response.teams[0].members[0].numberInTeam)
        assertEquals(1, response.categories[0].id)
    }
}
