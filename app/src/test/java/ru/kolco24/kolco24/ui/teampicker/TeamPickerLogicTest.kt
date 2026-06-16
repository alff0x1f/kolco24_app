package ru.kolco24.kolco24.ui.teampicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.nearestRaceId

class TeamPickerLogicTest {

    private fun race(
        id: Int = 1,
        date: String = "2026-06-13",
        dateEnd: String? = null,
        regStatus: String = "open",
    ) = RaceEntity(
        id = id,
        name = "Гонка $id",
        slug = "race-$id",
        date = date,
        dateEnd = dateEnd,
        place = "Лес",
        regStatus = regStatus,
        isLegendVisible = true,
    )

    private fun team(
        id: Int = 1,
        teamname: String = "Лоси",
        startNumber: String? = "201",
    ) = TeamEntity(
        id = id,
        raceId = 1,
        teamname = teamname,
        startNumber = startNumber,
        categoryId = 1,
        ucount = 2,
        paidPeople = 2.0,
        startTime = 0,
        finishTime = 0,
        members = emptyList(),
    )

    // --- raceStatusPill ---

    @Test
    fun statusFinishedWhenEndBeforeToday() {
        val r = race(date = "2026-06-10", dateEnd = "2026-06-12", regStatus = "open")
        assertEquals(RaceStatusPill.Finished, raceStatusPill(r, today = "2026-06-13"))
    }

    @Test
    fun statusTodayIsNotFinished() {
        // Race ends exactly today — still relevant, status driven by regStatus.
        val r = race(date = "2026-06-13", dateEnd = "2026-06-13", regStatus = "open")
        assertEquals(RaceStatusPill.Registration, raceStatusPill(r, today = "2026-06-13"))
    }

    @Test
    fun statusTomorrowUsesRegStatus() {
        val r = race(date = "2026-06-14", regStatus = "upcoming")
        assertEquals(RaceStatusPill.Upcoming, raceStatusPill(r, today = "2026-06-13"))
    }

    @Test
    fun statusSoldOut() {
        val r = race(date = "2026-06-20", regStatus = "sold_out")
        assertEquals(RaceStatusPill.SoldOut, raceStatusPill(r, today = "2026-06-13"))
    }

    @Test
    fun statusUnknownRegStatusFallsBackToUpcoming() {
        val r = race(date = "2026-06-20", regStatus = "something_new")
        assertEquals(RaceStatusPill.Upcoming, raceStatusPill(r, today = "2026-06-13"))
    }

    @Test
    fun statusUsesDateWhenDateEndNull() {
        // dateEnd null → fall back to date; date is in the past → finished.
        val r = race(date = "2026-06-12", dateEnd = null, regStatus = "open")
        assertEquals(RaceStatusPill.Finished, raceStatusPill(r, today = "2026-06-13"))
    }

    // --- splitRaces ---

    @Test
    fun splitPartitionsAndKeepsOrder() {
        val past = race(id = 1, date = "2026-06-01")
        val today = race(id = 2, date = "2026-06-13")
        val future = race(id = 3, date = "2026-06-20")
        val result = splitRaces(listOf(future, today, past), today = "2026-06-13")

        assertEquals(listOf(3, 2), result.current.map { it.id })
        assertEquals(listOf(1), result.archive.map { it.id })
    }

    @Test
    fun splitUsesDateEndFallback() {
        // dateEnd present and in the future keeps the race current even though date is past.
        val multiDay = race(id = 1, date = "2026-06-10", dateEnd = "2026-06-15")
        val result = splitRaces(listOf(multiDay), today = "2026-06-13")
        assertEquals(listOf(1), result.current.map { it.id })
        assertTrue(result.archive.isEmpty())
    }

    @Test
    fun splitEmpty() {
        val result = splitRaces(emptyList(), today = "2026-06-13")
        assertTrue(result.current.isEmpty())
        assertTrue(result.archive.isEmpty())
    }

    // --- nearestRaceId ---

    @Test
    fun nearestPicksEarliestStartDateAmongCurrentRaces() {
        // Ongoing race (started June 10) has an earlier start date than upcoming race (June 18),
        // so it wins — result of minByOrNull { date }, not a special "ongoing wins" rule.
        val ongoing = race(id = 1, date = "2026-06-10", dateEnd = "2026-06-20")
        val upcoming = race(id = 2, date = "2026-06-18")
        assertEquals(1, nearestRaceId(listOf(upcoming, ongoing), today = "2026-06-13"))
    }

    @Test
    fun nearestPicksEarliestStartAmongOverlappingOngoing() {
        // Both ongoing today; selection is by start date, so the earlier start wins.
        val earlierStart = race(id = 1, date = "2026-06-10", dateEnd = "2026-06-20")
        val laterStart = race(id = 2, date = "2026-06-12", dateEnd = "2026-06-18")
        assertEquals(1, nearestRaceId(listOf(laterStart, earlierStart), today = "2026-06-13"))
    }

    @Test
    fun nearestPicksSoonestFutureStart() {
        val soon = race(id = 1, date = "2026-06-15")
        val later = race(id = 2, date = "2026-06-20")
        val latest = race(id = 3, date = "2026-07-01")
        assertEquals(1, nearestRaceId(listOf(latest, later, soon), today = "2026-06-13"))
    }

    @Test
    fun nearestIncludesRaceEndingToday() {
        // effectiveEnd >= today is inclusive; a race ending exactly today must still be selected.
        val endingToday = race(id = 1, date = "2026-06-13", dateEnd = "2026-06-13")
        assertEquals(1, nearestRaceId(listOf(endingToday), today = "2026-06-13"))
    }

