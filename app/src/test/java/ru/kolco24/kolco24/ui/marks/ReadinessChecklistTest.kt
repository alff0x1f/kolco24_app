package ru.kolco24.kolco24.ui.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.NfcState
import ru.kolco24.kolco24.data.time.ClockStatus
import ru.kolco24.kolco24.ui.map.MapAvailability

class ReadinessChecklistTest {

    private val ready = ReadinessInput(
        team = TeamReadiness.Present,
        teamTitle = "Ф-мажор · 101",
        memberCount = 4,
        boundCount = 4,
        nfc = NfcState.Available,
        location = LocationAccess.Precise,
        legendCount = 30,
        map = MapAvailability.Ready,
        clock = ClockStatus.Ok,
        notificationsGranted = true,
        powerSaveMode = false,
    )

    private fun item(input: ReadinessInput, id: ReadinessItemId): ReadinessItem =
        readinessItems(input).single { it.id == id }

    private fun itemOrNull(input: ReadinessInput, id: ReadinessItemId): ReadinessItem? =
        readinessItems(input).singleOrNull { it.id == id }

    // --- team / chips ---

    @Test
    fun `no team blocks team and chips, chips has no action`() {
        val input = ready.copy(team = TeamReadiness.None, teamTitle = null, memberCount = 0, boundCount = 0)
        val team = item(input, ReadinessItemId.Team)
        val chips = item(input, ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Blocked, team.status)
        assertEquals("Выберите соревнование и команду", team.detail)
        assertEquals(ReadinessAction.ChooseTeam, team.action)
        assertEquals(ReadinessStatus.Blocked, chips.status)
        assertEquals("Сначала выберите команду", chips.detail)
        assertNull(chips.action)
    }

    @Test
    fun `missing team says not found and offers choose team, chips has no action`() {
        val input = ready.copy(team = TeamReadiness.Missing)
        val team = item(input, ReadinessItemId.Team)
        assertEquals(ReadinessStatus.Blocked, team.status)
        assertEquals("Команда не найдена — выберите заново", team.detail)
        assertEquals(ReadinessAction.ChooseTeam, team.action)
        val chips = item(input, ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Blocked, chips.status)
        assertEquals("Чипы не привязаны", chips.title)
        assertEquals("Сначала выберите команду", chips.detail)
        assertNull(chips.action)
    }

    @Test
    fun `present team is done with team title`() {
        val team = item(ready, ReadinessItemId.Team)
        assertEquals(ReadinessStatus.Done, team.status)
        assertEquals("Команда выбрана", team.title)
        assertEquals("Ф-мажор · 101", team.detail)
    }

    @Test
    fun `more bound than members is done capped at member count`() {
        val chips = item(ready.copy(boundCount = 5), ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Done, chips.status)
        assertEquals("4 из 4", chips.detail)
    }

    @Test
    fun `no chips bound is blocked with zero count`() {
        val chips = item(ready.copy(boundCount = 0), ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Blocked, chips.status)
        assertEquals("Чипы привязаны не все", chips.title)
        assertEquals("0 из 4", chips.detail)
        assertEquals(ReadinessAction.BindChips, chips.action)
    }

    @Test
    fun `partially bound chips are blocked with count and bind action`() {
        val chips = item(ready.copy(boundCount = 3), ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Blocked, chips.status)
        assertEquals("3 из 4", chips.detail)
        assertEquals(ReadinessAction.BindChips, chips.action)
    }

    @Test
    fun `all chips bound is done`() {
        val chips = item(ready, ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Done, chips.status)
        assertEquals("4 из 4", chips.detail)
    }

    @Test
    fun `empty roster is blocked with refresh`() {
        val chips = item(ready.copy(memberCount = 0, boundCount = 0), ReadinessItemId.Chips)
        assertEquals(ReadinessStatus.Blocked, chips.status)
        assertEquals("Состав команды не загружен", chips.detail)
        assertEquals(ReadinessAction.Refresh, chips.action)
    }

    // --- nfc / location ---

