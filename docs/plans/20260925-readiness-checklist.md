# Чек-лист готовности к старту (вкладка «Отметки»)

## Overview

Перед стартом команда делает несколько разрозненных шагов: выбирает команду, привязывает чипы, даёт
геодоступ, качает карту, включает NFC. Сейчас пустое состояние «Отметок» (`MarksEmpty`) показывает
**один** следующий шаг, так что проверить «всё ли готово» одним взглядом нельзя.

Задача — Android-порт iOS-фичи (iOS-план: `~/src/kolco24_ios/kolco24/docs/plans/completed/20260918-readiness-checklist.md`,
сводка — `docs/IOS-PARITY.md` п.1). Единый список готовности на вкладке «Отметки», виден пока не взято
ни одного КП (`tiles.isEmpty()`). Заменяет `MarksEmpty` целиком (вместе с `LocationNudge` и `TrackNudge`).

**Состав (порядок фиксирован, без сортировки — прыгающие строки хуже):**

| # | Пункт | Не выполнено | Сигнал |
|---|-------|--------------|--------|
| 1 | Команда выбрана | `Blocked` | `teamState` |
| 2 | Чипы привязаны N/N | `Blocked` | `teamForTab.members` + `bindings` |
| 3 | NFC | `Blocked` (выключен) / `Warning` (нет железа) | `nfcState` |
| 4 | Геолокация (точная) | `Warning` | FINE / COARSE |
| 5 | Легенда загружена | `Warning` | `legendScoringCount` |
| 6 | Карта скачана | `Warning`; скрыт без карты у гонки | `mapAvailabilityNow` |
| 7 | Часы синхронизированы | `Warning` | `trustedClock.status` |
| 8 | Уведомления | `Warning`; скрыт на API < 33 | `POST_NOTIFICATIONS` |
| 9 | Энергосбережение | `Warning`; виден только когда включено | `PowerManager.isPowerSaveMode` |

Отличия от iOS: добавлены NFC и уведомления (андроидная механика), карта берётся из наблюдаемого
`MapRepository.downloaded` (опрос файла не нужен), у энергосбережения есть действие (на Android есть
публичный intent в настройки). Подсказка «Начать трек» (`TrackNudge`) убрана — решение пользователя.

## Context (from discovery)

- `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt` — `MarksScreen(...)` (~318),
  ветка `tiles.isEmpty()` (~461) рендерит `MarksEmpty` (~570), ниже `LocationNudge`, `TrackNudge`,
  `GhostTileRow` — используются только в `MarksEmpty`.
- `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`:
  - `enum class NfcState { NoHardware, Disabled, Available }` (:167);
  - `var locationGranted by mutableStateOf(false)` (:280), обновляется в `onResume` (:339–352);
    также читается `MapScreen` (:1357) — поле остаётся;
  - запрос геодоступа и guard «отказ навсегда → настройки»: `hasRequestedLocation` (`rememberSaveable`,
    :885), `scanPermissionLauncher` (:1004–1020), `onRequestMarkLocation` (:1040–1057),
    `showLocationDeniedDialog` (текст «чтобы записывать трек», :2160);
    `POST_NOTIFICATIONS` сейчас запрашивается только вместе с геолокацией при старте трека (:953);
  - `races` — `collectAsState(initial = emptyList())` (:576), `selectedMapUrl` (:1167) `null`, пока каталог не эмитнул;
  - `legendScoringCount` (:728) — `collectAsState(initial = 0)`, неотличим «не загружено» от «0»;
  - `bindingsListOrNull` / `marksOrNull` / `marksLoading` (:737–755) — `marksLoading` уже покрывает
    загрузку команды, отметок и привязок;
  - `mapAvailabilityNow` (~:1168) — `null`, пока нет гонки или `mapDownloaded` не засеян с диска;
  - вызов `MarksScreen` (:1305–1330); `pagerState.animateScrollToPage(3)` — образец перехода на вкладку;
  - pull-to-refresh вкладки «Команда»: `pullRefresh(..., container.syncCoordinator::refreshAll)` (:1381).
