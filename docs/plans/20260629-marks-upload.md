# Marks Upload — выгрузка взятых КП на сервер

## Overview

Клиентская отправка отметок взятий КП (`marks`) на бэкенд — зеркало уже
работающей выгрузки GPS-трека (`TrackRepository`/`TrackDao`/`TrackDtos`). Сейчас
взятия КП пишутся в Room как **local-only** (`MarkEntity` с флагами-заделами
`uploadedLocal`/`uploadedCloud`, которые никогда не сбрасываются в 1, потому что
upload-запросов и репозиторного цикла выгрузки ещё нет). Эта задача добавляет
дуальную (cloud + local LAN) идемпотентную батч-выгрузку по контракту
`POST /app/race/<race_id>/marks/` из `docs/design/UPLOAD.md`.

**Что решает:** организаторы видят взятия команд почти в реальном времени; данные
переживают офлайн (флаги остаются 0, дослыка оппортунистическая); device-provenance
(`source_install_id` + физическая идентичность чипов) позволяет серверу дедупить
два телефона одной команды и разбирать «чужую команду» пост-фактум.

**Интеграция:** переиспользует существующую POST-инфраструктуру (`ApiClient.post`,
подпись/конверт, `PostResult`), типы `TrackScope`/`UploadCounts`/`UploadTarget`/
`UploadResultKind`/`TargetUploadOutcome`, и точь-в-точь повторяет цикл выгрузки
`TrackRepository`.

> ⚠️ **Backend НЕ готов.** Эндпоинта `/marks/` на сервере пока нет. Клиент строится
> как трек: пишем локально, флаги остаются 0, оппортунистически досылаем — само
> починится, когда эндпоинт появится. Живого интеграционного теста нет, только
> MockWebServer-юниты. **Перечитать `docs/design/UPLOAD.md` перед реализацией** —
> тело запроса, поля `present[]`, коды ответов и краевые случаи там зафиксированы.

## Context (from discovery)

**Files/components involved:**
- Образец для зеркалирования: `data/track/TrackRepository.kt`, `data/db/TrackDao.kt`,
  `data/api/dto/TrackDtos.kt`, `ui/track/TrackCard.kt` (`UploadStatusRow`).
- Изменяемые: `data/db/MarkEntity.kt`, `data/db/MarkDao.kt`, `data/MarkRepository.kt`,
  `data/db/AppDatabase.kt`, `data/api/ApiClient.kt`, `AppContainer.kt`,
  `Kolco24App.kt`, `MainActivity.kt`, `TrackRecordingService.kt`,
  `ui/marks/MarksScreen.kt`.
- Новые: `data/api/dto/MarkDtos.kt`, `data/db/MarkMemberSnapshotListConverter.kt`,
  миграция (в `AppDatabase.kt` или отдельный файл миграций).

**Related patterns found:**
- `IntListConverter` — образец JSON-`@TypeConverter` (`List<Int>` ↔ строка).
- `TrackDao`: `unuploadedLocal/Cloud(raceId, teamId, limit)`, `markUploaded*`,
  `pendingUploadScopes(): List<TrackScope>`, `uploadCounts(teamId, raceId): Flow<UploadCounts>`.
- `TrackRepository`: `MarkUploader`-аналог `TrackUploader`, `uploadPending`/
  `uploadAllPending`/`flushScope`/`uploadLoop` (Mutex.tryLock, batch=500, метим
  только `accepted ∩ batch`, репорт `onUploadOutcome`).
- `MarkDao.addMember` — `@Transaction` read-modify-write с set-семантикой по
  `numberInTeam`; образец для записи `presentDetails`.

**Dependencies identified:**
- `TrackScope(raceId, teamId)` и `UploadCounts(total, local, cloud)` — в `data/db/TrackDao.kt`.
- `UploadTarget`/`UploadResultKind`/`TargetUploadOutcome`/`uploadResultKind` — в
  `data/track/TrackModels.kt`.
- `AppContainer.installId` — источник `source_install_id`.
- `localApiClient` (LAN) и `apiClient` (cloud) — две цели.