    @Test
    fun `nfc disabled is blocked with settings action`() {
        val nfc = item(ready.copy(nfc = NfcState.Disabled), ReadinessItemId.Nfc)
        assertEquals(ReadinessStatus.Blocked, nfc.status)
        assertEquals("NFC выключен", nfc.title)
        assertEquals("Включите NFC в настройках телефона", nfc.detail)
        assertEquals(ReadinessAction.OpenNfcSettings, nfc.action)
    }

    @Test
    fun `no nfc hardware is a warning without action`() {
        val nfc = item(ready.copy(nfc = NfcState.NoHardware), ReadinessItemId.Nfc)
        assertEquals(ReadinessStatus.Warning, nfc.status)
        assertEquals("Отмечайте КП через «Фото»", nfc.detail)
        assertNull(nfc.action)
    }

    @Test
    fun `nfc available is done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Nfc).status)
    }

    @Test
    fun `no and approximate location are warnings with request action and distinct details`() {
        val none = item(ready.copy(location = LocationAccess.None), ReadinessItemId.Location)
        val approx = item(ready.copy(location = LocationAccess.Approximate), ReadinessItemId.Location)
        assertEquals(ReadinessStatus.Warning, none.status)
        assertEquals(ReadinessStatus.Warning, approx.status)
        assertEquals(ReadinessAction.RequestLocation, none.action)
        assertEquals(ReadinessAction.RequestLocation, approx.action)
        assertEquals("Нет доступа — отметка без координаты", none.detail)
        assertEquals("Примерная геопозиция", approx.detail)
    }

    @Test
    fun `precise location is done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Location).status)
    }

    // --- legend / map ---

    @Test
    fun `empty legend is a warning with refresh`() {
        val legend = item(ready.copy(legendCount = 0), ReadinessItemId.Legend)
        assertEquals(ReadinessStatus.Warning, legend.status)
        assertEquals("Легенда не загружена", legend.title)
        assertEquals("Обновите данные, пока есть сеть", legend.detail)
        assertEquals(ReadinessAction.Refresh, legend.action)
    }

    @Test
    fun `empty legend without team has no action`() {
        for (team in listOf(TeamReadiness.None, TeamReadiness.Missing)) {
            val legend = item(ready.copy(team = team, legendCount = 0), ReadinessItemId.Legend)
            assertEquals("$team", ReadinessStatus.Warning, legend.status)
            assertEquals("$team", "Сначала выберите команду", legend.detail)
            assertNull("$team", legend.action)
        }
    }

    @Test
    fun `loaded legend is done even without team`() {
        val legend = item(ready.copy(team = TeamReadiness.None, legendCount = 12), ReadinessItemId.Legend)
        assertEquals(ReadinessStatus.Done, legend.status)
        assertEquals("КП в легенде: 12", legend.detail)
    }

    @Test
    fun `loaded legend is done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Legend).status)
    }

    @Test
    fun `no map for race hides the row`() {
        assertNull(itemOrNull(ready.copy(map = MapAvailability.NoMapForRace), ReadinessItemId.Map))
    }

    @Test
    fun `not downloaded and busy other race are warnings with open map`() {
        for (map in listOf(MapAvailability.NotDownloaded, MapAvailability.BusyOtherRace)) {
            val row = item(ready.copy(map = map), ReadinessItemId.Map)
            assertEquals("$map", ReadinessStatus.Warning, row.status)
            assertEquals("$map", "Карта не скачана", row.title)
            assertEquals("$map", ReadinessAction.OpenMap, row.action)
        }
    }

    @Test
    fun `downloading shows percent`() {
        val row = item(ready.copy(map = MapAvailability.Downloading(0.42f)), ReadinessItemId.Map)
        assertEquals(ReadinessStatus.Warning, row.status)
        assertEquals("Скачивается · 42%", row.detail)
        assertEquals(ReadinessAction.OpenMap, row.action)
    }

    @Test
    fun `downloading percent is clamped and rounded`() {
        for ((progress, expected) in listOf(1.2f to "100%", -0.1f to "0%", 0.995f to "100%", 0.004f to "0%")) {
            val row = item(ready.copy(map = MapAvailability.Downloading(progress)), ReadinessItemId.Map)
            assertEquals("$progress", "Скачивается · $expected", row.detail)
        }
    }

    @Test
    fun `indeterminate downloading has no percent`() {
        val row = item(ready.copy(map = MapAvailability.Downloading(null)), ReadinessItemId.Map)
        assertEquals("Скачивается", row.detail)
    }

    @Test
    fun `ready map is done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Map).status)
    }

    // --- clock / notifications / power ---

    @Test
    fun `no sync and skewed clock are warnings with distinct details`() {
        val noSync = item(ready.copy(clock = ClockStatus.NoSync), ReadinessItemId.Clock)
        val skewed = item(ready.copy(clock = ClockStatus.Skewed(-120_000)), ReadinessItemId.Clock)
        assertEquals(ReadinessStatus.Warning, noSync.status)
        assertEquals(ReadinessStatus.Warning, skewed.status)
        assertNotEquals(noSync.detail, skewed.detail)
        assertEquals("Часы не синхронизированы", noSync.title)
        assertEquals("Время не подтверждено — подключитесь к сети", noSync.detail)
        assertTrue(skewed.detail, skewed.detail.contains("2 мин"))
        assertTrue(skewed.detail, skewed.detail.startsWith("Расходятся с сервером на "))
        assertNull(noSync.action)
        assertNull(skewed.action)
    }

    @Test
    fun `positive skew is a warning naming the minutes`() {
        val skewed = item(ready.copy(clock = ClockStatus.Skewed(300_000)), ReadinessItemId.Clock)
        assertEquals(ReadinessStatus.Warning, skewed.status)
        assertTrue(skewed.detail, skewed.detail.contains("5 мин"))
    }

    @Test
    fun `ok clock is done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Clock).status)
    }

    @Test
    fun `notifications row hidden below api 33`() {
        assertNull(itemOrNull(ready.copy(notificationsGranted = null), ReadinessItemId.Notifications))
    }

    @Test
    fun `denied notifications are a warning with request action`() {
        val row = item(ready.copy(notificationsGranted = false), ReadinessItemId.Notifications)
        assertEquals(ReadinessStatus.Warning, row.status)
        assertEquals("Не будет видно уведомления о записи трека", row.detail)
        assertEquals(ReadinessAction.RequestNotifications, row.action)
    }

    @Test
    fun `granted notifications are done`() {
        assertEquals(ReadinessStatus.Done, item(ready, ReadinessItemId.Notifications).status)
    }

    @Test
    fun `power row hidden when power save is off`() {
        assertNull(itemOrNull(ready, ReadinessItemId.Power))
    }

    @Test
    fun `power save on is a warning with battery saver settings action`() {
        val row = item(ready.copy(powerSaveMode = true), ReadinessItemId.Power)
        assertEquals(ReadinessStatus.Warning, row.status)
        assertEquals("Включено энергосбережение", row.title)
        assertEquals("Трек и фоновая загрузка могут прерываться", row.detail)
        assertEquals(ReadinessAction.OpenBatterySaverSettings, row.action)
    }

    @Test
    fun `done rows keep only team chips and map actions`() {
        val expected = mapOf(
            ReadinessItemId.Team to ReadinessAction.ChooseTeam,
            ReadinessItemId.Chips to ReadinessAction.BindChips,
            ReadinessItemId.Nfc to null,
            ReadinessItemId.Location to null,
            ReadinessItemId.Legend to null,
            ReadinessItemId.Map to ReadinessAction.OpenMap,
            ReadinessItemId.Clock to null,
            ReadinessItemId.Notifications to null,
        )
        val items = readinessItems(ready)
        assertEquals(expected.keys.toList(), items.map { it.id })
        for (row in items) {
            assertEquals("${row.id}", ReadinessStatus.Done, row.status)
            assertEquals("${row.id}", expected[row.id], row.action)
        }
    }

    // --- invariants ---

    private val worstInput = ReadinessInput(
        team = TeamReadiness.None,
        teamTitle = null,
        memberCount = 0,
        boundCount = 0,
        nfc = NfcState.Disabled,
        location = LocationAccess.None,
        legendCount = 0,
        map = MapAvailability.NotDownloaded,
        clock = ClockStatus.NoSync,
        notificationsGranted = false,
        powerSaveMode = true,
    )

    @Test
    fun `order is fixed regardless of statuses`() {
        val all = ReadinessItemId.entries.toList()
        assertEquals(all, readinessItems(worstInput).map { it.id })
        assertEquals(all, readinessItems(ready.copy(powerSaveMode = true)).map { it.id })
        val mixed = ready.copy(
            nfc = NfcState.NoHardware, clock = ClockStatus.Skewed(90_000), map = MapAvailability.NoMapForRace,
            notificationsGranted = null,
        )
        assertEquals(
            listOf(
                ReadinessItemId.Team, ReadinessItemId.Chips, ReadinessItemId.Nfc, ReadinessItemId.Location,
                ReadinessItemId.Legend, ReadinessItemId.Clock,
            ),
            readinessItems(mixed).map { it.id },
        )
    }

    @Test
    fun `blocked only on team chips and nfc`() {
        val allowed = setOf(ReadinessItemId.Team, ReadinessItemId.Chips, ReadinessItemId.Nfc)
        val maps = listOf(
            MapAvailability.NoMapForRace, MapAvailability.Ready, MapAvailability.NotDownloaded,
            MapAvailability.BusyOtherRace, MapAvailability.Downloading(0.5f), MapAvailability.Downloading(null),
        )
        val clocks = listOf(ClockStatus.Ok, ClockStatus.NoSync, ClockStatus.Skewed(600_000))
        val counts = listOf(Triple(0, 0, 0), Triple(4, 1, 30), Triple(4, 4, 0))
        var cases = 0
        var blockedSeen = 0
        for (team in TeamReadiness.entries) for (nfc in NfcState.entries) for (loc in LocationAccess.entries)
            for (map in maps) for (clock in clocks) for (notif in listOf(null, true, false))
                for (power in listOf(false, true)) for ((members, bound, legend) in counts) {
                    val input = ReadinessInput(
                        team, "T", members, bound, nfc, loc, legend, map, clock, notif, power,
                    )
                    cases++
                    for (row in readinessItems(input).filter { it.status == ReadinessStatus.Blocked }) {
                        blockedSeen++
                        assertTrue("${row.id} blocked for $input", row.id in allowed)
                    }
                }
        assertTrue(cases > 1000)
        assertTrue("no Blocked row was ever produced", blockedSeen > 0)
    }

    @Test
    fun `summary of all done`() {
        val items = readinessItems(ready)
        val s = readinessSummary(items)
        assertEquals(items.size, s.total)
        assertEquals(items.size, s.done)
        assertEquals(ReadinessStatus.Done, s.worst)
        assertTrue(s.allDone)
    }

    @Test
    fun `summary total follows hidden rows`() {
        val items = readinessItems(ready.copy(map = MapAvailability.NoMapForRace, notificationsGranted = null))
        assertEquals(6, readinessSummary(items).total)
        assertEquals(9, readinessSummary(readinessItems(worstInput)).total)
    }

    @Test
    fun `summary worst is warning when only warnings`() {
        val s = readinessSummary(readinessItems(ready.copy(location = LocationAccess.Approximate)))
        assertEquals(ReadinessStatus.Warning, s.worst)
        assertEquals(s.total - 1, s.done)
        assertFalse(s.allDone)
    }

    @Test
    fun `summary worst is blocked over warning`() {
        val s = readinessSummary(readinessItems(worstInput))
        assertEquals(ReadinessStatus.Blocked, s.worst)
        assertEquals(0, s.done)
        assertFalse(s.allDone)
    }

    @Test
    fun `summary worst is blocked with blocked and done rows only`() {
        val s = readinessSummary(readinessItems(ready.copy(nfc = NfcState.Disabled)))
        assertEquals(ReadinessStatus.Blocked, s.worst)
        assertEquals(s.total - 1, s.done)
        assertFalse(s.allDone)
    }

    @Test
    fun `summary of empty list is all done`() {
        val s = readinessSummary(emptyList())
        assertEquals(0, s.total)
        assertEquals(ReadinessStatus.Done, s.worst)
        assertTrue(s.allDone)
    }

    @Test
    fun `gate opens only when all signals resolved`() {
        assertTrue(readinessVisible(marksLoading = false, legendLoaded = true, mapResolved = true, teamMatches = true))
        assertFalse(readinessVisible(marksLoading = true, legendLoaded = true, mapResolved = true, teamMatches = true))
        assertFalse(readinessVisible(marksLoading = false, legendLoaded = false, mapResolved = true, teamMatches = true))
        assertFalse(readinessVisible(marksLoading = false, legendLoaded = true, mapResolved = false, teamMatches = true))
        assertFalse(readinessVisible(marksLoading = false, legendLoaded = true, mapResolved = true, teamMatches = false))
    }

    @Test
    fun `team matches only when the resolved team is the selected one`() {
        assertTrue(readinessTeamMatches(selectedTeamId = 7, resolvedTeamId = 7))
        assertTrue(readinessTeamMatches(selectedTeamId = null, resolvedTeamId = null))
        // Mid-switch: the roster collector still holds team 7 while bindings are already keyed on 8.
        assertFalse(readinessTeamMatches(selectedTeamId = 8, resolvedTeamId = 7))
        // Selection cleared/set while the other collector lags behind.
        assertFalse(readinessTeamMatches(selectedTeamId = null, resolvedTeamId = 7))
        assertFalse(readinessTeamMatches(selectedTeamId = 8, resolvedTeamId = null))
    }

    // --- host-side derivations ---

    @Test
    fun `team title appends start number unless blank`() {
        assertEquals("Ф-мажор · №101", readinessTeamTitle("Ф-мажор", "101"))
        assertEquals("Ф-мажор", readinessTeamTitle("Ф-мажор", ""))
        assertEquals("Ф-мажор", readinessTeamTitle("Ф-мажор", "  "))
        assertEquals("Ф-мажор", readinessTeamTitle("Ф-мажор", null))
    }

    @Test
    fun `bound count counts member slots with a binding, not bindings`() {
        assertEquals(2, readinessBoundCount(listOf(1, 2, 3, 4), setOf(1, 3, 7)))
        assertEquals(0, readinessBoundCount(emptyList(), setOf(1, 2)))
    }

    @Test
    fun `location access from grants`() {
        assertEquals(LocationAccess.Precise, locationAccessOf(fineGranted = true, coarseGranted = true))
        assertEquals(LocationAccess.Precise, locationAccessOf(fineGranted = true, coarseGranted = false))
        assertEquals(LocationAccess.Approximate, locationAccessOf(fineGranted = false, coarseGranted = true))
        assertEquals(LocationAccess.None, locationAccessOf(fineGranted = false, coarseGranted = false))
    }

    @Test
    fun `map resolved waits for listing and catalog emission, not race presence`() {
        assertTrue(readinessMapResolved(selectedRaceId = null, mapListingKnown = false, catalogEmitted = false))
        assertTrue(readinessMapResolved(selectedRaceId = 3, mapListingKnown = true, catalogEmitted = true))
        assertFalse(readinessMapResolved(selectedRaceId = 3, mapListingKnown = false, catalogEmitted = true))
        assertFalse(readinessMapResolved(selectedRaceId = 3, mapListingKnown = true, catalogEmitted = false))
    }

    @Test
    fun `legend loaded only for the selected race's own emission`() {
        assertTrue(readinessLegendLoaded(selectedRaceId = null, taggedCount = null))
        assertFalse(readinessLegendLoaded(selectedRaceId = 3, taggedCount = null))
        // Race switch 2 → 3: the previous race's count must not open the gate.
        assertFalse(readinessLegendLoaded(selectedRaceId = 3, taggedCount = 2 to 30))
        assertTrue(readinessLegendLoaded(selectedRaceId = 3, taggedCount = 3 to 0))
    }
}