- `app/src/main/java/ru/kolco24/kolco24/ui/map/MapAvailability.kt` — `NoMapForRace` / `NotDownloaded` /
  `BusyOtherRace` / `Downloading(progress: Float?)` / `Ready`.
- `app/src/main/java/ru/kolco24/kolco24/data/time/TrustedClock.kt:84` — `ClockStatus { NoSync, Ok, Skewed(skewMs) }`.
- `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt:76` — `scoringCountForRace` через
  `legendMetaDao.observeForRace(raceId)` (`null`-строка → 0).
- `ui/theme/Color.kt` — amber-токена нет (`ClockWarningBanner` использует `errorContainer`).
  `docs/design/UI-NOTES.md:17` фиксирует «one alert palette … deliberately no third amber hue» для
  notices «Отметок» — чек-лист **сознательно** вводит `WarningAmber` (решение пользователя), UI-NOTES
  обновляется в Task 5 с оговоркой, что правило действует для notices, а чек-лист — исключение.
- `MarksScreen.kt`: `nfc_banner` (:492–496) использует `nfcAvailable`/`nfcDisabled`/`onOpenNfcSettings`;
  `GhostTile` (:838) используется только `GhostTileRow`. Единственный вызов `MarksScreen` — MainActivity.kt:1305.
- Тесты: `app/src/test/java/ru/kolco24/kolco24/ui/marks/` (`MarksMappingTest`, `TileFillTest`).

**Паттерны:** чистые модели Android-free + JVM-тесты; Compose/`MainActivity` не тестируются;
состояние в `MainActivity` (нет ViewModel); null-until-first-emit для гейтов загрузки.

**Зависимости:** новых нет.

## Development Approach

- **testing approach**: Regular (код, затем тесты в той же задаче)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting next task**
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- Ветка от `main`, финал — PR.

## Testing Strategy

- **unit tests**: `ReadinessChecklistTest` (JVM, табличный) — вся логика статусов, скрытия, текстов,
  summary и гейта. Проверяется через `./gradlew testDebugUnitTest`.
- **Compose UI и `MainActivity`** — без тестов по конвенции проекта; гейт — `assembleDebug` + `lintDebug`.
- **e2e**: в проекте нет UI-тестов.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview

**Подход A (выбран):** чистая модель `ui/marks/ReadinessChecklist.kt` + сбор сигналов в `MainActivity`
+ `ReadinessCard` в `MarksScreen`. Отклонены: отдельный держатель `ReadinessSignals` (новый паттерн,
которого в проекте нет) и чтение устройства прямо из `MarksScreen` (запросы разрешений всё равно живут в
`MainActivity` — состояние раздвоилось бы).

**Ключевые решения:**

- **Вся логика — в чистой функции.** Вьюха получает готовый `List<ReadinessItem>?` (`null` = ничего не
  рисовать) и отдаёт наверх `ReadinessAction`. Цвет по статусу — единственное, что решает вьюха.
- **Одно действие `RequestLocation`** для `None` и `Approximate`: `MainActivity` сама решает, диалог или
  настройки (`onRequestMarkLocation`). На Android 12+ повторный запрос FINE+COARSE при выданном COARSE
  показывает диалог «повысить до точной». **Но** текущий колбэк `scanPermissionLauncher` действует только
  при `!granted` (`granted` = FINE || COARSE), поэтому при постоянном отказе в FINE тап был бы пустым —
  колбэк дорабатывается: COARSE есть, FINE нет, `alreadyRequested && !shouldShowRequestPermissionRationale(FINE)`
  → считать постоянным отказом → настройки приложения. Текст диалога — отдельный вариант про точную
  геопозицию («для координаты отметки»), а не «чтобы записывать трек».
- **Опрос в `onResume` + один `BroadcastReceiver`** (`onStart`/`onStop`) на
  `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED` **и** `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED`: оба
  переключаются из шторки, что не вызывает `onPause`/`onResume`. NFC-ветка выносится из `onResume` в
  `refreshNfcState()` (включая `enableReaderMode`) и зовётся из обоих мест. Разрешения меняются только в
  системных диалогах/настройках → `onResume` достаточно.
