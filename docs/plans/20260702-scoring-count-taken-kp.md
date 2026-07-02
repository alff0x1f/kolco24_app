# Зачётные КП в счётчике взятых (scoring_count)

## Overview
- В счётчике взятых КП («ВЗЯТО» на экране Отметки и счёт «$taken/$total КП» в `ScoreCard` экрана Легенда) учитывать только **зачётные** КП — со стоимостью `cost > 0`.
- КП с `cost = 0` — технические (тестовая, зона трансфера) и не должны попадать в счётчик.
- Механизм знаменателя — по аналогии с суммой баллов: сервер отдаёт агрегат `scoring_count` (число КП с `cost > 0`, включая locked, чью стоимость клиент не видит), клиент хранит его в `legend_meta` и читает офлайн. Числитель считается на клиенте по живой стоимости.

## Context (from discovery)
- **DTO**: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt` — уже есть `@SerialName("total_cost") val totalCost: Int = 0`.
- **Room**: `app/src/main/java/ru/kolco24/kolco24/data/db/LegendMetaEntity.kt` (сейчас только `totalCost`); `AppDatabase.kt` version=3, миграции `MIGRATION_1_2`/`MIGRATION_2_3`, `.addMigrations(...)`, `exportSchema=true`; схемы в `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/{1,2,3}.json`; `LegendMetaDao.upsert(entity)` + `observeForRace(raceId): Flow<LegendMetaEntity?>`.
- **Repo**: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt` — `totalCostForRace(raceId): Flow<Int>` (map `it?.totalCost ?: 0`); `refreshLegend` пишет `upsert(LegendMetaEntity(raceId, response.totalCost))`.
- **Числитель**: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt` — `fun takenPointCount(marks): Int` (все complete КП, distinct по checkpointId); рядом `totalScore(marks, costOf)` уже фильтрует по complete/distinct.
- **Отметки**: `MainActivity.kt` (`legendTotalCost` 637-638, `totalKp = safeCheckpoints.size` 1122); `ui/marks/MarksScreen.kt` (~214 `takenKp = takenPointCount(marks)`, рядом собран `costOf = { checkpointCosts[it.checkpointId] ?: it.cost }`).
- **Легенда**: `ui/legend/LegendScreen.kt` — `LegendList` (~114-172) и `ScoreCard`; сейчас `takenCount`/`totalCount` общие для ScoreCard и чипов; `MainActivity` LegendScreen `totalScore = legendTotalCost` (1144).
- **Тесты**: `MarksMappingTest.kt` (юнит `takenPointCount`), `MarkRepositoryTest.kt`, `LegendRepositoryTest.kt` (MockWebServer), `MigrationTest.kt` (androidTest, `runMigrationsAndValidate`).
- Конвенции (CLAUDE.md): отгруженная Room → реальные миграции + `schemas/<n>.json`; чистые модели JVM-тестируются; `./gradlew lintDebug` и `testDebugUnitTest` должны проходить; Compose-проводка не тестируется по конвенции.

## Development Approach
- **testing approach**: Regular (код, затем тесты) — правки к отгруженной БД, тесты по образцу существующих.
- Каждую задачу доводить до конца (код + тесты) перед следующей.
- **Каждая задача с изменением кода включает новые/обновлённые тесты** (кроме Compose-проводки — не тестируется по конвенции проекта).
- **Все тесты проходят до начала следующей задачи.**
- Обновлять этот файл при изменении объёма работ.

## Testing Strategy
- **unit tests**: перегрузка `takenPointCount(marks, costOf)` (JVM), парсинг `scoring_count` в `LegendRepositoryTest` (MockWebServer).
- **instrumented**: `MIGRATION_3_4` в `MigrationTest` (по образцу `MIGRATION_2_3`) + committed `schemas/4.json`.
- **e2e**: в проекте нет UI e2e — не применимо.
- Compose-проводка (`MainActivity`/`MarksScreen`/`LegendScreen`) — без тестов по конвенции.

## Progress Tracking
- Отмечать `[x]` сразу по завершении.
- Новые задачи с префиксом ➕, блокеры с ⚠️.

## Solution Overview
- **Знаменатель** (всего зачётных КП) — серверный `scoring_count`, сохраняемый в `legend_meta` рядом с `total_cost`; читается офлайн. Единственный корректный источник, т.к. locked-КП скрывают `cost`.
- **Числитель** (взято зачётных) — на клиенте по живой стоимости (`costOf > 0`), симметрично тому, как `СУММА` суммирует `costOf`.
- **Легенда (вариант B2)**: технические КП остаются видны в списке и чипах, но `ScoreCard`-счёт «$taken/$total КП» считает только зачётные. Переменные ScoreCard и чипов разделяются.

## Technical Details
- `scoring_count`: `Int`, default 0 (forward-compat, как `total_cost`); в БД `scoringCount INTEGER NOT NULL DEFAULT 0` — без бэкфилла, данные догрузятся при первом refresh легенды. Гейтинг `total > 0` в UI уже скрывает «/total» на холодном старте (когда 0).
- Числитель Отметок: `takenPointCount(marks, costOf)` где `costOf = checkpointCosts[id] ?: it.cost`.
- Легенда: `fun CheckpointEntity.isScoring() = locked || (cost ?: 0) > 0`; `takenScoring = checkpoints.count { it.id in takenIds && it.isScoring() }`; `totalScoring` = проброшенный `scoringCount`. Чипы/список: `totalCount = checkpoints.size`, `takenCount = checkpoints.count { it.id in takenIds }` — без изменений.
- **Известный приемлемый пограничный случай (не чинить, отметить в коде комментарием):** взятый locked-КП по фото до раскрытия имеет `cost = null` → `costOf` вернёт snapshot `0` → в счётчик Отметок не попадёт до раскрытия. В Легенде через `isScoring()` (locked ⇒ зачётный) считается сразу. Небольшая асимметрия между экранами; обе ветки самоисправляются при раскрытии.

## What Goes Where
- **Implementation Steps**: DTO, Room-миграция+схема, repo, числитель, проводка Отметок и Легенды, тесты — всё в этом репозитории.
- **Post-Completion**: серверная реализация поля `scoring_count` в ответе легенды (вне этого репозитория); ручная проверка на устройстве.

## Implementation Steps

### Task 1: Добавить `scoring_count` в DTO легенды

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt`

