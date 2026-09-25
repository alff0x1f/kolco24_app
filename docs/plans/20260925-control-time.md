# Контрольное время (КВ) на вкладке «Отметки»

## Overview

Ячейка «ДО КВ» в `MetricsCard` (`ui/marks/MarksScreen.kt:419`) — заглушка `"—"`. Задача — показать в ней
живое состояние контрольного времени (КВ) команды. Это порт iOS-фичи
(`~/src/kolco24_ios/kolco24/docs/plans/completed/20260923-control-time.md`, `docs/IOS-PARITY.md` пункт 1):

- до старта — КВ категории («КВ 8:00»);
- после своей NFC-отметки на КП типа `start` — обратный отсчёт («ДО КВ 3:27»);
- КВ вышло, финиша нет — опоздание красным («ОПОЗДАНИЕ +0:12»);
- после своей отметки на КП типа `finish` — время на дистанции («ВРЕМЯ 7:48», красным при опоздании).

Сервер уже отдаёт `categories[].control_time` (минуты, `0` = не задано) в `GET /app/race/<id>/teams/`.
Android поле игнорирует — его нужно протащить DTO → entity → Room.

**Отличия от iOS:**
- Приложение в проде → миграция v7 **сбрасывает ETag teams** (иначе `304` оставит `controlTime = 0` навсегда).
- Время отметки без `trustedTakenAt` пересчитывается через `TrustedClock.trustedAt(elapsedRealtimeAt, bootCount)`,
  как при выгрузке (`MarkRepository.backfillTrustedMs`) — отсчёт совпадает с серверным и не сдвигается на skew.
- Тик точный: следующий пересчёт — ровно на смене минуты от старта, а не на границе стенной минуты.
- В состоянии `Unknown` подпись «КВ —» (в iOS «До КВ —») — решение пользователя.

**Вне скоупа:** `overtime_penalty` и штраф в баллах; судейские сканы старта/финиша как источник.

## Context (from discovery)

- DTO: `data/api/dto/TeamsResponse.kt` `CategoryDto` (`id, code, short_name, name, order`); тест `TeamsResponseTest`.
- Entity: `data/db/CategoryEntity.kt`; маппинг `data/TeamRepository.kt` `CategoryDto.toEntity` (тест `TeamRepositoryTest`).
- ETag teams: `sync_meta.resource = "race/$raceId/teams"` (`TeamRepository.teamsResource`).
- Room v6: `data/db/AppDatabase.kt`, образец `MIGRATION_5_6` (колонка + `DELETE FROM sync_meta WHERE resource = 'races'`);
  instrumented `MigrationTest.migrate5To6_addsNullMapUrlAndDropsOnlyRacesEtags` — образец теста.
- Отметки: `MarkEntity` (`method`, `checkpointId`, `takenAt`, `trustedTakenAt`, `elapsedRealtimeAt`, `bootCount`).
- Тип КП: `CheckpointEntity.type` ∈ `start|finish|test|kp`.
- Часы: `TrustedClock.sample()` (`trustedMs ?: wallMs` — та же шкала, что `localModeNow` в `MainActivity`),
  `TrustedClock.trustedAt(elapsedAt, bootAt)`.
- Хост: `MainActivity` — `tabCategory` (~стр. 838, категория выбранной команды), `checkpointColors` (~979),
  вызов `MarksScreen` (~1613).
- Метрики: `MarksScreen.MetricsCard` / `MetricItem` (`isWarn` сейчас зашит `true` → `primary` + `RobotoMono`).
- Сервер считает опоздание (`apps/race/results.py`): `duration_min = int(ms/1000/60)`, опоздание если `duration_min > control_time`.

## Development Approach

- **testing approach**: Regular (код, затем тесты в той же задаче)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- инварианты CLAUDE.md: чистые модели без Android и с JVM-тестами; Compose UI не тестируется;
  «duplicate, don't couple»; Room shipped → настоящая миграция + `schemas/7.json`

## Testing Strategy

- **unit tests**: JUnit (JVM), `./gradlew testDebugUnitTest`
- **instrumented**: `MigrationTest` для `MIGRATION_6_7`, `./gradlew connectedDebugAndroidTest` (нужно устройство/эмулятор)
- UI e2e в проекте нет; ячейка проверяется вручную (см. Post-Completion)

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview

Чистая функция `controlTimeState(...)` в `ui/marks/ControlTime.kt` считает состояние по отметкам, типам КП,
КВ и «сейчас». `MainActivity` собирает входы (категория, типы КП, резолвер времени отметки, источник «сейчас»).
Тик живёт в маленькой обёртке `ControlTimeMetrics` вокруг `MetricsCard` (раз в минуту перерисовывается только
карточка метрик, не весь экран). `now` берётся заново **синхронно при смене входов** (`remember(tick, marks,
checkpointTypes, controlMinutes) { nowMs() }`), иначе после взятия старта в нетикающем состоянии `now` был бы
устаревшим и отсчёт показал бы больше КВ до первого тика. `tick` растёт в `LaunchedEffect(kv)` после
`delay(msUntilNextChange(kv))` и при `ON_START` (`LifecycleStartEffect`) — `delay` идёт по `uptimeMillis`
и стоит, пока телефон спит.

Ключевые решения:
- Старт/финиш — только свои отметки команды, `method == "nfc"`, время > 0 (как серверный `_auto_populate_boundary_times`).
- Старт = самая ранняя отметка на КП `start` (полнота состава не важна). Финиш = самая ранняя на `finish` с временем ≥ старта.
- Округление минут вниз везде, как на сервере. Первую минуту после КВ — «ОПОЗДАНИЕ +0:00» красным (штрафа ещё нет).
- Известный край (не лечим): после перезагрузки `trustedMs == null` до синхронизации → «сейчас» по стенным часам,
  а старт может быть в доверенной шкале; при сбитых часах отсчёт сдвинут до первой синхры (виден `ClockWarningBanner`).

## Technical Details

### Данные

- `CategoryDto`: `@SerialName("control_time") val controlTime: Int? = null`.
- `CategoryEntity`: `@ColumnInfo(defaultValue = "0") val controlTime: Int = 0`.
- `toEntity`: `controlTime = controlTime ?: 0`.
- `MIGRATION_6_7`:
  ```sql
  ALTER TABLE categories ADD COLUMN controlTime INTEGER NOT NULL DEFAULT 0
  DELETE FROM sync_meta WHERE resource LIKE 'race/%/teams'
  ```
  Удаляет ETag teams всех гонок и обоих origin (cloud + LAN). Остальные ETag не трогает.

### Ядро (`ui/marks/ControlTime.kt`)

```kotlin
sealed interface ControlTimeState {
    data object Unknown : ControlTimeState
    data class NotStarted(val limitMs: Long) : ControlTimeState
    data class Running(val remainingMs: Long) : ControlTimeState
    data class Overtime(val overMs: Long) : ControlTimeState
    data class Finished(val elapsedMs: Long, val overMs: Long?) : ControlTimeState
}

fun controlTimeState(
    marks: List<MarkEntity>,
    checkpointTypes: Map<Int, String>,
    controlMinutes: Int,
    nowMs: Long,
    timeOf: (MarkEntity) -> Long,
): ControlTimeState
```

Алгоритм:
1. Фильтр: `method == "nfc"` и `timeOf(m) > 0`.
2. `start = min timeOf` среди отметок с типом `start`; `finish = min timeOf` среди `finish` с `t >= start`.
3. `limitMs = controlMinutes * 60_000L`.
4. Есть `start` и `finish`: `elapsed = finish − start`; `overMs = if (controlMinutes > 0 && elapsed / 60_000 > controlMinutes) elapsed − limitMs else null` → `Finished`.
5. `controlMinutes <= 0` → `Unknown`.
6. Нет `start` → `NotStarted(limitMs)`.
7. `elapsed = nowMs − start`; `elapsed < limitMs` → `Running(limitMs − elapsed)`, иначе `Overtime(elapsed − limitMs)`.

Без легенды типы неизвестны → старта нет → `NotStarted` / `Unknown`.

Вспомогательные:
- `resolveMarkTime(m, trustedAt: (Long, Int?) -> Long?): Long` — `trustedTakenAt ?: elapsedRealtimeAt?.let { trustedAt(it, bootCount) } ?: takenAt`.
  Копия логики `MarkRepository.backfillTrustedMs` (дублируем, не импортируем).