    @Test
    fun nearestNullWhenAllArchived() {
        val past1 = race(id = 1, date = "2026-06-01", dateEnd = "2026-06-02")
        val past2 = race(id = 2, date = "2026-05-10")
        assertEquals(null, nearestRaceId(listOf(past1, past2), today = "2026-06-13"))
    }

    @Test
    fun nearestNullWhenEmpty() {
        assertEquals(null, nearestRaceId(emptyList(), today = "2026-06-13"))
    }

    @Test
    fun nearestSameStartDateDoesNotCrash() {
        val a = race(id = 1, date = "2026-06-15")
        val b = race(id = 2, date = "2026-06-15")
        // Tie-break order is unspecified; only guarantee is a non-null result.
        assertNotNull(nearestRaceId(listOf(a, b), today = "2026-06-13"))
        assertNotNull(nearestRaceId(listOf(b, a), today = "2026-06-13"))
    }

    // --- filterTeams ---

    @Test
    fun filterByName() {
        val teams = listOf(team(id = 1, teamname = "Лоси"), team(id = 2, teamname = "Волки"))
        assertEquals(listOf(1), filterTeams(teams, "лос").map { it.id })
    }

    @Test
    fun filterByStartNumber() {
        val teams = listOf(team(id = 1, startNumber = "201"), team(id = 2, startNumber = "305"))
        assertEquals(listOf(2), filterTeams(teams, "305").map { it.id })
    }

    @Test
    fun filterIsCaseInsensitive() {
        val teams = listOf(team(id = 1, teamname = "Лоси"))
        assertEquals(listOf(1), filterTeams(teams, "ЛОСИ").map { it.id })
    }

    @Test
    fun filterEmptyQueryReturnsAll() {
        val teams = listOf(team(id = 1), team(id = 2))
        assertEquals(listOf(1, 2), filterTeams(teams, "   ").map { it.id })
    }

    @Test
    fun filterNoMatch() {
        val teams = listOf(team(id = 1, teamname = "Лоси", startNumber = "201"))
        assertTrue(filterTeams(teams, "zzz").isEmpty())
    }

    @Test
    fun filterNullStartNumberDoesNotCrash() {
        val teams = listOf(team(id = 1, teamname = "Лоси", startNumber = null))
        assertTrue(filterTeams(teams, "201").isEmpty())
        assertEquals(listOf(1), filterTeams(teams, "лос").map { it.id })
    }

    // --- teamToken ---

    @Test
    fun tokenUsesStartNumber() {
        assertEquals("201", teamToken(team(startNumber = "201")))
    }

    @Test
    fun tokenFallsBackToInitialsWhenNull() {
        assertEquals("ЛТ", teamToken(team(teamname = "Лесные тропы", startNumber = null)))
    }

    @Test
    fun tokenFallsBackToInitialsWhenEmpty() {
        assertEquals("ЛТ", teamToken(team(teamname = "Лесные тропы", startNumber = "")))
    }

    @Test
    fun tokenFallsBackToIdWhenBothBlank() {
        assertEquals("#42", teamToken(team(id = 42, teamname = "", startNumber = null)))
    }

    // --- displayTeamName ---

    @Test
    fun displayNameUsesTeamname() {
        assertEquals("Лоси", displayTeamName(team(teamname = "Лоси")))
    }

    @Test
    fun displayNameFallsBackToNumberWhenBlank() {
        assertEquals("Команда 201", displayTeamName(team(teamname = "", startNumber = "201")))
    }

    @Test
    fun displayNameFallsBackToIdWhenBlankAndNoNumber() {
        assertEquals("Команда #7", displayTeamName(team(id = 7, teamname = "", startNumber = null)))
    }

    // --- initials ---

    @Test
    fun initialsOneWord() {
        assertEquals("Л", initials("Лоси"))
    }

    @Test
    fun initialsTwoWords() {
        assertEquals("ЛТ", initials("лесные тропы"))
    }

    @Test
    fun initialsRespectsMax() {
        assertEquals("АБ", initials("Альфа Браво Чарли", max = 2))
        assertEquals("АБЧ", initials("Альфа Браво Чарли", max = 3))
    }

    @Test
    fun initialsEmptyString() {
        assertEquals("", initials(""))
        assertEquals("", initials("   "))
    }

    // --- peopleWord ---

    @Test
    fun peopleWordSingular() {
        assertEquals("человек", peopleWord(1))
        assertEquals("человек", peopleWord(21))
    }

    @Test
    fun peopleWordFewForm() {
        assertEquals("человека", peopleWord(2))
        assertEquals("человека", peopleWord(3))
        assertEquals("человека", peopleWord(4))
        assertEquals("человека", peopleWord(22))
        assertEquals("человека", peopleWord(24))
    }

    @Test
    fun peopleWordManyForm() {
        assertEquals("человек", peopleWord(5))
        assertEquals("человек", peopleWord(11))
        assertEquals("человек", peopleWord(12))
        assertEquals("человек", peopleWord(14))
        assertEquals("человек", peopleWord(20))
        assertEquals("человек", peopleWord(100))
    }

    // --- peopleLine ---

    private fun category(shortName: String = "Муж", name: String = "Мужская") = CategoryEntity(
        id = 1, raceId = 1, code = "M", shortName = shortName, name = name, sortOrder = 0,
    )

    @Test
    fun peopleLineWithoutCategory() {
        assertEquals("2 человека", peopleLine(null, 2))
        assertEquals("5 человек", peopleLine(null, 5))
    }

    @Test
    fun peopleLineWithCategory() {
        assertEquals("Категория Муж · 3 человека", peopleLine(category(), 3))
        assertEquals("Категория Муж · 1 человек", peopleLine(category(), 1))
    }

    @Test
    fun peopleLineUsesNameWhenShortNameBlank() {
        assertEquals("Категория Мужская · 2 человека", peopleLine(category(shortName = ""), 2))
    }
}