**⚠️ Состояние миграций (важно!):** `AppDatabase` сейчас на `version = 1`, БЕЗ
какой-либо migration-инфраструктуры — нет `MIGRATION_*`, нет `.addMigrations(...)`,
нет `MigrationTest`, есть только `fallbackToDestructiveMigrationOnDowngrade`.
Комментарий в коде утверждает «единственный install — dev-устройство», но **memory
проекта говорит обратное: приложение выпущено, изменения схемы требуют НАСТОЯЩИХ
миграций**. Поэтому эта задача вводит **первую реальную upgrade-миграцию (1→2)** и
впервые подключает `.addMigrations(...)` к билдеру. CLAUDE.md про «v1 no-migrations»
устарел — следуем memory.

## Development Approach

- **testing approach**: **Regular** (код, затем тесты) — соответствует конвенции
  репозитория: чистое/логику покрываем JVM-тестами, Compose-UI и тонкие
  Android-адаптеры — нет. Где естественно (чистые мапперы/конвертеры) пишем тест
  сразу после кода в той же задаче.
- complete each task fully before moving to the next
- make small, focused changes
- **каждая задача с изменением кода ОБЯЗАНА содержать новые/обновлённые тесты**
  (success + error/edge), кроме задач, явно помеченных как «без юнит-тестов по
  конвенции» (Compose/проводка/реальные адаптеры) — там тест-чекбокс заменяется на
  ручную проверку сборки/линта.
- **все тесты должны проходить перед началом следующей задачи**
- **обновлять этот план при изменении скоупа в ходе реализации**
- run tests after each change; maintain backward compatibility

## Testing Strategy

- **unit tests (JVM)**: мапперы DTO, JSON-конвертер, репозиторный upload-цикл с
  фейковыми DAO/uploader'ами, `ApiClient.uploadMarks` против `MockWebServer`,
  запись `presentDetails` в `MarkRepository`.
- **instrumented tests**: `MigrationTest` (1→2) — Robolectric не на classpath, как
  и `CheckpointDaoTest`; гоняется через `./gradlew connectedDebugAndroidTest`
  (нужен эмулятор/устройство). Опционально — scope-изоляция `unuploaded*`.
- **e2e**: в проекте нет UI-e2e — не применимо.
- **НЕ тестируем** (конвенция): `MarksScreen` статус-строку, проводку в
  `MainActivity`/`TrackRecordingService`/`AppContainer`, реальные uploader-лямбды.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- keep plan in sync with actual work done

## Solution Overview

**Архитектура — точное зеркало трека**, с двумя смысловыми отличиями:

1. **`present[]` через снимок на момент взятия** (аддитивная nullable колонка).
   Контракт требует на каждого участника `{nfc_uid, code, number, number_in_team}`,
   а `MarkEntity.present` сейчас только `List<Int>` (numberInTeam). Добавляем
   value-тип `MarkMemberSnapshot` и nullable-колонку `presentDetails`, заполняемую
   на момент скана браслета (когда `classifyTag` вернул `Member` и хост держит `uid`
   + `participantNumber` из привязки). Старая `present: List<Int>` остаётся
   источником истины для скоринга — `reduce`/`isComplete`/`complete` НЕ трогаем.
2. **`source_install_id` обязателен в теле** (у трека в `TrackUploadRequest` его
   нет; у marks — есть по контракту). Берём из `AppContainer.installId`.

**Триггеры выгрузки** (Решение 3):
- при завершении взятия (`MainActivity`, на закрытии scan-оверлея по `isComplete`)
  → `markRepo.uploadPending(raceId, teamId)`;
- Launch B (`Kolco24App`) рядом с `trackRepository.uploadAllPending()` →
  `markRepository.uploadAllPending()`;
- пиггибэк на «живую» throttled-отправку трека (`TrackRecordingService.onPoints`,
  под `shouldLiveUpload`) → после `trackRepository.uploadPending(r,t)` тем же
  `applicationScope.launch` добавить `markRepository.uploadPending(r,t)`.

**UI-статус** (Решение 6): транзиентный `markUploadOutcomes` в `AppContainer` +
`MarkDao.uploadCounts` → джойн в `TrackUploadStatus` в `MainActivity` → строка в
`MarksScreen` (копия `UploadStatusRow`).

## Technical Details

