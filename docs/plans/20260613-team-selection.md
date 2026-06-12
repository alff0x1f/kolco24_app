# Поток выбора команды (экраны 04 / 04b / 04c / 04d)

## Overview
- Полный поток выбора команды: пустое состояние таба «Команда» → выбор соревнования → список зарегистрированных команд (с поиском) → подтверждение в bottom sheet → таб показывает реальный состав из Room.
- Решает: сейчас `TeamScreen` работает на моках, команды выбрать нельзя; без выбранной команды бессмысленны отметки на КП.
- Интеграция: зеркалит существующий паттерн races-sync (HMAC-подписанный `GET` + ETag/304 + Room как single source of truth); навигация — enum-overlay по образцу `showScan`/`ScanScreen`, без Navigation Compose.

## Context (from discovery)
- Дизайн: `docs/design/Kolco24 Android v1 (standalone).html` — React-моки в gzip+base64 внутри `<script type="__bundler/manifest">`; экраны потока — babel-скрипт `4acc8a93-fef5-410d-86b6-eba9b524f4df` (распакован в `/tmp/kolco24_screens_3.jsx`; если файла нет — извлечь заново python-скриптом: json из manifest → base64 → gzip).
- API: `docs/design/API.md` — `GET /app/race/<race_id>/teams/` (categories + teams + members, ETag/304). Подпись запросов уже делает `AppSignatureInterceptor` — для нового эндпоинта ничего менять не нужно.
- Существующий паттерн: `data/RaceRepository.kt` (двухтранзакционный replaceAll→upsert ETag), `data/api/ApiClient.kt` (`FetchResult`), `data/db/AppDatabase.kt` (Room **v1**, `exportSchema = false`, **нет** `fallbackToDestructiveMigration` — миграция обязательна), `data/db/SyncMetaEntity.kt` (composite PK `(origin, resource)` — готов под per-race ресурсы).
- UI-паттерн: `MainActivity.kt` — `Box` вокруг `Scaffold`, оверлеи после Scaffold, `rememberSaveable` + `BackHandler`; `ui/team/TeamScreen.kt` — текущий таб на `MOCK_MEMBERS`.
- Тесты: `app/src/test/.../data/` — `RaceRepositoryTest`, `ApiClientTest` (MockWebServer-стиль смотреть там), `RacesResponseTest`.

## Решения из brainstorm (утверждены)
- Объём: всё из дизайна — поиск, чипы Актуальные/Архив, bottom sheet подтверждения.
- Хранение: всё в Room (teams/categories/selected_team), миграция 1→2.
- Навигация: enum-overlay без библиотеки.
- Номер команды: бэкенд добавил `start_number` (строка, напр. `"201"`; в `docs/design/API.md` пока не задокументирован) — в токене команды показываем его, как в дизайне; фолбэк при null/пустом — монограмма из названия. Поиск — по названию **или** стартовому номеру (подстрока, без регистра).
- Составы (`members`) — JSON-колонка в `teams` через TypeConverter, отдельной таблицы нет (читаются только вместе с командой).

## Development Approach
- **testing approach**: Regular (код, затем тесты в том же таске)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - unit-тесты для новых/изменённых функций, success + error сценарии
  - исключения (решение из brainstorm): DAO (тривиальные запросы) и Compose-UI не тестируем; вся логика из UI выносится в чистые функции и тестируется
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change (`./gradlew testDebugUnitTest`)
- maintain backward compatibility (миграция БД без потери данных)

## Testing Strategy
- **unit tests**: обязательны в каждом таске (см. выше); JVM-тесты в `app/src/test/`
- **e2e tests**: в проекте нет — не заводим; финальная проверка руками на устройстве (см. Post-Completion)

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
- Данные: `ApiClient.fetchTeams(raceId, etag)` → `TeamRepository.refreshTeams(raceId)` → Room (`teams`, `categories`); ETag в `sync_meta(origin, "race/<id>/teams")`. Выбор — однострочная таблица `selected_team`, `Flow` из неё реактивно переключает таб «Команда» между пустым состоянием и составом.
- Навигация: `TeamFlowStep { None, CompPicker, TeamPicker }` + `pickerRaceId` + `confirmTeamId` в `rememberSaveable` в `MainActivity`; оверлеи в том же `Box`, что и `ScanScreen`; `BackHandler` сворачивает поток пошагово; подтверждение — `ModalBottomSheet`.
- Порядок записи при `Success` (как в `RaceRepository`, осознанно): `replaceAllForRace` **затем** upsert ETag, две отдельные транзакции — креш между ними самовосстанавливается на следующем refresh.