- [x] добавить в `LegendResponse` поле `@SerialName("scoring_count") val scoringCount: Int = 0`
- [x] обновить KDoc: `scoringCount` — число КП с `cost > 0` (open + locked), для знаменателя счётчика взятых, симметрично `totalCost`; default 0 для forward-compat
- [x] (тесты парсинга — в Task 4 вместе с repo/MockWebServer)

### Task 2: Схема БД v4 — колонка `legend_meta.scoringCount` + миграция

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/LegendMetaEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`

- [x] в `LegendMetaEntity` добавить `val scoringCount: Int` (**без Kotlin-дефолта** — явный аргумент, компилятор ловит забытый call-site; обновить KDoc: серверный `scoring_count`, число зачётных КП)
- [x] в `AppDatabase` бампнуть `version = 4`
- [x] добавить `MIGRATION_3_4 = object : Migration(3, 4) { execSQL("ALTER TABLE legend_meta ADD COLUMN scoringCount INTEGER NOT NULL DEFAULT 0") }` с KDoc (additive, default 0, без бэкфилла — догрузится при первом refresh)
- [x] добавить `MIGRATION_3_4` в `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`
- [x] **НЕ собирать здесь**: `scoringCount` без Kotlin-дефолта → единственный call-site `LegendRepository.kt:126` не компилируется, пока не обновлён в Task 3. Схема `4.json` генерируется в конце Task 3.
- [x] (инструментальный тест миграции — Task 6)

### Task 3: LegendRepository — писать и отдавать `scoringCount` (+ генерация схемы)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/4.json` (генерируется сборкой)

- [x] в `refreshLegend` заменить `upsert(LegendMetaEntity(raceId, response.totalCost))` на `upsert(LegendMetaEntity(raceId, response.totalCost, response.scoringCount))` (чинит call-site из Task 2 → модуль снова компилируется)
- [x] добавить `fun scoringCountForRace(raceId: Int): Flow<Int> = legendMetaDao.observeForRace(raceId).map { it?.scoringCount ?: 0 }` (по аналогии с `totalCostForRace`)
- [x] собрать (`./gradlew assembleDebug`) — теперь компилируется → KSP генерирует `schemas/4.json`, закоммитить его
- [x] (тест upsert `scoringCount` — Task 4)

### Task 4: Тесты слоя данных (DTO parse + repo upsert)

**Files:**
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/LegendRepositoryTest.kt`

- [x] проверить структуру `LegendRepositoryTest` (MockWebServer, как строится тело ответа легенды)
- [x] добавить/дополнить тест: ответ легенды с `"scoring_count": N` → после `refreshLegend` `scoringCountForRace(raceId)` эмитит N
- [x] тест forward-compat: ответ без поля `scoring_count` → `scoringCount = 0` (парсинг не падает, геттер отдаёт 0)
- [x] запустить `./gradlew testDebugUnitTest` — должны проходить до следующей задачи

### Task 5: Числитель — перегрузка `takenPointCount(marks, costOf)`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/marks/MarksMappingTest.kt`

- [x] добавить `fun takenPointCount(marks: List<MarkEntity>, costOf: (MarkEntity) -> Int): Int = marks.filter { it.complete }.distinctBy { it.checkpointId }.count { costOf(it) > 0 }` с KDoc (технические КП с cost 0 не считаются)
- [x] существующий `takenPointCount(marks)` оставить без изменений
- [x] тест: КП с `cost = 0` не считается; КП с `cost > 0` считается
- [x] тест: повторное взятие того же checkpointId не дублирует (distinct)
- [x] тест: incomplete-take не считается
- [x] запустить `./gradlew testDebugUnitTest` — должны проходить до следующей задачи