- **Опрос в `onCreate` до `setContent`** и в `onResume` — первый кадр видит реальные значения, флаг
  «опрос сделан» не нужен.
- **Уведомления честно:** foreground-сервис без `POST_NOTIFICATIONS` не останавливается, просто его
  уведомление не видно. Отсюда detail «Не будет видно уведомления о записи трека», статус `Warning`.
- **Карта — без опроса:** `mapAvailabilityNow` уже наблюдаемый; `null` (seed не прошёл) держит гейт.
- **Гейт первой отрисовки:** `!marksLoading && legendLoaded && mapResolved`. Иначе на холодном старте
  мелькнут ложные «Легенда не загружена» / «Чипы 0 из 4» / свёрнутое «Всё готово», которое затем
  разворачивается строкой «Карта не скачана». `mapResolved` ждёт **и** seed файлов карты, **и** каталог
  гонок (`selectedMapUrl` берётся из `races`). Для легенды нужен `null`-until-first-emit сигнал.
- **Выполненные пункты видимы, приглушены.** Все `Done` → одна зелёная строка «Всё готово к старту».
- **`blocked` только у team / chips / nfc** — без них отметка NFC физически не сработает. NFC без
  железа — `Warning`: есть фото-фолбэк.

## Technical Details

### `ui/marks/ReadinessChecklist.kt`

```kotlin
enum class ReadinessStatus { Done, Warning, Blocked }
enum class ReadinessItemId { Team, Chips, Nfc, Location, Legend, Map, Clock, Notifications, Power }
enum class ReadinessAction {
    ChooseTeam, BindChips, OpenNfcSettings, RequestLocation, Refresh, OpenMap,
    RequestNotifications, OpenBatterySaverSettings,
}
enum class LocationAccess { None, Approximate, Precise }

data class ReadinessItem(
    val id: ReadinessItemId,
    val status: ReadinessStatus,
    val title: String,
    val detail: String,
    val action: ReadinessAction?,
)

enum class TeamReadiness { None, Missing, Present }

data class ReadinessInput(
    val team: TeamReadiness,           // из SelectedTeamState: Present / Missing (удалена на сервере) / иначе None
    val teamTitle: String,             // teamname (+ стартовый номер) из teamForTab; "" без команды
    val memberCount: Int,
    val boundCount: Int,
    val nfc: NfcState,
    val location: LocationAccess,
    val legendCount: Int,
    val map: MapAvailability,          // NoMapForRace → пункт скрыт
    val clock: ClockStatus,
    val notificationsGranted: Boolean?, // null = API < 33 → пункт скрыт
    val powerSaveMode: Boolean,
)

fun readinessItems(input: ReadinessInput): List<ReadinessItem>

data class ReadinessSummary(val done: Int, val total: Int, val worst: ReadinessStatus, val allDone: Boolean)
fun readinessSummary(items: List<ReadinessItem>): ReadinessSummary

fun readinessVisible(marksLoading: Boolean, legendLoaded: Boolean, mapResolved: Boolean): Boolean
```

`NfcState` (top-level enum в `MainActivity.kt`) импортируется напрямую: он компилируется в отдельный
класс, `MainActivity` в JVM-тесте не загружается. `SelectedTeamState` → `TeamReadiness` мапится в
`MainActivity` (модель не зависит от репо-типов).

Правила (всё в `readinessItems`):