**`MarkMemberSnapshot`** (рядом с `MarkEntity`):
```kotlin
data class MarkMemberSnapshot(
    val numberInTeam: Int,
    val nfcUid: String?,      // uid браслета, прочитанный при скане
    val number: Int,          // глобальный номер участника (из привязки)
    val code: String? = null, // у браслетов code пока нет — задел
)
```

**`MarkEntity`** + `val presentDetails: List<MarkMemberSnapshot>? = null`.

**Тело `POST /app/race/<race_id>/marks/`** (см. UPLOAD.md, поля snake_case):
`team_id`, `source_install_id`, `marks[]`; каждый mark: `id`, `checkpoint_id`,
`method`, `cp_code`, `cp_nfc_uid`, `present[]` (`nfc_uid`/`code`/`number`/
`number_in_team`), `expected_count`, `complete`, `trusted_ms` (nullable),
`wall_ms`, `elapsed_at` (nullable), `boot_count` (nullable). Ответ
`{ "accepted": ["id", ...] }`.

**⚠️ Имена полей `MarkEntity` ≠ имена на проводе — НЕ маппить «1:1» вслепую** (ревью
подтвердило риск):
- `cp_nfc_uid ← entity.cpUid` (UID метки КП), `cp_code ← entity.cpCode` (hex-код).
  Подтверждено: `ScanSession.kt:166-167` `cpUid = uid`, `cpCode = chipCodeHex(code)`;
  `MainActivity.kt:1126-1127` зовёт `startKpTake(cpUid = event.cpUid, cpCode = event.cpCode)`.
- `wall_ms ← entity.takenAt` — **в сущности нет поля `wallMs`!** (в отличие от трека).
- `trusted_ms ← entity.trustedTakenAt`, `elapsed_at ← entity.elapsedRealtimeAt`.

**Выгружаем ВСЕ строки** (и `complete=true`, и `false`) — без фильтра по `complete`
в `unuploaded*`: частичный сбор хранится для серверного лога, полноту сервер
пересчитывает сам из `present[]` против ростера.

**Порядок выгрузки**: `ORDER BY COALESCE(trustedTakenAt, takenAt), id` (как
scoring-порядок в `observeForTeam`, но ASC для стабильной батч-дослыки).

## What Goes Where

- **Implementation Steps** (`[ ]`): все изменения кода/тестов/схемы в этом репозитории.
- **Post-Completion** (без чекбоксов): подъём серверного эндпоинта `/marks/` и
  живая проверка против реального сервера (вне этого репозитория).

## Implementation Steps

### Task 1: Снимок участников — value-тип, колонка, JSON-конвертер

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkMemberSnapshotListConverter.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/db/MarkMemberSnapshotListConverterTest.kt`

- [ ] добавить `data class MarkMemberSnapshot(numberInTeam, nfcUid: String?, number, code: String? = null)` рядом с `MarkEntity` (тот же файл, как value-тип) с KDoc «снимок участника на момент взятия — для выгрузки `present[]`»
- [ ] добавить в `MarkEntity` колонку `val presentDetails: List<MarkMemberSnapshot>? = null` с KDoc: параллельна `present: List<Int>`, заполняется на момент скана, источник для `present[]` в выгрузке, NULL на legacy-строках
- [ ] создать `MarkMemberSnapshotListConverter` (зеркало `IntListConverter`): `@TypeConverter fun fromJson(String?): List<MarkMemberSnapshot>?` / `toJson(List<MarkMemberSnapshot>?): String?` — **nullable** (NULL ↔ null), `ignoreUnknownKeys`, catch `SerializationException`/`IllegalArgumentException` → null
- [ ] написать `MarkMemberSnapshotListConverterTest`: round-trip (полный список с uid/без uid/`code=null`), `null` ↔ null, битый JSON → null, пустой список ↔ `"[]"`
- [ ] run tests — must pass before next task

### Task 2: Регистрация конвертера + миграция 1→2 + schemas/2.json

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create (или в `AppDatabase.kt`): `MIGRATION_1_2`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/2.json` (генерируется сборкой)
- Create: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [ ] зарегистрировать `MarkMemberSnapshotListConverter::class` в `@TypeConverters` на `AppDatabase`
- [ ] поднять `version = 2`
- [ ] добавить `val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db) { db.execSQL("ALTER TABLE marks ADD COLUMN presentDetails TEXT") ; db.execSQL("CREATE INDEX IF NOT EXISTS index_marks_raceId ON marks(raceId)") } }` (колонка nullable без default; индекс под scope-запрос, симметрично `track_points`)
- [ ] добавить `Index("raceId")` в `@Entity(indices=[...])` на `MarkEntity` (чтобы schema 2.json совпала с миграцией — иначе `MigrationTest` validate упадёт)
- [ ] подключить `.addMigrations(MIGRATION_1_2)` в `AppDatabase.build(...)` (первое реальное подключение миграций; `fallbackToDestructiveMigrationOnDowngrade` оставить)
- [ ] обновить baseline-комментарий в `AppDatabase.companion` (миграции снова живые; сослаться на memory «приложение выпущено»)
- [ ] собрать, чтобы Room сгенерировал `schemas/2.json`; закоммитить его
- [ ] написать `MigrationTest` (инструментальный, `MigrationTestHelper`): создать v1-БД со строкой `marks`, выполнить `MIGRATION_1_2`, проверить что `presentDetails IS NULL` у старой строки и индекс `index_marks_raceId` существует, миграция не падает
- [ ] run instrumented test (`./gradlew connectedDebugAndroidTest`) — должен пройти на эмуляторе/устройстве (отметить как требующий девайса, если CI без него)

