package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberTagsResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesMemberTagsList() {
        val payload = """
            {
              "member_tags": [
                {"number": 101, "nfc_uid": "04A2B3C4D5E680"},
                {"number": 102, "nfc_uid": "0489AB12CD34EF"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<MemberTagsResponse>(payload)

        assertEquals(2, response.memberTags.size)
        assertEquals(101, response.memberTags[0].number)
        assertEquals("04A2B3C4D5E680", response.memberTags[0].nfcUid)
        assertEquals(102, response.memberTags[1].number)
        assertEquals("0489AB12CD34EF", response.memberTags[1].nfcUid)
    }

    @Test
    fun emptyList_parsesCorrectly() {
        val payload = """{"member_tags": []}"""

        val response = json.decodeFromString<MemberTagsResponse>(payload)

        assertTrue(response.memberTags.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val payload = """
            {
              "member_tags": [
                {"number": 101, "nfc_uid": "04A2B3C4D5E680", "future_field": "ignored"}
              ],
              "future_top_level": "also_ignored"
            }
        """.trimIndent()

        val response = json.decodeFromString<MemberTagsResponse>(payload)

        assertEquals(1, response.memberTags.size)
        assertEquals(101, response.memberTags[0].number)
        assertEquals("04A2B3C4D5E680", response.memberTags[0].nfcUid)
    }
}
