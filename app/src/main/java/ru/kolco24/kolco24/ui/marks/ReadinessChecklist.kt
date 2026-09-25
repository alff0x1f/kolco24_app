package ru.kolco24.kolco24.ui.marks

import ru.kolco24.kolco24.NfcState
import ru.kolco24.kolco24.data.time.ClockStatus
import ru.kolco24.kolco24.ui.common.formatSkewMinutes
import ru.kolco24.kolco24.ui.map.MapAvailability

/*
 * Pre-start readiness checklist for the «Отметки» tab (shown while no КП is taken yet).
 *
 * Pure (Android-free, JVM-tested by `ReadinessChecklistTest`): all status / hide / text / action
 * rules live here; the view only picks a colour per [ReadinessStatus] and forwards
 * [ReadinessAction]s up to `MainActivity`, which owns the device signals and permission launchers.
 */

enum class ReadinessStatus { Done, Warning, Blocked }

/** Fixed display order — rows never re-sort (jumping rows read worse than a stable list). */
enum class ReadinessItemId { Team, Chips, Nfc, Location, Legend, Map, Clock, Notifications, Power }

enum class ReadinessAction {
    ChooseTeam, BindChips, OpenNfcSettings, RequestLocation, Refresh, OpenMap,
    RequestNotifications, OpenBatterySaverSettings,
}

/** FINE granted → [Precise]; only COARSE → [Approximate]; neither → [None]. */
enum class LocationAccess { None, Approximate, Precise }

/** `SelectedTeamState` collapsed for the checklist: `Present` / `Missing` (deleted server-side) / else `None`. */
enum class TeamReadiness { None, Missing, Present }

data class ReadinessItem(
    val id: ReadinessItemId,
    val status: ReadinessStatus,
    val title: String,
    val detail: String,
    val action: ReadinessAction?,
)

data class ReadinessInput(
    val team: TeamReadiness,
    /** Team name (+ start number) of the selected team; `""` without a team. */
    val teamTitle: String,
    val memberCount: Int,
    val boundCount: Int,
    val nfc: NfcState,
    val location: LocationAccess,
    val legendCount: Int,
    /** [MapAvailability.NoMapForRace] hides the row. */
    val map: MapAvailability,
    val clock: ClockStatus,
    /** `null` = API < 33 (no runtime permission) → row hidden. */
    val notificationsGranted: Boolean?,
    val powerSaveMode: Boolean,
)

data class ReadinessSummary(
    val done: Int,
    val total: Int,
    val worst: ReadinessStatus,
    val allDone: Boolean,
)

private const val NEED_TEAM = "Сначала выберите команду"

/**
 * Checklist rows in the fixed [ReadinessItemId] order. Map is hidden on [MapAvailability.NoMapForRace],
 * Notifications when [ReadinessInput.notificationsGranted] is `null`, Power unless power-save is on.
 * Only Team / Chips / Nfc can be [ReadinessStatus.Blocked] — without them an NFC take cannot work.
 */
fun readinessItems(input: ReadinessInput): List<ReadinessItem> = buildList {
    add(teamItem(input))
    add(chipsItem(input))
    add(nfcItem(input.nfc))
    add(locationItem(input.location))
    add(legendItem(input))
    mapItem(input.map)?.let(::add)
    add(clockItem(input.clock))
    notificationsItem(input.notificationsGranted)?.let(::add)
    if (input.powerSaveMode) {
        add(
            ReadinessItem(
                ReadinessItemId.Power, ReadinessStatus.Warning,
                "Включено энергосбережение",
                "Трек и фоновая загрузка могут прерываться",
                ReadinessAction.OpenBatterySaverSettings,
            ),
        )
    }
}

private fun teamItem(input: ReadinessInput): ReadinessItem = when (input.team) {
    TeamReadiness.Present -> ReadinessItem(
        ReadinessItemId.Team, ReadinessStatus.Done, "Команда выбрана", input.teamTitle,
        ReadinessAction.ChooseTeam,
    )
    TeamReadiness.Missing -> ReadinessItem(
        ReadinessItemId.Team, ReadinessStatus.Blocked, "Команда не выбрана",
        "Команда не найдена — выберите заново", ReadinessAction.ChooseTeam,
    )
    TeamReadiness.None -> ReadinessItem(
        ReadinessItemId.Team, ReadinessStatus.Blocked, "Команда не выбрана",
        "Выберите соревнование и команду", ReadinessAction.ChooseTeam,
    )
}

private fun chipsItem(input: ReadinessInput): ReadinessItem {
    val id = ReadinessItemId.Chips
    val members = input.memberCount
    val bound = input.boundCount
    return when {
        input.team != TeamReadiness.Present ->
            ReadinessItem(id, ReadinessStatus.Blocked, "Чипы не привязаны", NEED_TEAM, null)
        members == 0 -> ReadinessItem(
            id, ReadinessStatus.Blocked, "Чипы не привязаны", "Состав команды не загружен",
            ReadinessAction.Refresh,
        )
        bound >= members -> ReadinessItem(
            id, ReadinessStatus.Done, "Чипы привязаны", "$members из $members",
            ReadinessAction.BindChips,
        )
        else -> ReadinessItem(
            id, ReadinessStatus.Blocked, "Чипы привязаны не все", "$bound из $members",
            ReadinessAction.BindChips,
        )
    }
}