### Task 6: Инструментальный тест миграции `MIGRATION_3_4`

**Files:**
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [x] добавить тест по образцу `MIGRATION_2_3`: создать БД v3, **вставить seed-строку в `legend_meta` (raceId, totalCost)** (в отличие от существующих `MIGRATION_1_2`/`MIGRATION_2_3` тестов, которые сидят `marks` — здесь мигрируется `legend_meta`), затем `runMigrationsAndValidate(testDb, 4, true, AppDatabase.MIGRATION_3_4)`
- [x] проверить, что legacy-строка `legend_meta` после миграции имеет `scoringCount = 0` (обязательно — зеркалит `migrate2To3` default-zero assertion, `MigrationTest.kt:114-119`)
- [x] manual test (skipped - not automatable: no emulator/adb available in this environment; `./gradlew compileDebugAndroidTestSources` passes, verifying the test compiles against the real schema)

### Task 7: Проводка экрана Отметки

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [x] в `MainActivity` добавить поток `legendScoringCount` по образцу `legendTotalCost` (`selectedRaceId?.let { legendRepo.scoringCountForRace(it) } ?: flowOf(0)`)
- [x] заменить `totalKp = safeCheckpoints.size` на `totalKp = legendScoringCount` (строка ~1122)
- [x] в `MarksScreen` заменить `val takenKp = takenPointCount(marks)` на `takenPointCount(marks, costOf)` (`costOf` уже определён ниже — при необходимости поднять его объявление выше строки использования)
- [x] обновить комментарий про `«ВЗЯТО»`-метрику при необходимости
- [x] сборка (`./gradlew assembleDebug`) — без юнит-тестов (Compose-проводка, по конвенции)

### Task 8: Проводка экрана Легенда (B2)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] добавить `internal fun CheckpointEntity.isScoring(): Boolean = locked || (cost ?: 0) > 0` (в `LegendScreen.kt`)
- [ ] добавить в `LegendScreen`/`LegendList`/`ScoreCard` параметр `scoringCount: Int = 0` (дефолт `= 0` — идиоматично для публичного composable, как прочие defaulted-параметры `LegendScreen.kt:89-90`; проброс `totalScoring` в ScoreCard); в `MainActivity` передать `scoringCount = legendScoringCount` (рядом со строкой 1144, `totalScore` оставить = `legendTotalCost`)
- [ ] в `LegendList` разделить переменные: для `ScoreCard` — `takenScoring = checkpoints.count { it.id in takenIds && it.isScoring() }` и `totalScoring = scoringCount`; чипы/список оставить на `totalCount = checkpoints.size` / `takenCount = checkpoints.count { it.id in takenIds }`
- [ ] `ScoreCard` показывает `«$takenScoring/$totalScoring КП»`; чипы «Все N»/«Не взятые M» и список — без изменений (видят все КП, включая технические)
- [ ] добавить комментарий про пограничный случай (locked считается зачётным в Легенде, в Отметках — по costOf; самоисправляется)
- [ ] сборка (`./gradlew assembleDebug`) — без юнит-тестов (Compose-проводка, по конвенции)

### Task 9: Verify acceptance criteria
- [ ] «ВЗЯТО» (Отметки) = взятые зачётные / серверный `scoring_count`; технические КП (cost 0) не влияют
- [ ] `ScoreCard` (Легенда) = взятые зачётные / `scoring_count`; список и чипы по-прежнему показывают все КП
- [ ] холодный старт (scoring_count=0) не показывает «/0» (гейтинг `total > 0`)
- [ ] `./gradlew lintDebug` — проходит
- [ ] `./gradlew testDebugUnitTest` — проходит
- [ ] `./gradlew connectedDebugAndroidTest` — при доступном эмуляторе (MIGRATION_3_4 и др.)

### Task 10: [Final] Документация
- [ ] обновить CLAUDE.md: `LegendMetaEntity`/`LegendResponse`/`LegendRepository` (добавлен `scoringCount`/`scoring_count`, `scoringCountForRace`); Room v3→v4 + `MIGRATION_3_4` (и упоминание connectedDebugAndroidTest-гарда); описания `MarksScreen`/`LegendScreen` (счётчик считает зачётные КП, B2)
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion
*Требуют внешних действий — без чекбоксов*

**Серверная часть** (вне этого репозитория):
- Реализовать в ответе `GET /app/race/<race_id>/legend/` top-level поле `scoring_count` = число КП с `cost > 0` (включая locked). До появления поля клиент видит `scoring_count = 0` → «/total» в счётчике скрыт, числитель работает; после деплоя знаменатель появляется автоматически.

**Ручная проверка на устройстве:**
- Гонка с техническими КП (cost 0): «ВЗЯТО» и ScoreCard не считают их; список Легенды их показывает.
- Апгрейд с v3 без потери данных (миграция аддитивная).