### Task 3: Запись presentDetails в MarkDao/MarkRepository (две фазы)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt`

- [ ] `MarkRepository.startKpTake`: сменить `bufferedMembers: Set<Int>` → `bufferedMembers: Set<MarkMemberSnapshot>` (или принять оба: `Set<Int>` для `present` + `List<MarkMemberSnapshot>` снимков; **выбрать снимок-несущий тип**, т.к. из него выводится и `present`, и `presentDetails`) и писать `presentDetails = снимки.distinctBy { numberInTeam }`, `present = снимки.map { numberInTeam }`
- [ ] `MarkDao.addMember`: добавить параметр снимка (`numberInTeam, nfcUid, number, code`) и в той же `@Transaction` дописывать `presentDetails` с set-семантикой по `numberInTeam` (если уже есть — no-op, как у `present`); `present` логика без изменений. **Гард на `presentDetails == null`** (legacy/seed-строка с заполненным `present`, но NULL `presentDetails`): начинать новый список из `(snapshot)` вместо NPE. Возможная дивергенция `present`↔`presentDetails` на legacy-строках безвредна — `present` остаётся истиной скоринга
- [ ] `MarkRepository.addMember`: пробросить снимок в `MarkDao.addMember`
- [ ] следить, чтобы скоринг (`complete`/`expectedCount`) считался по `present` как раньше — `presentDetails` на него не влияет
- [ ] расширить `MarkRepositoryTest`: `startKpTake` пишет `presentDetails` из буфера (дедуп по numberInTeam); `addMember` добавляет снимок с set-семантикой (повтор — no-op); `present` и `complete` не регрессируют; снимок с `nfcUid=null` хранится корректно
- [ ] run tests — must pass before next task

### Task 4: DTO marks + чистый маппер MarkEntity.toDto()

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/MarkDtos.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/MarkDtoMappingTest.kt`

- [ ] создать `MarkUploadRequest(team_id, source_install_id, marks: List<MarkDto>)`, `MarkDto(...)`, `PresentMemberDto(nfc_uid: String?, code: String?, number, number_in_team)`, `MarkUploadResponse(accepted: List<String>)` — `@Serializable`, snake_case `@SerialName`, точно по UPLOAD.md
- [ ] написать чистый `fun MarkEntity.toDto(): MarkDto`: `present[]` из `presentDetails` (маппинг снимка → `PresentMemberDto`); **fallback при `presentDetails == null`** — собрать `present[]` из `present: List<Int>` с `nfc_uid=null`, `code=null`, `number=0` (**документировать `0` как sentinel «номер неизвестен на legacy-строке»**, не реальный номер), `number_in_team=<int>`
- [ ] **точные маппинги переименованных полей** (в `MarkEntity` имена ≠ имена на проводе — НЕ «1:1», свериться по `MarkEntity.kt`): `cp_nfc_uid ← cpUid`, `cp_code ← cpCode` (подтверждено ревью: `ScanSession.kt:166-167` `cpUid = uid` КП-метки, `cpCode = chipCodeHex(code)`); `wall_ms ← takenAt` (в сущности **нет** поля `wallMs`!); `trusted_ms ← trustedTakenAt`; `elapsed_at ← elapsedRealtimeAt`; `boot_count`/`method`/`complete`/`expected_count`/`checkpoint_id` — по одноимённым полям
- [ ] написать `MarkDtoMappingTest`: полный снимок → `present[]` с uid/number; fallback при `presentDetails=null` (uid/code/number = null/null/0); nullable `trusted_ms`/`elapsed_at`/`boot_count` = null проходят; `cp_code`/`cp_nfc_uid` берутся из правильных полей; времена 1:1
- [ ] run tests — must pass before next task

### Task 5: ApiClient.uploadMarks

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify/Create: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt` (или новый `ApiClientMarksTest.kt`)