| Пункт | `Done` | Иначе | Действие |
|-------|--------|-------|----------|
| Team | `Present`, detail = `teamTitle` | `None` → `Blocked` «Выберите соревнование и команду»; `Missing` → `Blocked` «Команда не найдена — выберите заново» | `ChooseTeam` |
| Chips | `memberCount > 0 && boundCount >= memberCount`, detail «N из N» | `Blocked` «N из M»; team ≠ `Present` → «Сначала выберите команду», без действия; `memberCount == 0` → «Состав команды не загружен» + `Refresh` | `BindChips` |
| Nfc | `Available` | `Disabled` → `Blocked` + `OpenNfcSettings`; `NoHardware` → `Warning` «Отмечайте КП через «Фото»», без действия | см. слева |
| Location | `Precise` | `Warning`: `None` → «Нет доступа — отметка без координаты», `Approximate` → «Примерная геопозиция» | `RequestLocation` |
| Legend | `legendCount > 0` | `Warning`; team ≠ `Present` → «Сначала выберите команду», без действия (Refresh без гонки — пустой тап) | `Refresh` |
| Map | `Ready` | `NotDownloaded`/`BusyOtherRace` → `Warning` «Карта не скачана»; `Downloading(p)` → `Warning` «Скачивается · 42%» (`p == null` → «Скачивается»); `NoMapForRace` → **пункта нет** | `OpenMap` |
| Clock | `Ok` | `Warning`; `NoSync` и `Skewed` — разные detail | нет |
| Notifications | `true` | `false` → `Warning` «Не будет видно уведомления о записи трека»; `null` → **пункта нет** | `RequestNotifications` |
| Power | — | `false` → **пункта нет**; `true` → `Warning` | `OpenBatterySaverSettings` |

Тексты — русские, в стиле существующих строк проекта. Заголовки в утвердительной форме
(«Команда выбрана», «Чипы привязаны»), для невыполненных — отрицательная форма там, где читается лучше
(«NFC выключен», «Карта не скачана»).

`readinessSummary`: `done` = число `Done`, `total = items.size` (не константа — скрытые пункты меняют
длину), `worst` = `Blocked` > `Warning` > `Done`, `allDone = done == total`.

### `MainActivity`

Новые поля рядом с `locationGranted`:

```kotlin
var locationAccess by mutableStateOf(LocationAccess.None)
var notificationsGranted by mutableStateOf<Boolean?>(null)
var powerSaveMode by mutableStateOf(false)
```

- `pollDeviceState()` — FINE → `Precise`, только COARSE → `Approximate`, иначе `None`;
  `locationGranted = locationAccess != None` (поле остаётся — его читают трек, скан и `MapScreen`);
  `notificationsGranted` = `null` на API < 33, иначе `checkSelfPermission(POST_NOTIFICATIONS)`;
  `powerSaveMode = powerManager.isPowerSaveMode`. Зовётся из `onCreate` (до `setContent`) и `onResume`.
  Системный диалог разрешений ставит activity на паузу, так что `onResume` покрывает и возврат из него.
- `refreshNfcState()` — NFC-ветка `onResume` (вычисление `nfcState` + `enableReaderMode`), вынесенная в
  метод; зовётся из `onResume` и из receiver'а.