- `formatHoursMinutes(ms: Long): String` — `Ч:ММ`, минуты вниз.
- `msUntilNextChange(state): Long?` — `Running(r)` → `r % 60_000 + 1`; `Overtime(o)` → `60_000 − o % 60_000`; прочие → `null`.
- `controlTimeLabel(state): ControlTimeLabel(label, value, isError)`:

| Состояние | Подпись | Значение | isError |
|---|---|---|---|
| Unknown | КВ | — | false |
| NotStarted | КВ | 8:00 | false |
| Running | ДО КВ | 3:27 | false |
| Overtime | ОПОЗДАНИЕ | +0:12 | true |
| Finished, `overMs == null` | ВРЕМЯ | 7:48 | false |
| Finished, `overMs != null` | ВРЕМЯ | 8:12 | true |

### Связка

- `MainActivity`:
  - `checkpointTypes = remember(safeCheckpoints) { safeCheckpoints.associate { it.id to it.type } }`
  - `controlMinutes = tabCategory?.controlTime ?: 0`
  - `markTime = { m -> resolveMarkTime(m, trustedClock::trustedAt) }`
  - `nowMs = { trustedClock.sample().let { it.trustedMs ?: it.wallMs } }`
- `MarksScreen` → private `ControlTimeMetrics(...)` вокруг `MetricsCard` в item `"metrics"`:
  ```kotlin
  var tick by remember { mutableIntStateOf(0) }
  LifecycleStartEffect(Unit) { tick++; onStopOrDispose {} }
  val now = remember(tick, marks, checkpointTypes, controlMinutes) { nowMs() }
  val kv = controlTimeState(marks, checkpointTypes, controlMinutes, now, markTime)
  LaunchedEffect(kv) { msUntilNextChange(kv)?.let { delay(it); tick++ } }
  ```
- `MetricItem`: новый `isError: Boolean = false` → `colorScheme.error`; ячейка КВ — `RobotoMono` всегда, обычный цвет `onSurface`.

## What Goes Where

- **Implementation Steps**: код, тесты, документация в этом репо.
- **Post-Completion**: ручная проверка на устройстве, настройка КВ в админке сервера.

## Implementation Steps

### Task 1: Протащить `control_time` в DTO, entity и Room v7

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/TeamsResponse.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/CategoryEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/TeamRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/7.json` (генерирует KSP)
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/TeamsResponseTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/TeamRepositoryTest.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [x] `CategoryDto`: `@SerialName("control_time") val controlTime: Int? = null`
- [x] `CategoryEntity`: `@ColumnInfo(defaultValue = "0") val controlTime: Int = 0` + KDoc (минуты, `0` = не задано)
- [x] `TeamRepository.toEntity`: `controlTime = controlTime ?: 0`
- [x] `AppDatabase`: `version = 7`, `MIGRATION_6_7` (колонка + сброс ETag `race/%/teams`) с KDoc по образцу `MIGRATION_5_6`; добавить в `.addMigrations(...)`. В KDoc отметить: default и в DDL, и в `@ColumnInfo` (в отличие от v3/v4) — fresh install и апгрейд дают одинаковую схему
- [x] собрать, закоммитить сгенерированный `schemas/.../7.json` (проверить путь по существующим 1–6)
- [x] `TeamsResponseTest`: `control_time: 480` → `480`; без ключа → `null`
- [x] `TeamRepositoryTest`: `control_time` → `CategoryEntity.controlTime`; без ключа → `0`
- [x] `MigrationTest.migrate6To7_...` (по образцу `migrate5To6_...`): категория из v6 выживает с `controlTime == 0`; ETag `race/1/teams` и `race/2/teams` (оба origin) удалены; `races`, `race/1/legend`, `race/1/member_tags`, `race/1/member_tags/synced` на месте
- [x] run `./gradlew testDebugUnitTest` — must pass before task 2 (`connectedDebugAndroidTest`, если есть устройство) — unit tests pass; ⚠️ `connectedDebugAndroidTest` skipped (no device/emulator attached; androidTest compiles)

### Task 2: Чистая логика `ControlTime.kt`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/marks/ControlTime.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/marks/ControlTimeTest.kt`

