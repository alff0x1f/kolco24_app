package ru.kolco24.kolco24.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamMembersConverterTest {

    private val converter = TeamMembersConverter()

    @Test
    fun roundTripPreservesMembersAndOrder() {
        val members = listOf(
            TeamMemberItem(name = "Иван", numberInTeam = 1),
            TeamMemberItem(name = "Пётр", numberInTeam = 2),
            TeamMemberItem(name = "Анна", numberInTeam = 3),
        )

        val restored = converter.fromJson(converter.toJson(members))

        assertEquals(members, restored)
    }

    @Test
    fun roundTripEmptyList() {
        val restored = converter.fromJson(converter.toJson(emptyList()))

        assertTrue(restored.isEmpty())
    }
}