## Technical Details

### DTO (`data/api/dto/TeamsResponse.kt`)
По схеме `GET /app/race/<race_id>/teams/` из `docs/design/API.md`:
- `TeamsResponse(race: Int, categories: List<CategoryDto>, teams: List<TeamDto>)`
- `CategoryDto(id: Int, code: String, @SerialName("short_name") shortName: String, name: String, order: Int)`
- `TeamDto(id: Int, teamname: String, @SerialName("start_number") startNumber: String? = null, category2: Int?, ucount: Int, @SerialName("paid_people") paidPeople: Double, @SerialName("start_time") startTime: Long, @SerialName("finish_time") finishTime: Long, members: List<MemberDto>)` — `startNumber` nullable с дефолтом: поле добавлено бэкендом недавно и в API.md не задокументировано, страхуемся от его отсутствия
- `MemberDto(name: String, @SerialName("number_in_team") numberInTeam: Int)`

### FetchResult — обобщение
Текущий `FetchResult` несёт `races: List<RaceDto>`. Обобщить до `FetchResult<out T>`: `Success<T>(data: T, etag: String?)`, `NotModified`, `Forbidden`, `Error(code: Int?)` (объекты — через `FetchResult<Nothing>`). Обновить `fetchRaces(): FetchResult<List<RaceDto>>`, `RaceRepository`, существующие тесты.