- [ ] `ControlTimeState` и `controlTimeState(...)` по алгоритму из Technical Details
- [ ] `resolveMarkTime`, `formatHoursMinutes`, `msUntilNextChange`, `ControlTimeLabel` + `controlTimeLabel`
- [ ] тесты `resolveMarkTime`: `trustedTakenAt` побеждает; без него — `trustedAt(elapsed, boot)`; `trustedAt == null` или `elapsedRealtimeAt == null` → `takenAt`
- [ ] тесты состояний: `Unknown` (`controlMinutes == 0`), `NotStarted`, `Running`, `Overtime`, `Finished` с опозданием и без
- [ ] тесты правил: две отметки старта → ранняя; финиш раньше старта игнорируется; финиш без старта → `NotStarted`; фото-отметка на `start` не старт; `timeOf == 0` игнорируется; без легенды → `NotStarted` / `Unknown`
- [ ] тесты округления: опоздание 59 с на финише → `overMs == null`; 60 с → не `null`; `Finished` при `controlMinutes == 0` → `overMs == null`
- [ ] тесты `formatHoursMinutes`: `0` → `0:00`, `59_999` → `0:00`, 8 ч → `8:00`, 3 ч 27 мин 59 с → `3:27`
- [ ] тесты `msUntilNextChange`: `Running(60_000)` → `1`, `Running(90_000)` → `30_001`, `Overtime(0)` → `60_000`, `Overtime(61_000)` → `59_000`, прочие → `null`
- [ ] тесты `controlTimeLabel` — все строки таблицы
- [ ] run `./gradlew testDebugUnitTest` — must pass before task 3

### Task 3: Ячейка КВ в `MarksScreen` и связка в `MainActivity`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] `MarksScreen`: параметры `checkpointTypes`, `controlMinutes`, `markTime`, `nowMs` с дефолтами; private `ControlTimeMetrics` вокруг `MetricsCard` — `tick`, `remember(tick, marks, checkpointTypes, controlMinutes) { nowMs() }`, `LifecycleStartEffect`, `LaunchedEffect(kv)` с точным тиком
- [ ] `MetricsCard`: вместо `timeToKv: String` — `ControlTimeLabel`; убрать комментарий-заглушку
- [ ] `MetricItem`: `isWarn` используется только ячейкой КВ — заменить на `mono: Boolean` + `isError: Boolean` (→ `colorScheme.error`); обычный цвет `onSurface`, не `primary`
- [ ] `MainActivity`: `checkpointTypes`, `controlMinutes`, `markTime` (`resolveMarkTime(m, trustedClock::trustedAt)`), `nowMs` — передать в `MarksScreen`
- [ ] логика покрыта тестами задачи 2 (Compose UI не тестируется по конвенции); `./gradlew assembleDebug` проходит
- [ ] run `./gradlew testDebugUnitTest` — must pass before task 4

### Task 4: Verify acceptance criteria

- [ ] все состояния из Overview реализованы, подписи по таблице
- [ ] `ControlTime.kt` без `android.*` импортов
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew lintDebug`
- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew connectedDebugAndroidTest`, если подключено устройство/эмулятор (иначе явно отметить ⚠️ пропуск)

### Task 5: [Final] Update documentation

- [ ] `docs/design/UI-NOTES.md`: `MarksScreen` — ячейка КВ, точный тик, `LifecycleStartEffect` (uptime стоит во сне), известный край после перезагрузки; пункт про `ControlTime.kt`
- [ ] `docs/design/DATA-NOTES.md`: `CategoryEntity.controlTime`, `MIGRATION_6_7` + сброс ETag teams
- [ ] `CLAUDE.md`: Room **v7**, `MIGRATION_6_7` в строке `connectedDebugAndroidTest`, `ControlTime` в списке чистых моделей
- [ ] `docs/IOS-PARITY.md`: удалить пункт 1, перенумеровать остальные
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

**Manual verification:**
- апгрейд поверх v6 без переустановки: после синка teams `control_time` подтягивается (ETag сброшен)
- выставить категории `control_time` в админке сервера, проверить «КВ 8:00» до старта
- взять КП-старт → «ДО КВ» с отсчётом; смена цифры ровно на минуте от старта
- экран «Отметки» открыт давно (> минуты), взять КП-старт → сразу «ДО КВ» ≤ КВ (не больше)
- с маленьким КВ (1–2 мин) дождаться «ОПОЗДАНИЕ +0:00» красным, затем «+0:01»
- заблокировать экран на несколько минут → при разблокировке значение сразу актуально
- взять КП-финиш → «ВРЕМЯ» (красным, если опоздание ≥ 1 мин)
- светлая и тёмная темы