- Один `BroadcastReceiver` на `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED` и
  `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED`: регистрация в `onStart`, снятие в `onStop`
  (`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` — системные broadcast'ы проходят).
- Доработка `scanPermissionLauncher`: если COARSE выдан, FINE нет, `alreadyRequested` и
  `!shouldShowRequestPermissionRationale(FINE)` → показать диалог-переход в настройки с текстом про
  точную геопозицию (вариант `showLocationDeniedDialog` или параметр текста; текущий «чтобы записывать
  трек» не подходит).
- Новый launcher `RequestPermission(POST_NOTIFICATIONS)` (API 33+) с guard'ом «отказ навсегда» по образцу
  геолокации: флаг `hasRequestedNotifications` (`rememberSaveable`) ставится **и** здесь, **и** в
  `trackPermissionLauncher` (он тоже просит `POST_NOTIFICATIONS`, :953) — иначе первый тап после отказа
  из трека пустой. Постоянный отказ → `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`.
- `legendScoringCount` заменяется nullable-версией (один коллектор, не два):
  ```kotlin
  val legendScoringCountOrNull by remember(selectedRaceId) {
      selectedRaceId?.let { legendRepo.scoringCountForRace(it) } ?: flowOf<Int?>(null)
  }.collectAsState(initial = null)
  val legendScoringCount = legendScoringCountOrNull ?: 0
  val legendLoaded = selectedRaceId == null || legendScoringCountOrNull != null
  ```
  Репо-поток не меняется: отсутствующая строка `legend_meta` → `0` (иначе на никогда не синхронизированной
  легенде гейт не открылся бы). Ветка без гонки — именно `flowOf<Int?>(null)`: `collectAsState` не
  сбрасывается на смене ключа (:733–736), ненулевое значение протекло бы через гейт.
- `mapResolved = selectedRaceId == null || (mapDownloaded != null && races.any { it.id == selectedRaceId })`
  — ждёт и seed файлов, и каталог гонок (`selectedMapUrl` из `races`, `initial = emptyList()`). При
  `mapAvailabilityNow == null` в модель идёт `NoMapForRace` (пункт скрыт).
- `team`: `SelectedTeamState.Present` → `Present`, `Missing` → `Missing`, иначе `None`; `teamTitle` из `teamForTab`.
- Действия:
  - `ChooseTeam` / `BindChips` / `OpenNfcSettings` → существующие лямбды из вызова `MarksScreen`;
  - `RequestLocation` → `onRequestMarkLocation`;
  - `RequestNotifications` → новый launcher;
  - `Refresh` → `pullRefresh({ readinessRefreshing = it }, container.syncCoordinator::refreshAll)` —
    тот же путь, что PTR «Команды» (snackbar на ошибке через `refreshErrorMessage`); тапы игнорируются,
    пока `readinessRefreshing`;
  - `OpenMap` → `pagerState.animateScrollToPage(2)`;
  - `OpenBatterySaverSettings` → `Settings.ACTION_BATTERY_SAVER_SETTINGS`, при
    `ActivityNotFoundException` → `Settings.ACTION_SETTINGS`.

### `MarksScreen` / `ReadinessCard`

```
┌────────────────────────────────────┐
│ ● ГОТОВНОСТЬ К СТАРТУ        5 / 8 │  RobotoMono, labelSmall, letterSpacing
│ ██████████████░░░░░░░              │  LinearProgressIndicator 3dp
├────────────────────────────────────┤
│ ✓  Команда выбрана                 │  onSurface / onSurfaceVariant, done — приглушено
│    Ф-мажор                         │
│ ✕  NFC выключен                  › │  error
│ !  Геолокация                    › │  WarningAmber
│    Примерная геопозиция            │
└────────────────────────────────────┘
```

- `MarksScreen` получает `readiness: List<ReadinessItem>?` + `onReadinessAction: (ReadinessAction) -> Unit`.
  **Удаляются** (используются только вызовом `MarksEmpty`, :463–477): `hasTeam`, `loading`, `memberCount`,
  `boundCount`, `trackRecording`, `locationGranted`, `onChooseTeam`, `onBindChips`, `onStartTrack`,
  `onRequestLocation`. **Остаются**: `nfcAvailable`, `nfcDisabled`, `onOpenNfcSettings` (`nfc_banner`, :492–496).
  В `MainActivity` `onStartTrack` остаётся (его использует `TeamScreen`, :1388).
- Цвета: `Blocked` → `colorScheme.error`, `Warning` → новый `WarningAmber` (light/dark) в `Color.kt`,
  `Done` → `colorScheme.tertiary`.
- Строка с `action` — `clickable` + шеврон `›`; без `action` — просто текст.
- `allDone` → одна строка «Всё готово к старту» + «Приложите телефон к метке КП».

## What Goes Where

- **Implementation Steps**: код, тесты, документация в этом репозитории.
- **Post-Completion**: проверки на живом устройстве (системные диалоги, шторка, power save).

## Implementation Steps

### Task 1: Чистая модель чек-листа

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/marks/ReadinessChecklist.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/marks/ReadinessChecklistTest.kt`

- [x] создать типы `ReadinessStatus`, `ReadinessItemId`, `ReadinessAction`, `LocationAccess`, `TeamReadiness`,
      `ReadinessItem`, `ReadinessInput`, `ReadinessSummary` (без Android-импортов; `NfcState` импортируется из `MainActivity.kt`)
- [x] реализовать `readinessItems(input)` по таблице правил: фиксированный порядок, скрытие Map / Notifications / Power
- [x] реализовать `readinessSummary(items)` и `readinessVisible(marksLoading, legendLoaded, mapResolved)`
- [x] тесты team/chips: `None` → оба `Blocked`, у chips нет действия; `Missing` → Team «Команда не найдена» + `ChooseTeam`, chips без действия; 3 из 4 → «3 из 4» + `BindChips`; все → `Done`; пустой ростер → `Blocked` + `Refresh`
- [x] тесты nfc/location: `Disabled` → `Blocked` + `OpenNfcSettings`; `NoHardware` → `Warning` без действия; `None`/`Approximate` → `Warning` + `RequestLocation` с разными detail; `Precise` → `Done`
- [x] тесты legend/map: `legendCount == 0` → `Warning` + `Refresh`; без команды → без действия; `NoMapForRace` → нет пункта; `NotDownloaded`/`BusyOtherRace` → `Warning` + `OpenMap`; `Downloading(0.42f)` → «42%»; `Downloading(null)` → без процента; `Ready` → `Done`
- [x] тесты clock/notifications/power: `NoSync`/`Skewed` → разные detail; `notificationsGranted == null` → нет пункта, `false` → `Warning` + `RequestNotifications`; `powerSaveMode == false` → нет пункта, `true` → `Warning` + `OpenBatterySaverSettings`
- [x] тесты инвариантов: порядок стабилен при любых статусах; `Blocked` только у Team/Chips/Nfc; summary (`total == items.size`, worst, allDone); гейт `readinessVisible` (каждый из трёх сигналов закрывает)
- [x] `./gradlew testDebugUnitTest` — зелено до Task 2

### Task 2: Сигналы устройства и действия в `MainActivity`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] добавить поля `locationAccess`, `notificationsGranted`, `powerSaveMode` и `pollDeviceState()`; звать из `onCreate` (до `setContent`) и `onResume`; `locationGranted` выводить из того же опроса
- [ ] вынести NFC-ветку `onResume` в `refreshNfcState()`; один `BroadcastReceiver` на `ACTION_POWER_SAVE_MODE_CHANGED` + `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED` в `onStart`/`onStop`
- [ ] доработать `scanPermissionLauncher`: COARSE есть, FINE нет, постоянный отказ в FINE → диалог-переход в настройки с текстом про точную геопозицию
- [ ] добавить launcher `POST_NOTIFICATIONS` (API 33+) с guard «отказ навсегда» → `ACTION_APP_NOTIFICATION_SETTINGS`; флаг `hasRequestedNotifications` ставить и в `trackPermissionLauncher`
- [ ] заменить `legendScoringCount` на `legendScoringCountOrNull` (`flowOf<Int?>(null)`, `initial = null`) + `legendLoaded`; добавить `mapResolved` (seed файлов **и** каталог гонок)
- [ ] собрать `ReadinessInput` в composable хоста (маппинг `SelectedTeamState` → `TeamReadiness`), вычислить `readiness = if (readinessVisible(...)) readinessItems(input) else null`
- [ ] реализовать обработчик `onReadinessAction` для всех восьми действий (`Refresh` — через `pullRefresh` с busy-guard; battery saver — с фоллбэком на `ACTION_SETTINGS`)
- [ ] тестов нет (конвенция: `MainActivity` и Android-адаптеры не тестируются); логика покрыта Task 1
- [ ] `./gradlew assembleDebug` — зелено (новые значения пока не используются `MarksScreen`, сборка проходит сама по себе)

### Task 3: `ReadinessCard` в `MarksScreen`, удаление `MarksEmpty`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/theme/Color.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (вызов `MarksScreen`)

- [ ] добавить `WarningAmber` (light/dark) в `Color.kt`
- [ ] создать `private fun ReadinessCard(items, onAction)`: шапка + счётчик + полоска, строки пунктов, свёрнутое состояние `allDone`
- [ ] заменить `MarksEmpty` на `ReadinessCard` в ветке `tiles.isEmpty()`; `readiness == null` → ничего не рисовать
- [ ] удалить `MarksEmpty`, `LocationNudge`, `TrackNudge`, `GhostTileRow`, `GhostTile`, десять параметров `MarksScreen` (см. Technical Details) и осиротевшие импорты иконок
- [ ] обновить вызов `MarksScreen` в `MainActivity` (`onStartTrack` в хосте остаётся — его использует `TeamScreen`)
- [ ] проверить, что `nfc_banner` при непустой сетке, photo-review и hidden-КП notices не затронуты
- [ ] тестов на Compose нет (конвенция); `./gradlew testDebugUnitTest assembleDebug lintDebug` — зелено до Task 4

### Task 4: Verify acceptance criteria

- [ ] все девять пунктов реализованы, порядок фиксирован
- [ ] Map скрыт на гонке без карты; Notifications скрыт на API < 33; Power виден только при включённом режиме
- [ ] чек-лист исчезает после первого взятия и не мигает на холодном старте (гейт из трёх сигналов, `mapResolved` ждёт каталог гонок)
- [ ] `Blocked` только у Team / Chips / Nfc
- [ ] `grep -rnw "MarksEmpty\|TrackNudge\|LocationNudge\|GhostTileRow\|GhostTile" app/src` — пусто
- [ ] `./gradlew testDebugUnitTest` — зелено
- [ ] `./gradlew lintDebug` — зелено
- [ ] `./gradlew assembleDebug` — зелено

### Task 5: [Final] Update documentation

- [ ] `docs/design/UI-NOTES.md`: секция `MarksScreen` (чек-лист вместо `MarksEmpty`, гейт, `WarningAmber` — исключение из правила «no third amber hue», которое остаётся для notices) + `ReadinessChecklist` + в секции `MainActivity`: `pollDeviceState`, receiver power save/NFC, launcher уведомлений, upgrade до точной геопозиции
- [ ] `CLAUDE.md`: в module map `ui/marks/MarksScreen.kt` упомянуть чек-лист готовности и pure `ReadinessChecklist`; добавить `ReadinessChecklist` в список pure models
- [ ] `docs/IOS-PARITY.md`: убрать п.1 (реализован)
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion

*Требует ручных действий — чекбоксов нет.*

**Ручная проверка на устройстве:**

- свежая установка: тап «Геолокация» показывает системный диалог; после «Примерная» — пункт `Warning`
  «Примерная геопозиция», повторный тап показывает диалог повышения до точной (Android 12+)
- «Примерная» выбрана дважды (FINE отклонён навсегда) → тап открывает диалог-переход в настройки с текстом про точность
- постоянный отказ в геодоступе → тап ведёт в настройки приложения, возврат обновляет пункт
- power save из шторки → пункт появляется/исчезает без ухода с экрана; тап открывает настройки экономии
- NFC из шторки → пункт меняется без ухода с экрана, reader mode включается
- отказ в уведомлениях (Android 13+) → пункт `Warning`; постоянный отказ (в т.ч. через старт трека) → настройки уведомлений
- NFC выключен → `Blocked`, тап → настройки NFC, возврат → `Done`
- гонка с картой: тап → вкладка «Карта»; во время скачивания пункт показывает процент; после — `Done`
- гонка без карты → пункта нет
- холодный старт с привязанной командой: не мелькает «0 из N», «Легенда не загружена» и ложное «Всё готово»
- «Обновить» без сети → snackbar с ошибкой
- визуальная проверка в светлой и тёмной теме

**Отложено:** предупреждение «геолокация выключена в системе» при выданном разрешении
(`showLocationDisabledDialog` уже есть) — не входит в объём.

**Внешние системы:** не затронуты — серверный контракт не меняется.