### Room v1 → v2 (`Migration(1, 2)` — три `CREATE TABLE`, существующие таблицы не трогаем)
- `categories`: PK `id: Int`; `race_id: Int`, `code`, `short_name`, `name`, `sort_order: Int` (поле `order` — зарезервированное слово SQL, в entity колонка `sort_order`)
- `teams`: PK `id: Int`; `race_id: Int` (`@Index`), `teamname`, `start_number: String?`, `category_id: Int?`, `ucount: Int`, `paid_people: Double`, `start_time: Long`, `finish_time: Long`, `members: List<TeamMemberItem>` через `@TypeConverter` (JSON-строка, kotlinx.serialization)
- `selected_team`: PK `id: Int = 1` (single row); `race_id: Int`, `team_id: Int`
- DAO: `TeamDao` (`observeTeamsForRace`, `observeCategoriesForRace`, `observeTeamById`, `@Transaction replaceAllForRace(raceId, categories, teams)` — delete по race_id + insertAll), `SelectedTeamDao` (`observe(): Flow<SelectedTeamEntity?>`, `upsert`)
- SQL в миграции должен буквально совпадать со схемой, которую сгенерирует Room (NOT NULL, default'ы, имя индекса — Room генерирует `index_teams_race_id`), иначе валидация упадёт на старте. `assembleDebug`/KSP это **не** проверяет — расхождение всплывает только при открытии обновлённой БД в рантайме.
- Гард миграции: включить `exportSchema = true` + `room.schemaLocation` (KSP arg в `build.gradle.kts`, schemas коммитим) и написать instrumented-тест с `MigrationTestHelper` (`androidx.room:room-testing`, `app/src/androidTest/`) — создать БД v1, прогнать `Migration(1,2)`, убедиться что races выжили. Запуск: `./gradlew connectedDebugAndroidTest` (нужен эмулятор/устройство).
- Порядок: schema JSON v1 не существует (экспорт был выключен) → сначала включить экспорт **до** бампа версии и собрать (`assembleDebug`) — KSP сгенерирует `1.json`; затем добавить entities, version 2, миграцию — появится `2.json`. Оба файла закоммитить.

### TeamRepository (`data/TeamRepository.kt`)
- `teamsForRace(raceId): Flow<List<TeamEntity>>`, `categoriesForRace(raceId): Flow<List<CategoryEntity>>`
- `selectedTeam: Flow<SelectedTeamEntity?>`, `observeTeam(teamId): Flow<TeamEntity?>`
- `suspend fun refreshTeams(raceId): RefreshResult` — ETag из `sync_meta(origin, "race/$raceId/teams")`; маппинг результатов 1:1 как в `refreshRaces`; ETag не пишем, если заголовок отсутствует
- `suspend fun selectTeam(raceId, teamId)` — upsert single row

### Чистые UI-функции (тестируемые, `ui/teampicker/TeamPickerLogic.kt`)
- Даты сравниваем **лексикографически как ISO-строки** (`YYYY-MM-DD` сравнимы строково). `java.time.LocalDate` НЕ использовать: API 26+, а minSdk 24 и core library desugaring в проекте не включён — JVM-тесты пройдут, на устройстве упадёт. `today` передаётся параметром (строка `YYYY-MM-DD`).
- `raceStatusPill(race, today): RaceStatusPill` — `effectiveEnd < today` → Завершено; иначе `reg_status`: `open` → Регистрация, `upcoming` → Скоро, `sold_out` → Мест нет (в дизайне пилюли нет — серый стиль как «Скоро»). `effectiveEnd = dateEnd ?: date` (`RaceEntity.dateEnd` nullable — фолбэк на `date`).
- `splitRaces(races, today): (актуальные, архив)` — актуальные: `effectiveEnd >= today`, сортировка как пришла из Room (новые первые); архив — прошедшие
- `filterTeams(teams, query)` — подстрока в `teamname` ИЛИ в `start_number`, без учёта регистра; пустой query → все
- `teamToken(team)` — текст для токена команды: `start_number`, при null/**пустой строке** (Django `default=""`) — `initials(teamname)`
- `displayTeamName(team)` — `teamname`, при пустом (`blank=True` в модели) — «Команда N» из `start_number`, иначе «Команда #id»; используется в списке, шите и hero-карте
- `initials(text, max = 2)` — один общий хелпер: фолбэк-монограмма команды и инициалы участника (первые буквы слов); заменяет inline-код в `MonogramAvatar` (`TeamScreen.kt`)

### Состояние загрузки TeamPicker
- При входе на шаг 2: показать кэш из Room сразу, параллельно `refreshTeams(raceId)` в `applicationScope`/`rememberCoroutineScope`
- кэш пуст + `Offline/HttpError` → заглушка «Не удалось загрузить команды» + кнопка «Повторить»
- кэш есть + ошибка → snackbar «Нет сети, показан сохранённый список»
- `Forbidden` → заглушка «Обновите приложение»
- успех + пустой список → «Пока никто не зарегистрирован»

## What Goes Where
- **Implementation Steps**: код, тесты, обновление CLAUDE.md
- **Post-Completion**: ручной проход потока на устройстве, проверка миграции на установленной сборке

## Implementation Steps

### Task 1: DTO для teams-эндпоинта

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/TeamsResponse.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/TeamsResponseTest.kt`

- [x] создать `@Serializable` DTO `TeamsResponse` / `CategoryDto` / `TeamDto` / `MemberDto` со snake_case `@SerialName` (схема — Technical Details)
- [x] тест: парсинг реального ответа бэкенда (фикстура с `start_number: "201"`) → все поля корректны (включая `paid_people: 2.0` как Double и `startNumber`)
- [x] тест: рукописные фикстуры: `category2: null`, `start_number: ""` (в Django-модели `default=""` — пустая строка, а не null, это основной кейс «номера нет»), JSON **без** `start_number` → `null` (старый формат из API.md), пустые `teams`/`members`, незнакомые поля игнорируются (`ignoreUnknownKeys`)
- [x] run tests - must pass before task 2

### Task 2: Обобщить FetchResult и добавить ApiClient.fetchTeams

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/RaceRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/RaceRepositoryTest.kt`

- [x] обобщить `FetchResult` → `FetchResult<out T>` (`Success<T>(data, etag)`; объекты через `FetchResult<Nothing>`), общий приватный хелпер выполнения условного GET
- [x] адаптировать `fetchRaces(): FetchResult<List<RaceDto>>` и `RaceRepository` (поле `races` → `data`)
- [x] добавить `fetchTeams(raceId: Int, etag: String?): FetchResult<TeamsResponse>` — URL `/app/race/$raceId/teams/`, `If-None-Match` как в `fetchRaces`
- [x] обновить существующие тесты под generic (в `ApiClientTest` обращения `Success.races` → `Success.data`; `RaceRepositoryTest` через MockWebServer — должен скомпилироваться без правок по сути); тест `fetchTeams`: 200 → Success с разобранным телом и ETag, 304/403/500 → соответствующие ветки, IOException → `Error(null)`
- [x] run tests - must pass before task 3

### Task 3: Room v2 — entities, DAO, миграция

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TeamEntity.kt` (+ `CategoryEntity`, `SelectedTeamEntity`, `TeamMemberItem`, конвертер — по файлу на entity, как принято в `db/`)
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TeamDao.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/SelectedTeamDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Modify: `app/build.gradle.kts` (schema export + room-testing)
- Create: `app/src/test/java/ru/kolco24/kolco24/data/db/TeamMembersConverterTest.kt`
- Create: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [x] включить `exportSchema = true` + `room.schemaLocation` (KSP arg) и собрать **до** бампа версии — закоммитить сгенерированный `schemas/.../1.json`
- [x] entities `categories` / `teams` / `selected_team` по Technical Details; `TypeConverter` members ↔ JSON (kotlinx.serialization)
- [x] `TeamDao` (`observeTeamsForRace`, `observeCategoriesForRace`, `observeTeamById`, `@Transaction replaceAllForRace`) и `SelectedTeamDao` (`observe`, `upsert`)
- [x] `AppDatabase`: version 2, новые entities/DAO, `@TypeConverters`, `Migration(1, 2)` с тремя `CREATE TABLE` + `CREATE INDEX index_teams_raceId` (SQL сверена с `2.json`), подключить в `build(...)`; закоммитить `2.json`
- [x] тест конвертера (JVM): round-trip списка участников, пустой список, порядок сохраняется (DAO не тестируем — решение из brainstorm)
- [x] instrumented-тест миграции (`MigrationTestHelper` + `androidx.room:room-testing`) написан (`MigrationTest.kt`): создаёт v1 с races, прогоняет `Migration(1,2)`, валидирует схему и проверяет индекс. Запуск `./gradlew connectedDebugAndroidTest` требует эмулятор/устройство — не автоматизируется в этой среде, проверить вручную (Post-Completion)
- [x] run tests - must pass before task 4 (`testDebugUnitTest` + `lintDebug` + `compileDebugAndroidTestKotlin` зелёные)

  Примечание: индекс назван `index_teams_raceId` (колонка `raceId`, camelCase — Room не сужает в snake_case), а не `index_teams_race_id` как было в черновике Technical Details.

### Task 4: TeamRepository

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/TeamRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/TeamRepositoryTest.kt`

- [x] `TeamRepository` по Technical Details: flow-методы, `refreshTeams(raceId)` (resource `"race/$raceId/teams"`, порядок replaceAll → upsert ETag, ETag не пишем при null), `selectTeam(raceId, teamId)`, маппинг DTO → entity
- [x] подключить в `AppContainer` лениво (`teamRepository: TeamRepository by lazy`), по образцу `raceRepository`
- [x] тесты (фейковые DAO/ApiClient, как в `RaceRepositoryTest`): Success → replaceAllForRace вызван с замапленными entity, затем ETag upsert; Success без ETag → upsert не вызывается
- [x] тесты: NotModified → данные не трогаем; Error(null) → Offline; Error(code) → HttpError; Forbidden → Forbidden; разные raceId → разные ресурсы в sync_meta
- [x] run tests - must pass before task 5

### Task 5: Чистая UI-логика пикеров

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerLogic.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerLogicTest.kt`

- [x] `raceStatusPill(race, today)` и `splitRaces(races, today)` — правила в Technical Details; только лексикографическое сравнение ISO-строк (НЕ `LocalDate` — minSdk 24 без desugaring), фолбэк `dateEnd ?: date`
- [x] `filterTeams(teams, query)` (по названию или `start_number`), `teamToken(team)` (`start_number`, пустая строка = нет номера → фолбэк `initials`), `displayTeamName(team)` (фолбэк для пустого `teamname`), общий хелпер `initials(text, max = 2)` (заменяет inline-логику в `MonogramAvatar` из `TeamScreen`)
- [x] тесты `splitRaces`/`raceStatusPill`: гонка сегодня/завтра/вчера, `date_end` на границе, `dateEnd = null` → фолбэк на `date`, все три `reg_status`
- [x] тесты `filterTeams` (по названию, по номеру, регистр, пустой query, нет совпадений), `teamToken` (номер есть / null / пустая строка), `displayTeamName` (имя есть / пустое + номер / пустое без номера) и `initials` (одно слово, два слова, пустая строка)
- [x] run tests - must pass before task 6

### Task 6: Экран выбора соревнования (04b)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/CompPickerScreen.kt`

- [x] каркас: TopAppBar с back-стрелкой «Соревнование», FilterChips «Актуальные · N» / «Архив · N» (стиль чипов — как в `LegendScreen`), список гонок из `raceRepository.races`
- [x] строка гонки по моку 04b: дата-токен (МЕС/день на `inverseSurface`-градиенте, моноширинный), название, статус-пилюля из `raceStatusPill`, подпись `place`, бейдж «Текущее» если `race.id == selectedTeam?.raceId`, chevron
- [x] подпись под списком «Выберите соревнование — откроется список его команд»; тап по строке → callback `onRaceSelected(raceId)`
- [x] тестов нет (чистый Compose; логика покрыта Task 5) — прогнать `./gradlew lintDebug`
- [x] run tests - must pass before task 7

### Task 7: Экран выбора команды (04c) + bottom sheet (04d)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerScreen.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamSwitchSheet.kt`

- [ ] `TeamPickerScreen(raceId, ...)`: back-bar («Сменить команду» / «Выбор команды» если ничего не выбрано), карточка соревнования с кнопкой «Изменить» (→ `onChangeRace`), поисковая строка (пилюля, `TextField`, placeholder «Название или номер команды»), секция «Зарегистрированные · N», строки команд (токен с `teamToken(team)` — стартовый номер или монограмма, название, бейдж «Текущая», «Категория X · N чел.» — категория из `categoriesForRace`)
- [ ] загрузка: `LaunchedEffect(raceId)` → `refreshTeams` (composition scope ок — это read-refresh, отмена при закрытии безвредна); состояния по Technical Details (заглушка+Повторить / snackbar / «Обновите приложение» / «Пока никто не зарегистрирован»)
- [ ] тап по команде → `onTeamTapped(teamId)`; `TeamSwitchSheet(team, category)` — `ModalBottomSheet`: токен (`teamToken`), название, «Категория X · N человек», поясняющий текст, `PrimaryButton`-стиль «Перейти в команду» (OrangeCta-логика как у FAB), «Отмена»
- [ ] тестов нет (Compose; фильтр покрыт Task 5) — `./gradlew lintDebug`
- [ ] run tests - must pass before task 8

### Task 8: Таб «Команда» на реальных данных + пустое состояние (04)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamEmptyContent.kt`

- [ ] `TeamEmptyContent(onChooseTeam)` по моку 04: иллюстрация (круг `inverseSurface` + пунктирное оранжевое кольцо + красное свечение), «Команда не выбрана», подзаголовок, карточка «почему» (2 строки: NFC-чипы / общий счёт), CTA «Выбрать команду» + подпись «Сначала соревнование, затем команда из списка»
- [ ] `TeamScreen`: принимает `selectedTeam: TeamEntity?`/`category` (state hoisting — collect в MainActivity, как сделано с другими данными) и `onChooseTeam`/`onChangeTeam`; `null` → `TeamEmptyContent`; иначе текущая вёрстка, но: hero-карта = `start_number` (моноширинный, на месте захардкоженного «342»; скрыть при null) + название + «Категория X · N человек», состав из `team.members`, «Изменить» в шапке секции → `onChangeTeam`
- [ ] кейс «команда исчезла с сервера»: selection есть, `observeTeam` вернул null → `TeamEmptyContent` с подписью «Команда больше не зарегистрирована» (selection не трём)
- [ ] чип-статусы участников (привязка NFC) — вне скоупа: пока все «Чип не привязан», `MOCK_MEMBERS` удалить
- [ ] тестов нет (Compose) — `./gradlew lintDebug`
- [ ] run tests - must pass before task 9

### Task 9: Навигация потока в MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] состояние в `Kolco24AppRoot`: `teamFlowStep: TeamFlowStep` (None/CompPicker/TeamPicker), `pickerRaceId: Int?`, `confirmTeamId: Int?` — всё `rememberSaveable` (Kotlin enum — `java.io.Serializable`, кастомный `Saver` НЕ нужен; nullable `Int` сохраняется из коробки)
- [ ] оверлеи в существующем `Box` после `Scaffold` (после `ScanScreen`): `CompPickerScreen` при `CompPicker`, `TeamPickerScreen` при `TeamPicker`; sheet поверх при `confirmTeamId != null`
- [ ] `BackHandler`: приоритет sheet > TeamPicker > CompPicker > scan (несколько `BackHandler` — срабатывает последний зарегистрированный enabled, регистрировать team-flow-хендлер после scan-хендлера; sheet закрывается своим dismiss); «Изменить» в TeamPicker → шаг CompPicker
- [ ] подтверждение: «Перейти в команду» → `teamRepository.selectTeam(...)` в `applicationScope` (запись должна дожить до конца, composition scope отменится при закрытии оверлея) → закрыть sheet и весь поток; «Отмена» → только sheet
- [ ] collect `selectedTeam`/`observeTeam`/категории и прокинуть в `TeamScreen`; CTA пустого состояния → `CompPicker`
- [ ] проверить recreate (поворот): шаг потока и sheet переживают пересоздание
- [ ] тестов нет (Activity/Compose) — `./gradlew lintDebug testDebugUnitTest`
- [ ] run tests - must pass before task 10

### Task 10: Verify acceptance criteria
- [ ] все требования Overview реализованы (4 экрана + данные + сохранение выбора)
- [ ] граничные случаи: офлайн с кэшем/без, пустой список команд, Forbidden, исчезнувшая команда, recreate
- [ ] `./gradlew lintDebug testDebugUnitTest assembleDebug` — все зелёные
- [ ] ручной smoke на устройстве/эмуляторе: пусто → соревнование → команда → подтверждение → таб с составом; повторный заход показывает «Сменить команду» и бейджи «Текущее»/«Текущая»; обновление приложения поверх установленной v1-БД не падает (миграция)

### Task 11: [Final] Update documentation
- [ ] обновить CLAUDE.md: teams-sync, новые таблицы/версия Room, TeamRepository, паттерн team-flow-оверлеев, generic FetchResult
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion
*Только информационно — внешние/ручные действия*

**Manual verification:**
- Полный проход потока на физическом устройстве с реальным бэкендом (ETag/304 на повторных заходах в пикер — проверить по логам)
- Обновление поверх установленной сборки с БД v1 — миграция без креша и без потери списка гонок
- Тёмная тема: пустое состояние, пилюли статусов, bottom sheet

**Отложено (вне скоупа):**
- Привязка NFC-чипов участникам (статусы в составе сейчас заглушены «не привязан»)
- Лиз/блокировка команды (`lease_expires_at` в API всегда `null` — логику не строим, см. API.md)

**External:**
- Попросить бэкенд задокументировать `start_number` в API.md (поле уже приходит, в доке отсутствует)
