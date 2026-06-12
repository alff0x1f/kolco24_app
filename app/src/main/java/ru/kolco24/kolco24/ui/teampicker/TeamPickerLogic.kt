package ru.kolco24.kolco24.ui.teampicker

import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.db.TeamEntity

/**
 * Pure, testable logic shared by the comp/team picker screens. No Android or Compose imports —
 * everything here is unit-tested. Dates are compared **lexicographically as ISO strings**
 * (`YYYY-MM-DD` sorts correctly as text); [java.time.LocalDate] is deliberately avoided because
 * it needs API 26+ and core library desugaring is not enabled (minSdk 24 would crash on device).
 * `today` is always passed in as a `YYYY-MM-DD` string.
 */

/** Status pill shown on a race row. Carries only the label; color mapping lives in the composable. */
enum class RaceStatusPill(val label: String) {
    Finished("Завершено"),
    Registration("Регистрация"),
    Upcoming("Скоро"),
    SoldOut("Мест нет"),
}

/** Races split into current (still relevant) and archive (already finished), preserving Room order. */
data class SplitRaces(
    val current: List<RaceEntity>,
    val archive: List<RaceEntity>,
)

/** Last day the race is relevant: [RaceEntity.dateEnd] when present, otherwise the start [date]. */
private fun RaceEntity.effectiveEnd(): String = dateEnd ?: date

/**
 * Status pill for a race: finished if its last day is before [today]; otherwise derived from
 * [RaceEntity.regStatus] (`open` → registration, `upcoming` → soon, `sold_out` → no slots).
 * Any unknown status falls back to the neutral [RaceStatusPill.Upcoming] style.
 */
fun raceStatusPill(race: RaceEntity, today: String): RaceStatusPill {
    if (race.effectiveEnd() < today) return RaceStatusPill.Finished
    return when (race.regStatus) {
        "open" -> RaceStatusPill.Registration
        "sold_out" -> RaceStatusPill.SoldOut
        "upcoming" -> RaceStatusPill.Upcoming
        else -> RaceStatusPill.Upcoming
    }
}

/**
 * Split [races] into current (`effectiveEnd >= today`) and archive (already past), keeping the
 * order Room returned them in (newest first).
 */
fun splitRaces(races: List<RaceEntity>, today: String): SplitRaces {
    val current = races.filter { it.effectiveEnd() >= today }
    val archive = races.filter { it.effectiveEnd() < today }
    return SplitRaces(current = current, archive = archive)
}

/**
 * Teams whose [TeamEntity.teamname] or [TeamEntity.startNumber] contains [query] (case-insensitive
 * substring). A blank query returns every team.
 */
fun filterTeams(teams: List<TeamEntity>, query: String): List<TeamEntity> {
    val needle = query.trim()
    if (needle.isEmpty()) return teams
    val lower = needle.lowercase()
    return teams.filter { team ->
        team.teamname.lowercase().contains(lower) ||
            (team.startNumber?.lowercase()?.contains(lower) == true)
    }
}

/** "Категория X · N человек" line; full `человек` word. Used on the hero card and the confirm sheet. */
fun peopleLine(category: CategoryEntity?, ucount: Int): String {
    val cat = category?.shortName?.takeIf { it.isNotBlank() } ?: category?.name?.takeIf { it.isNotBlank() }
    return if (cat != null) "Категория $cat · $ucount человек" else "$ucount человек"
}

/**
 * Short text for a team token: the start number when present, otherwise a monogram from the name.
 * An empty start number (Django `default=""`) counts as "no number".
 */
fun teamToken(team: TeamEntity): String {
    val number = team.startNumber
    return if (number.isNullOrBlank()) initials(team.teamname) else number
}

/**
 * Human-readable team name for the list, sheet and hero card. Falls back when [TeamEntity.teamname]
 * is blank (`blank=True` in the model): "Команда <start_number>" if a number exists, else "Команда #<id>".
 */
fun displayTeamName(team: TeamEntity): String {
    if (team.teamname.isNotBlank()) return team.teamname
    val number = team.startNumber
    return if (!number.isNullOrBlank()) "Команда $number" else "Команда #${team.id}"
}

/**
 * Monogram from [text]: the first letter of up to [max] words, uppercased. Shared by the team
 * fallback token and member avatar initials. Blank input yields an empty string.
 */
fun initials(text: String, max: Int = 2): String =
    text.split(" ")
        .filter { it.isNotEmpty() }
        .take(max)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