private fun nfcItem(nfc: NfcState): ReadinessItem = when (nfc) {
    NfcState.Available -> ReadinessItem(
        ReadinessItemId.Nfc, ReadinessStatus.Done, "NFC включён", "Готов к отметке КП", null,
    )
    NfcState.Disabled -> ReadinessItem(
        ReadinessItemId.Nfc, ReadinessStatus.Blocked, "NFC выключен",
        "Включите NFC в настройках телефона", ReadinessAction.OpenNfcSettings,
    )
    NfcState.NoHardware -> ReadinessItem(
        ReadinessItemId.Nfc, ReadinessStatus.Warning, "NFC нет на телефоне",
        "Отмечайте КП через «Фото»", null,
    )
}

private fun locationItem(location: LocationAccess): ReadinessItem = when (location) {
    LocationAccess.Precise -> ReadinessItem(
        ReadinessItemId.Location, ReadinessStatus.Done, "Геолокация", "Точная геопозиция", null,
    )
    LocationAccess.Approximate -> ReadinessItem(
        ReadinessItemId.Location, ReadinessStatus.Warning, "Геолокация", "Примерная геопозиция",
        ReadinessAction.RequestLocation,
    )
    LocationAccess.None -> ReadinessItem(
        ReadinessItemId.Location, ReadinessStatus.Warning, "Геолокация",
        "Нет доступа — отметка без координаты", ReadinessAction.RequestLocation,
    )
}

private fun legendItem(input: ReadinessInput): ReadinessItem = when {
    input.legendCount > 0 -> ReadinessItem(
        ReadinessItemId.Legend, ReadinessStatus.Done, "Легенда загружена",
        "КП в легенде: ${input.legendCount}", null,
    )
    // Refresh without a race is an empty tap — no action until a team (and so a race) is chosen.
    input.team != TeamReadiness.Present -> ReadinessItem(
        ReadinessItemId.Legend, ReadinessStatus.Warning, "Легенда не загружена", NEED_TEAM, null,
    )
    else -> ReadinessItem(
        ReadinessItemId.Legend, ReadinessStatus.Warning, "Легенда не загружена",
        "Обновите данные, пока есть сеть", ReadinessAction.Refresh,
    )
}

private fun mapItem(map: MapAvailability): ReadinessItem? = when (map) {
    MapAvailability.NoMapForRace -> null
    MapAvailability.Ready -> ReadinessItem(
        ReadinessItemId.Map, ReadinessStatus.Done, "Карта скачана", "Доступна без сети",
        ReadinessAction.OpenMap,
    )
    MapAvailability.NotDownloaded, MapAvailability.BusyOtherRace -> ReadinessItem(
        ReadinessItemId.Map, ReadinessStatus.Warning, "Карта не скачана",
        "Скачайте на вкладке «Карта», пока есть сеть", ReadinessAction.OpenMap,
    )
    is MapAvailability.Downloading -> ReadinessItem(
        ReadinessItemId.Map, ReadinessStatus.Warning, "Карта не скачана",
        map.progress?.let { "Скачивается · ${Math.round(it.coerceIn(0f, 1f) * 100)}%" } ?: "Скачивается",
        ReadinessAction.OpenMap,
    )
}

private fun clockItem(clock: ClockStatus): ReadinessItem = when (clock) {
    ClockStatus.Ok -> ReadinessItem(
        ReadinessItemId.Clock, ReadinessStatus.Done, "Часы синхронизированы", "Время подтверждено сервером",
        null,
    )
    ClockStatus.NoSync -> ReadinessItem(
        ReadinessItemId.Clock, ReadinessStatus.Warning, "Часы не синхронизированы",
        "Время не подтверждено — подключитесь к сети", null,
    )
    is ClockStatus.Skewed -> ReadinessItem(
        ReadinessItemId.Clock, ReadinessStatus.Warning, "Часы не синхронизированы",
        "Расходятся с сервером на ${formatSkewMinutes(clock.skewMs)} — проверьте дату и время", null,
    )
}

private fun notificationsItem(granted: Boolean?): ReadinessItem? = when (granted) {
    null -> null
    true -> ReadinessItem(
        ReadinessItemId.Notifications, ReadinessStatus.Done, "Уведомления разрешены",
        "Видно уведомление о записи трека", null,
    )
    false -> ReadinessItem(
        ReadinessItemId.Notifications, ReadinessStatus.Warning, "Уведомления запрещены",
        "Не будет видно уведомления о записи трека", ReadinessAction.RequestNotifications,
    )
}

/** `total` is the list length (hidden rows change it); `worst` = Blocked > Warning > Done. */
fun readinessSummary(items: List<ReadinessItem>): ReadinessSummary {
    val done = items.count { it.status == ReadinessStatus.Done }
    val worst = when {
        items.any { it.status == ReadinessStatus.Blocked } -> ReadinessStatus.Blocked
        items.any { it.status == ReadinessStatus.Warning } -> ReadinessStatus.Warning
        else -> ReadinessStatus.Done
    }
    return ReadinessSummary(done = done, total = items.size, worst = worst, allDone = done == items.size)
}

/**
 * First-render gate: hold the card until marks/team/bindings, the legend count and the map
 * availability have all resolved — otherwise a cold start flashes false «0 из N» / «Легенда не
 * загружена» / a collapsed «Всё готово» that then expands.
 */
fun readinessVisible(marksLoading: Boolean, legendLoaded: Boolean, mapResolved: Boolean): Boolean =
    !marksLoading && legendLoaded && mapResolved