- [ ] добавить `suspend fun uploadMarks(raceId: Int, teamId: Int, sourceInstallId: String, marks: List<MarkDto>): PostResult<MarkUploadResponse>` через приватный `post(url, bodyBytes, parse)`, `POST /app/race/<raceId>/marks/`; сериализовать `MarkUploadRequest(teamId, sourceInstallId, marks)` в байты один раз; парсить только на `200/201`. **NB:** `source_install_id` в теле — без прецедента у трека (`TrackUploadRequest` его НЕ несёт, хотя UPLOAD.md его и для `/track/` предписывает), так что это первое тело с этим полем — копировать неоткуда, добавляем в конверт заново
- [ ] написать тесты против `MockWebServer`: `200 → Success(accepted)`; каждый код `400/403/404/429 → BadRequest/Forbidden/Error(404)/RateLimited`; `Offline` (IOException); тело содержит `source_install_id` и snake_case-поля; пустой `marks` → валидное тело + `200 { accepted: [] }`
- [ ] run tests — must pass before next task

### Task 6: MarkRepository — цикл выгрузки (зеркало TrackRepository) + MarkDao upload-запросы

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryUploadTest.kt`

- [ ] `MarkDao`: добавить `unuploadedLocal(raceId, teamId, limit): List<MarkEntity>` (`WHERE raceId AND teamId AND uploadedLocal=0 ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT :limit`), `unuploadedCloud(...)` аналогично; `markUploadedLocal(ids)`/`markUploadedCloud(ids)` (`UPDATE marks SET uploaded*=1 WHERE id IN (:ids)`); `pendingUploadScopes(): List<TrackScope>` (`SELECT DISTINCT raceId, teamId FROM marks WHERE uploadedLocal=0 OR uploadedCloud=0`); `uploadCounts(teamId, raceId): Flow<UploadCounts>` (зеркало `TrackDao.uploadCounts`, те же алиасы `total/local/cloud`)
- [ ] добавить `fun interface MarkUploader { suspend fun upload(raceId, teamId, sourceInstallId, marks: List<MarkDto>): PostResult<MarkUploadResponse> }`
- [ ] расширить конструктор `MarkRepository`: `+ sourceInstallId: String`, `cloudUploader: MarkUploader = MarkUploader { _,_,_,_ -> PostResult.Offline }`, `localUploader` аналогично, `onUploadOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _,_,_ -> }` (**БЕЗ** `onScopeCleared`/`deleteForTeam` — YAGNI)
- [ ] добавить `uploadMutex = Mutex()`; `uploadPending(raceId, teamId)` (tryLock → flushScope); `uploadAllPending()` (tryLock → обход `pendingUploadScopes()`); `uploadCounts` passthrough
- [ ] добавить приватные `flushScope`/`uploadLoop` — **байт-в-байт** как в `TrackRepository`, только `upload` пробрасывает `sourceInstallId` и тип `MarkDto`/`MarkUploadResponse`; `UPLOAD_BATCH = 500`; метить только `accepted ∩ batch`; пустой первый fetch → `null`; не-`Success` → `uploadResultKind(result)`; нет прогресса → `Error`
- [ ] написать `MarkRepositoryUploadTest` (копия `TrackRepositoryTest`, фейковые DAO/uploader): дрейн батчами; метятся только `accepted ∩ batch`; частичный `accepted`; пустой `accepted` → стоп; не-`Success` → не метим, возврат Offline/Error; `uploadAllPending` по двум scope; `tryLock` guard (параллельный вход — no-op); `onUploadOutcome` репортит `Ok/Offline/Error/no-progress→Error/no-pending→null`; `sourceInstallId` доходит до uploader
- [ ] run tests — must pass before next task

### Task 7: AppContainer — проводка markRepository + markUploadOutcomes

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] добавить `val markUploadOutcomes = MutableStateFlow<Map<Pair<TrackScope, UploadTarget>, TargetUploadOutcome>>(emptyMap())` (транзиентный, как `trackUploadOutcomes`)
- [ ] перестроить lazy `markRepository`: `sourceInstallId = installId`, `cloudUploader = MarkUploader { r,t,sid,m -> apiClient.uploadMarks(r,t,sid,m) }`, `localUploader = MarkUploader { r,t,sid,m -> localApiClient.uploadMarks(r,t,sid,m) }`, `onUploadOutcome = { scope, target, kind -> markUploadOutcomes.update { it + ((scope to target) to TargetUploadOutcome(kind, System.currentTimeMillis())) } }`
- [ ] проверить отсутствие construction-order цикла (apiClient/installId уже инициализированы к моменту первого обращения к `markRepository`)
- [ ] без юнит-тестов (проводка/DI по конвенции) — проверить сборку и `lintDebug`

### Task 8: Триггеры выгрузки — Launch B, take-complete, пиггибэк на трек

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] `Kolco24App` Launch B: рядом с `trackRepository.uploadAllPending()` добавить `applicationScope.launch { markRepository.uploadAllPending() }`
- [ ] `TrackRecordingService`: получить `markRepository` из `container`; в `onPoints` в том же `applicationScope.launch`, где под `shouldLiveUpload(...)` вызывается `trackRepository.uploadPending(r,t)`, **после** него добавить `markRepository.uploadPending(r,t)` (тот же throttle/scope)
- [ ] `MainActivity`: добавить flush в **`onClose`-лямбду scan-оверлея** (`MainActivity.kt:1188` — единый хук закрытия; нет отдельного «финализатора `ScanTakeState`», оверлей просто пересоздаёт `remember { ScanTakeState() }`). `onClose` срабатывает на ЛЮБОМ закрытии (завершение/истечение окна/ручное/FAB) — это даже лучше: частичные взятия тоже улетят. Внутри: гард на non-null `selectedRaceId`/`selectedTeamId`, затем fire-and-forget `container.applicationScope.launch { markRepo.uploadPending(raceId, teamId) }`
- [ ] без юнит-тестов (проводка/сервис по конвенции) — проверить сборку и `lintDebug`

### Task 9: MarksScreen — параметр uploadStatus + статус-строка

> Сделать **до** Task 10: Task 10 прокидывает `uploadStatus` в `MarksScreen`, поэтому
> параметр должен существовать раньше, иначе сборка падает на стыке задач.

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] добавить параметр `uploadStatus: TrackUploadStatus? = null` в `MarksScreen` (дефолт `null` сохраняет все существующие вызовы/preview компилируемыми)
- [ ] отрендерить статус-строку — копия `UploadStatusRow` из `TrackCard.kt` (тихий `labelMedium`/`onSurfaceVariant`, сворачиваемый `rememberSaveable`, тикер «N мин назад» через `produceState`/`relativeTimeRu`), скрыта при `uploadStatus == null || total == 0`; разместить внизу/вверху сетки плиток, не конкурируя с оранжевыми CTA пустого состояния
- [ ] решить дублирование vs переиспользование `UploadStatusRow`: по умолчанию **дублируем** (как `SwitchTeamRow`/`ChangeTeamRow` в проекте — простая копия, ноль связанности между `ui/track` и `ui/marks`); если копия 1:1 крупная — вынести в `ui/common`. **Дефолт: дублировать.**
- [ ] без юнит-тестов (Compose по конвенции) — проверить визуально/сборкой

### Task 10: MainActivity — снимок участников + сбор статуса + проброс

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] в `ScanTakeState` (или эквивалентном per-window состоянии хоста) накапливать `Map<Int, MarkMemberSnapshot>`: на каждый `Member`-результат `classifyTag` (`onScanTag`, `uid` уже в scope) строить снимок `{ numberInTeam, nfcUid = <uid сканированного браслета>, number = <participantNumber из scanChipNumbers (MainActivity.kt:653)>, code = null }` и класть в map
- [ ] прокидывать буфер снимков в `startKpTake(bufferedMembers = ...)` и одиночный снимок в `addMember(...)` (по сигнатурам из Task 3)
- [ ] собрать `TrackUploadStatus` отметок: `markRepo.uploadCounts(teamId, raceId)` через `produceState<UploadCounts?>(null, selectedTeamId, selectedRaceId)` (reset в null при смене scope-ключа) + `container.markUploadOutcomes.collectAsState()` для `(scope, Local)`/`(scope, Cloud)`; джойн в `TrackUploadStatus` (переиспользуем тип из `ui/track`)
- [ ] прокинуть статус в `MarksScreen(uploadStatus = ...)` (параметр добавлен в Task 9)
- [ ] без юнит-тестов (Compose/проводка по конвенции) — проверить сборку

### Task 11: Verify acceptance criteria

- [ ] проверить, что все требования Overview реализованы: дуальная идемпотентная выгрузка marks, `source_install_id` в теле, снимок `present[]`, 3 триггера, UI-статус
- [ ] проверить краевые случаи: legacy-строка (`presentDetails=null`) выгружается с `nfc_uid=null`; пустой `accepted` останавливает цикл; офлайн оставляет флаги 0; повтор `id` идемпотентен; выгружаются и `complete=false` строки
- [ ] `./gradlew testDebugUnitTest` — все юнит-тесты зелёные
- [ ] `./gradlew lintDebug` — без новых ошибок
- [ ] `./gradlew connectedDebugAndroidTest` — `MigrationTest` зелёный (на эмуляторе/устройстве)
- [ ] `./gradlew assembleDebug` — собирается

### Task 12: [Final] Документация

- [ ] обновить `docs/design/UPLOAD.md`: снять статус «эндпоинтов на бэкенде пока нет / marks local-only» → «клиент реализован, ждёт backend»; зафиксировать `present[]` из снимка + fallback при NULL; **пометить `marks[].elapsed_at` как nullable** (сейчас в списке полей nullable отмечены только `trusted_ms`/`boot_count`, но `MarkEntity.elapsedRealtimeAt` — `Long?`)
- [ ] обновить `CLAUDE.md`: `MarkEntity.presentDetails` + `MarkMemberSnapshot`; upload-запросы `MarkDao`; upload-цикл `MarkRepository` + `MarkUploader`; `MarkDtos.kt`; `ApiClient.uploadMarks`; `markUploadOutcomes`; 3 триггера; статус-строка в `MarksScreen`; **факт первой реальной миграции (v1→v2) и подключения `.addMigrations`**
- [ ] обновить memory `room-released-with-migrations.md`: зафиксировать, что миграция 1→2 заведена (первая реальная)
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion

*Требуют внешних систем — без чекбоксов, информационно.*

**External system updates:**
- **Backend:** поднять `POST /app/race/<race_id>/marks/` по контракту `UPLOAD.md`
  (idempotent upsert по `id`, `accepted[]` в ответе, серверный `verified` =
  `sha256(cp_code)[:16] == bid`, дедуп `DISTINCT checkpoint_id` среди `verified`,
  пересчёт полноты из `present[]` против ростера, разбор «чужой команды»).
- **Local LAN server** (`192.168.1.5`): тот же эндпоинт с той же подписью/`key_id`/
  `secret` (или отключённой проверкой времени), иначе local-upload → `403` и
  `uploadedLocal` молча останется 0.

**Manual verification** (когда backend поднимут):
- живой прогон: взять КП всей командой → отметка улетает в cloud + local, флаги → 1;
- офлайн-сценарий: отметка в офлайне → флаги 0 → дослыка на старте/при записи трека;
- два телефона одной команды: обе отметки сохранены, счёт не задвоен;
- legacy-строки (созданные до миграции) выгружаются без падения с `nfc_uid=null`.
