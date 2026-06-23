# GPS Track Recording During Race

## Overview
Добавить запись GPS-трека команды во время гонки: батарейно-эффективно (1 точка/мин), в фоне со спящим экраном (foreground service), с ручным стартом/стопом. Точки пишутся локально в Room (источник правды), позже выгружаются батчем на два таргета — облако (HTTPS) и локальный сервер события (cleartext LAN). Просмотр в MVP — только метрики (без карты).

**Проблема, которую решает:** сейчас приложение не фиксирует маршрут команды — нет данных ни для участника (свой трек), ни для судейства/анти-фрода (доказательство пути).

**Критерии приёмки:** (1) запись стартует/стопится вручную; (2) идёт в фоне со спящим экраном; (3) время точки корректно при батчинге (из `elapsedRealtimeNanos`, не из времени доставки), включая pre-anchor точки; (4) метрики (число/длина) видны без карты; (5) двойная выгрузка идемпотентна и независима по таргетам; (6) приложение устанавливается на устройства без GPS-железа.

**Интеграция:** вписывается в существующие паттерны — Room v-bump + миграция + schema JSON + инструментальный `MigrationTest`; ручной DI через `AppContainer`; чистые модели отдельно от Android (как `ScanSession`/`CheckpointColor`); локальные данные с флагами `uploadedLocal`/`uploadedCloud` (как `MarkEntity`); подписанные запросы через `AppSignatureInterceptor`; trusted-время через `TrustedClock`/`TimeSample`.

## Context (from discovery)
- **Стек:** single-activity Jetpack Compose, minSdk 24 / targetSdk 36, Kotlin, Room (KSP), OkHttp + ручная HMAC-подпись, manual DI (`AppContainer`), нет ViewModel/Navigation.
- **Геолокации сейчас НЕТ вообще:** ни permissions, ни play-services-location, ни foreground service, ни WorkManager — фича с нуля.
- **Заложенные крючки в коде:**
  - `MarkEntity` уже имеет `uploadedLocal` + `uploadedCloud` — паттерн двойной выгрузки.
  - `SyncMetaEntity` — `origin` в композитном PK `(origin, resource)`.
  - `TrustedClock` (`data/time/TrustedClock.kt`) с `computeTrusted(state, elapsedNow, bootNow)` и `TimeSample` — но публичный путь считает только «сейчас», нужен расчёт для произвольного `elapsedAt`.
  - `AppContainer` — `applicationScope`, lazy-репозитории, `trustedClock`, `apiClient`, `database`.
  - `ApiClient.post()` + `PostResult<T>` — готовый POST-путь со статус-маппингом (`bindTag` как образец).
  - `AppSignatureInterceptor(tokenProvider, nowSeconds)` + `ServerTimeInterceptor` (single-host `/app/` HTTPS, без host-гейта).
  - Room сейчас **v10**, `schemas/.../10.json`, `MigrationTest`/`MigrationTestHelper` в `app/src/androidTest`.
- **Образцы для копирования:** `data/MarkRepository.kt`, `data/db/` (миграции), `data/NfcUid.kt` (чистый хелпер + тест), `data/nfc/MifareUltralightWriter.kt` (seam-интерфейс `NfcTransport`), `ui/marks/MarksScreen.kt` (метрики/тайлы), bind/unbind-диалог в `MainActivity`.

## Development Approach
- **testing approach**: Regular (код, затем JVM-тесты в той же задаче) — по конвенции репозитория: чистые модели/хелперы покрываются JVM-тестами, адаптеры (Service, реальные location-движки, Compose UI) не тестируются.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: каждая задача с изменением кода ОБЯЗАНА включать новые/обновлённые тесты** — для тестируемых частей (чистые модели, мапперы, DAO-логика, DTO, миграция). Для нетестируемых по конвенции частей (Service, FusedLocationEngine/LegacyLocationEngine реальные адаптеры, Compose) тест не пишется — это явно отмечается в задаче.
- **CRITICAL: все тесты должны проходить перед началом следующей задачи**
- run tests after each change
- maintain backward compatibility (аддитивная миграция, существующие таблицы не трогаем)

## Testing Strategy
- **unit tests (JVM, `testDebugUnitTest`)**: `TrackMetricsTest`, `TrackPointMappingTest`, `LocationEngineFactoryTest`, `TrustedClockTest` (расширение), `TrackUploadTest` (MockWebServer как `ApiClientTest`).
- **instrumented tests (`connectedDebugAndroidTest`)**: `MigrationTest.migrate10To11_*` (требует эмулятор/устройство; guard миграции Room).
- **e2e/UI**: проект UI/Service/реальные движки не тестирует по конвенции — вместо этого граничная логика вынесена в чистые функции и покрыта JVM-тестами.
- **lint**: `./gradlew lintDebug` должен проходить (особенно `NewApi` — minSdk 24; foreground-service-type на 14+).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
**Сбор (вариант A — батчинг):** `FusedLocationEngine` где есть GMS, иначе `LegacyLocationEngine`. Fused: `LocationRequest(PRIORITY_BALANCED_POWER_ACCURACY, interval=60s, minUpdateInterval=60s, maxUpdateDelay=300s)` — точки приезжают пачкой ~раз в 5 мин, GPS-радио спит между сериями. Legacy: `requestLocationUpdates(GPS_PROVIDER, 60s)` без батчинга, fallback на `NETWORK_PROVIDER`.

**Время точки из фикса:** каждый `Location` несёт `time` (`gpsTimeMs` — спутниковый хинт) и `elapsedRealtimeNanos` (монотонный момент фикса). `trustedMs` считается из `elapsedRealtimeNanos` каждой точки через `TrustedClock` (НЕ из времени доставки пачки — иначе при батчинге все точки получат почти одинаковое время). Порядок/длина трека — по `elapsedRealtimeAt`. **`wallMs` тоже привязан к моменту фикса, а не к моменту вставки** (из ревью): при батчинге Fused (пачка ~раз в 5 мин) `System.currentTimeMillis()` на вставке дал бы всем точкам пачки почти одинаковое wall-время доставки. Поэтому `wallMs` back-projected: `wallMs = wallNow + (elapsedAt − elapsedNow)`, где `wallNow`/`elapsedNow` сняты один раз на момент вставки пачки, `elapsedAt` — у каждой точки. Так `wallMs` остаётся честным per-point fallback к `trustedMs` (зеркало `MarkEntity` `trusted ?: wall`), когда нет синка часов.

**Хранение:** Room v11, аддитивная таблица `track_points`, все точки сырьём (фильтр грубых — только на чтении).

**Фон:** `TrackRecordingService` (foreground, type=location), `START_NOT_STICKY`, уведомление с кнопкой «Стоп», состояние через `trackRecordingState: StateFlow<TrackState>` в `AppContainer`.

**Выгрузка (фаза 2, батч не стрим):** на стопе + оппортунистически при запуске; два независимых таргета (cloud HTTPS / local cleartext `192.168.1.5`), идемпотентный upsert по client UUID, флаги `uploadedLocal`/`uploadedCloud`.

## Technical Details

### Ключевые решения и обоснование
- **Foreground service + `START_NOT_STICKY`:** запись в кармане со спящим экраном требует foreground service; не возобновляем молча после kill системой (внезапный расход батареи нежелателен) — перезапуск только из UI.
- **Без `ACCESS_BACKGROUND_LOCATION`:** foreground-service с типом `location`, запущенный из foreground, легально продолжает в фоне — упрощает онбординг.
- **`BALANCED_POWER` (не `HIGH_ACCURACY`):** батарея — приоритет; для трека 1/мин точности сети+GPS достаточно.
- **Отдельный OkHttpClient для local:** local — второй хост; изолируем доверие — БЕЗ `ServerTimeInterceptor` (не якорим trusted-часы от LAN-сервера), но с той же подписью.
- **Двойная выгрузка через два флага:** зеркало `MarkEntity` — точка «доставлена» только когда улетела в оба (или в доступный); таргеты независимы.

### TrackPointEntity (таблица `track_points`)
```
@PrimaryKey id: String          // client UUID — идемпотентность
raceId: Int                     // @Index index_track_points_raceId
teamId: Int                     // @Index index_track_points_teamId
lat: Double
lon: Double
accuracy: Float                 // REAL NOT NULL
gpsTimeMs: Long                 // location.time
elapsedRealtimeAt: Long         // location.elapsedRealtimeNanos / 1_000_000
bootCount: Int?                 // INTEGER (nullable)
wallMs: Long                    // wall-clock МОМЕНТА ФИКСА (back-projected), не времени вставки пачки
trustedMs: Long?                // INTEGER (nullable) — trusted из elapsedRealtimeAt
uploadedLocal: Boolean = false  // INTEGER NOT NULL (Kotlin-дефолт, без @ColumnInfo/DEFAULT — как MarkEntity)
uploadedCloud: Boolean = false  // INTEGER NOT NULL (Kotlin-дефолт, без @ColumnInfo/DEFAULT — как MarkEntity)
```

### API контракт (проектируется, эндпоинта пока нет)
`POST /app/race/<raceId>/track/` — батч точек команды.
```json
{ "team_id": 1234,
  "points": [ { "id": "uuid", "lat": 55.75, "lon": 37.61, "accuracy": 12.4,
    "gps_time_ms": 1718900000000, "trusted_ms": 1718900000123,
    "elapsed_at": 9876543, "boot_count": 7 } ] }
```
Идемпотентность: upsert по client `id`. Ответ `200` со списком принятых `id` → `markUploaded(ids, target)`. Подпись — та же HMAC-схема, что у `bindTag`.

### Конфиг local-сервера
`BuildConfig.LOCAL_API_BASE_URL` (дефолт `http://192.168.1.5/`), ключ `kolco24.localApiBaseUrl` в `local.properties` + env-fallback `KOLCO24_LOCAL_API_BASE_URL`. Cleartext — `res/xml/network_security_config.xml` `domain-config` только для `192.168.1.5`.

## What Goes Where
- **Implementation Steps** (`[ ]`): код, тесты, манифест, gradle, миграции, schema JSON — всё в этом репозитории.
- **Post-Completion** (без чекбоксов): подъём backend-эндпоинта `POST /app/race/<id>/track/`, реальный local-сервер события на `192.168.1.5`, ручная проверка фоновой записи на устройстве (расход батареи, экран выключен), запуск `connectedDebugAndroidTest` на эмуляторе/устройстве.

---

# ФАЗА 1 — Ядро локальной записи

## Implementation Steps

### Task 1: Зависимость play-services-location + конфиг local URL

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [x] добавить версию и библиотеку `play-services-location` (актуальная стабильная) в `libs.versions.toml`
- [x] подключить `implementation(libs.play.services.location)` в `app/build.gradle.kts`
- [x] добавить `BuildConfig.LOCAL_API_BASE_URL`: читать `kolco24.localApiBaseUrl` из `local.properties`, fallback env `KOLCO24_LOCAL_API_BASE_URL`, дефолт `http://192.168.1.5/` (по образцу `API_BASE_URL`, но с дефолтом — не падать если ключа нет)
- [x] ⚠️ **(из ревью) хост и cleartext связаны.** `network_security_config.xml` (Task 11) разрешает cleartext только для `192.168.1.5`. Значит **хост зафиксирован by design** — конфиг-ключ меняет порт/путь/схему, но смена *хоста* требует синхронной правки `network_security_config.xml`. Зафиксировать это коммент-доком у ключа и в `network_security_config.xml`; если в будущем нужен произвольный LAN-хост — отдельная задача (debug-only широкий cleartext или ввод хоста с динамическим security-config, что Android из коробки не умеет) — коммент-док добавлен у `localApiBaseUrl` в `app/build.gradle.kts` (часть про `network_security_config.xml` — Task 11)
- [x] `./gradlew help` / sync — проект конфигурируется без ошибок (`./gradlew help` зелёный, `play-services-location:21.3.0` резолвится в `debugRuntimeClasspath`)
- [x] (тестов нет — конфигурация сборки; проверка = успешный sync)

### Task 2: Обобщить TrustedClock для произвольного elapsedAt

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/time/TrustedClock.kt`
- Modify: `app/src/test/java/.../TrustedClockTest.kt`

⚠️ **Важно (из ревью):** существующий `computeTrusted` инвалидирует по **монотонной регрессии** `anchorElapsedMs > elapsedNow` — это валидно только для «сейчас». Для *прошлого* фикса `elapsedAt < anchorElapsedMs` — **нормальный** случай (точка снята до того, как сеть поставила якорь сессии), и регрессия-гард ошибочно вернёт `null`, спутав «до якоря» с «reboot». Поэтому путь `trustedAt` НЕ должен использовать монотонную регрессию как признак reboot.

- [x] добавить публичный метод `trustedAt(elapsedAt: Long, bootAt: Int?): Long?` — берёт `AtomicReference` состояние один раз (lock-free `.get()`, как `trusted()`)
- [x] reboot-детект в `trustedAt` строить **только** на boot-сессии: если `bootAt != null && anchor.bootCount != null && bootAt != anchor.bootCount` → `null` (разные boot-сессии — нельзя сравнивать монотонные шкалы); иначе вернуть `serverEpochMs + (elapsedAt − anchorElapsedMs)` — формула корректно экстраполирует и назад (отрицательная Δ для pre-anchor точки)
- [x] не ломать существующий `computeTrusted`/`trusted()` (путь «сейчас» оставляет монотонную регрессию) — `trustedAt` это отдельная ветка
- [x] write tests: `trustedAt` для прошлого `elapsedAt` (точка пачки 4 мин назад) даёт время раньше «сейчас»; разница = Δelapsed
- [x] write tests: **pre-anchor точка** `elapsedAt < anchorElapsedMs` в **той же** boot-сессии → НЕ `null`, а корректное время раньше якоря (ключевой кейс из ревью)
- [x] write tests: `trustedAt` с `bootAt`, не совпадающим с `anchor.bootCount` → `null`; оба `bootCount` null → fallback-поведение задокументировать (нет данных о reboot → доверяем, экстраполируем)
- [x] run tests — `./gradlew testDebugUnitTest` должен пройти перед Task 3

### Task 3: Чистые модели трека — RawFix, маппинг, метрики

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt`
- Create: `app/src/test/java/.../track/TrackMetricsTest.kt`
- Create: `app/src/test/java/.../track/TrackPointMappingTest.kt`

- [x] `data class RawFix(lat, lon, accuracy, gpsTimeMs, elapsedRealtimeNanos)` — Android-free value type
- [x] чистая `trackLengthMeters(points: List<TrackPointEntity или общий тип>): Double` — сумма гаверсинусов между соседними по `elapsedRealtimeAt` (через интерфейс `TrackPointLike`, отсортировано по `elapsedRealtimeAt`)
- [x] чистая `filterPoints(points, maxAccuracyMeters: Float = 50f): List<...>` — отбрасывает грубые фиксы (для показа/длины; в БД пишем всё)
- [x] чистый маппер `RawFix.toTrackPoint(raceId, teamId, wallMs, trustedMs, bootCount, idFactory): TrackPointEntity` (`elapsedRealtimeAt = elapsedRealtimeNanos/1_000_000`) — `idFactory`/`trustedMs` инжектятся, чтобы маппер был детерминирован и тестируем (создан `data/db/TrackPointEntity.kt` — нужен типу маппера; реализует `TrackPointLike`. Регистрация в `AppDatabase`/DAO/миграция/`11.json` остаются в Task 4)
- [x] write tests `TrackMetricsTest`: длина по известным координатам (напр. 0.001° ≈ ожидаемые метры), `filterPoints` отбрасывает `accuracy>50`, пустой/одноточечный список → 0
- [x] write tests `TrackPointMappingTest`: `elapsedRealtimeAt` = nanos/1e6, поля проброшены, `trustedMs` берётся из инжектированного значения (имитируя расчёт из `elapsedRealtimeNanos`)
- [x] run tests — должны пройти перед Task 4

### Task 4: Room v11 — TrackPointEntity, TrackDao, миграция

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TrackPointEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TrackDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create: `app/schemas/.../11.json` (генерируется KSP при сборке)
- Modify: `app/src/androidTest/java/.../MigrationTest.kt`

- [x] добавить `TrackPointEntity` (поля из Technical Details; `@Index` на `teamId` и `raceId`)
- [x] `TrackDao`: `observeForTeam(teamId): Flow<List<TrackPointEntity>>` (`ORDER BY elapsedRealtimeAt ASC`), `insertAll(points)`, `markUploadedLocal(ids)`, `markUploadedCloud(ids)`, `countForTeam(teamId): Flow<Int>`, `deleteForTeam(teamId)`
- [x] ⚠️ **(из ревью) запросы выгрузки scoped по `(raceId, teamId)`** — иначе пачка может зацепить точки другой команды/гонки и уйти не на тот эндпоинт (`/race/<raceId>/track/`). Сигнатуры: `unuploadedLocal(raceId, teamId, limit)` (`WHERE raceId=:raceId AND teamId=:teamId AND uploadedLocal=0 ORDER BY elapsedRealtimeAt LIMIT :limit`), `unuploadedCloud(raceId, teamId, limit)` аналогично. `uploadPending` (Task 12) вызывается с `(raceId, teamId)` пары
- [x] ⚠️ **(из ревью) `pendingUploadScopes(): List<TrackScope>`** — `SELECT DISTINCT raceId, teamId FROM track_points WHERE uploadedLocal=0 OR uploadedCloud=0`, где `data class TrackScope(raceId: Int, teamId: Int)`. Нужен для надёжного досыла: оппортунистический upload не должен ограничиваться только текущей `(raceId, teamId)` — иначе pending-точки старых гонок/команд застрянут навсегда. Task 12 итерируется по всем scope из `pendingUploadScopes()`
- [x] в `AppDatabase`: bump version 10→11, добавить `TrackPointEntity` в `entities`, абстрактный `trackDao()`, `MIGRATION_10_11` (`CREATE TABLE track_points` + `CREATE INDEX index_track_points_teamId` + `CREATE INDEX index_track_points_raceId`), зарегистрировать миграцию
- [x] SQL колонок в `CREATE TABLE` дословно: `id TEXT NOT NULL PRIMARY KEY`, `raceId INTEGER NOT NULL`, `teamId INTEGER NOT NULL`, `lat REAL NOT NULL`, `lon REAL NOT NULL`, `accuracy REAL NOT NULL`, `gpsTimeMs INTEGER NOT NULL`, `elapsedRealtimeAt INTEGER NOT NULL`, `bootCount INTEGER` (nullable), `wallMs INTEGER NOT NULL`, `trustedMs INTEGER` (nullable), `uploadedLocal INTEGER NOT NULL`, `uploadedCloud INTEGER NOT NULL`
- [x] ⚠️ **(из ревью) БЕЗ `DEFAULT 0` в SQL.** Room не эмитит DB-level default, если у поля нет `@ColumnInfo(defaultValue = "0")` — а Kotlin `= false` его не создаёт. `DEFAULT 0` в migration SQL без аннотации → расхождение с `11.json` и крэш schema-валидации. Зеркалим `MarkEntity` (Kotlin-дефолт, без `@ColumnInfo`): в `CREATE TABLE` — `INTEGER NOT NULL` без `DEFAULT`. Для новой таблицы это безопасно — все вставки идут через Room и всегда передают значение
- [x] собрать (`assembleDebug`) — KSP генерирует `11.json`; сверить SQL миграции дословно с ним (имена индексов camelCase, типы, nullability); **закоммитить `11.json` в git** (не только сгенерировать — `MigrationTestHelper` читает его из schemas)
- [x] write instrumented test `MigrationTest.migrate10To11_keepsDataAndAddsTable`: предзаполнить v10-строку (напр. marks), мигрировать, проверить что строка жива и `track_points` существует
- [x] run `./gradlew testDebugUnitTest` (компиляция DAO) + по возможности `connectedDebugAndroidTest` — должны пройти перед Task 5 — `testDebugUnitTest` зелёный; `connectedDebugAndroidTest` пропущен (нет эмулятора/устройства в среде — запустить вручную)

### Task 5: TrackRepository + wiring в AppContainer

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackState.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Create: `app/src/test/java/.../track/TrackRepositoryTest.kt`

- [x] `sealed interface TrackState { object Idle; data class Recording(teamId: Int, pointCount: Int) }`
- [x] `TrackRepository(trackDao)`: `insertAll(points)`, `observeTrack(teamId)`, `countForTeam(teamId)`, `deleteForTeam(teamId)`, чистые метрики через хелперы Task 3 (длина тестируется поверх `observeTrack` через `trackLengthMeters`)
- [x] **Решено (из ревью): маппинг владеет репозиторий.** Сигнатура `suspend fun insertAll(rawFixes: List<RawFix>, raceId: Int, teamId: Int)` — **один раз** на пачку снять `wallNow = wallProvider()` и `elapsedNow = elapsedProvider()` (тот же монотонный источник, что у `TrustedClock`), `bootAt = bootCountProvider()`; затем для каждой точки `elapsedAt = elapsedRealtimeNanos/1_000_000`, `trustedMs = trustedClock.trustedAt(elapsedAt, bootAt)`, `wallMs = wallNow + (elapsedAt − elapsedNow)` (back-project к моменту фикса, НЕ `wallProvider()` per-point), `bootCount = bootAt`, `id = idFactory()`, затем `trackDao.insertAll(entities)`. Сервис (Task 7) лишь форвардит `List<RawFix>` — никакой логики времени в сервисе
- [x] ⚠️ **(из ревью) `bootCount` — через инъекцию, не в `RawFix`.** Фикс всегда снят в **текущей** boot-сессии (сервис работает сейчас), поэтому `bootAt = текущий cachedBootCount`. `TrackRepository` принимает `bootCountProvider: () -> Int?` (из `AppContainer.cachedBootCount`) + `TrustedClock` + `idFactory` + `wallProvider` + `elapsedProvider: () -> Long` (для back-projection `wallMs`; в `AppContainer` = `{ SystemClock.elapsedRealtime() }`) — `RawFix` остаётся чистым гео-value-типом без boot/время-полей
- [x] в `AppContainer`: lazy `trackDao`, lazy `trackRepository` (передать `trustedClock`, `bootCountProvider = { cachedBootCount }`, `idFactory = { UUID.randomUUID().toString() }`, `wallProvider = { System.currentTimeMillis() }`), публичный `val trackRecordingState = MutableStateFlow<TrackState>(Idle)` (пишет сервис, читает UI)
- [x] write tests `TrackRepositoryTest` (fake `TrackDao`): `insertAll` мапит и пишет, `deleteForTeam` чистит, метрики/счётчик корректны, `trustedMs` досчитывается из `elapsedRealtimeAt` (fake clock), `bootCount`/`id` берутся из инжектированных провайдеров (детерминированный тест)
- [x] ⚠️ **(из ревью) write test батча из ≥2 точек с РАЗНЫМИ `elapsedRealtimeNanos`:** проверить, что каждая получает **свой** `wallMs = wallNow + (elapsedAt − elapsedNow)` (точки различаются по wall-времени ровно на Δelapsed), а не одинаковое время вставки пачки — это и есть критерий «время точки корректно при батчинге» для wall-fallback
- [x] run tests — должны пройти перед Task 6

### Task 6: LocationEngine seam + Fused + Legacy + Factory

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngine.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/FusedLocationEngine.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyLocationEngine.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngineFactory.kt`
- Create: `app/src/test/java/.../track/LocationEngineFactoryTest.kt`

- [x] ⚠️ **(из ревью) `start` обязан иметь error-path.** Даже после precheck permission `requestLocationUpdates` может бросить `SecurityException` (revoke/TOCTOU между лаунчером и стартом) или провайдер может отсутствовать. Сигнатура: `interface LocationEngine { fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit); fun stop() }` (callback-`onError`, не `Result` — старт асинхронный, ошибка может прийти позже). Реализации **оборачивают `requestLocationUpdates` в try/catch** и зовут `onError(e)` вместо падения
- [x] `FusedLocationEngine`: `LocationRequest.Builder(PRIORITY_BALANCED_POWER_ACCURACY, 60_000).setMinUpdateIntervalMillis(60_000).setMaxUpdateDelayMillis(300_000)`; в `onLocationResult` отдавать всю пачку `locationResult.locations.map { it.toRawFix() }` (мапит `time`/`elapsedRealtimeNanos`); `requestLocationUpdates` в try/catch → `onError`; повесить `addOnFailureListener` на Task, чтобы GMS-ошибка тоже шла в `onError`
- [x] `LegacyLocationEngine`: `LocationManager.requestLocationUpdates(GPS_PROVIDER, 60_000L, 0f, listener, Looper)`, fallback `NETWORK_PROVIDER` если GPS-провайдера нет; **если ни одного usable-провайдера нет** → `onError(IllegalStateException("no usable provider"))`; `SecurityException`/`requestLocationUpdates` в try/catch → `onError`; каждый фикс — список из одного `RawFix`
- [x] `LocationEngineFactory.create(context)`: выбор по `GoogleApiAvailability.isGooglePlayServicesAvailable(ctx) == SUCCESS`; для тестируемости — вынести решение в чистую `chooseEngineType(gmsAvailable: Boolean): EngineType`
- [x] note (из ревью): legacy-путь без `maxUpdateDelay`-эквивалента → под Doze на не-GMS устройствах апдейты могут троттлиться (device-dependent); это причина предпочтения Fused — отметить в коде комментарием и в Post-Completion device-test
- [x] write tests `LocationEngineFactoryTest`: `chooseEngineType(true)→Fused`, `chooseEngineType(false)→Legacy` (реальные движки/адаптеры — не тестируем по конвенции, отметить комментарием)
- [x] run tests — должны пройти перед Task 7

### Task 7: TrackRecordingService + permissions + манифест

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] объявить permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`
- [ ] объявить `<uses-feature android:name="android.hardware.location.gps" android:required="false" />` (из ревью: как `nfc required=false` — иначе Play фильтрует устройства без GPS, регрессия installability)
- [ ] объявить `<service android:name=".TrackRecordingService" android:foregroundServiceType="location" android:exported="false"/>`
- [ ] ⚠️ **(из ревью) permission — жёсткий precondition.** На target 34+ `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` **без** выданного coarse/fine permission кидает `SecurityException`. Гарантия: Task 8 лаунчер подтверждает `ACCESS_FINE_LOCATION` *до* `startForegroundService` — сервис стартует только когда permission уже есть. В `onStartCommand` первым делом **перепроверить** permission (`ContextCompat.checkSelfPermission`); если её нет (TOCTOU: отозвали между лаунчером и стартом) → `startForeground` с location-типом **не вызывать**, `stopSelf()` и выйти
- [ ] ⚠️ **(из ревью) GPS-тумблер ≠ permission.** Выключенные location services (тумблер GPS) `startForeground` НЕ ломают — сервис легально стартует, просто фиксов нет (показываем баннер + deep-link, как в Task 8). Блокирует старт только **отсутствие permission**, не выключенный тумблер. Если хочется ещё жёстче — опциональный fallback: поднять FGS с обычным (не-location) уведомлением и **promote** в location-тип только после подтверждения permission; для нашего флоу (permission гарантирован лаунчером) это избыточно, оставляем прямой старт
- [ ] `TrackRecordingService`: при наличии permission `onStartCommand` **немедленно** (<5с) вызывает `startForeground(id, notif, FOREGROUND_SERVICE_TYPE_LOCATION)`, читает `raceId`/`teamId` из extras, создаёт `LocationEngine` через factory, подписывается `start(onPoints, onError)`; `START_NOT_STICKY`
- [ ] ⚠️ **(из ревью) `onError` → корректный teardown.** Передать в `engine.start(...)` `onError = { e -> }` который логирует, снимает updates (`engine.stop()`), `container.trackRecordingState = Idle`, `stopForeground`+`stopSelf` — сервис не виснет в фоне как «Recording» без фиксов при недоступном провайдере/`SecurityException`. UI вернётся в `Idle` через `trackRecordingState` (хост может показать тост/баннер «не удалось начать запись»)
- [ ] note (из ревью): отказ в `POST_NOTIFICATIONS` — **не фатален**: канал всё равно создаётся, `startForeground` работает, уведомление просто не показывается; запись идёт
- [ ] уведомление: канал «Запись трека» (low importance, без звука), текст «Идёт запись трека · N точек», `Action` «Стоп» (`PendingIntent` с `STOP` action → `stopSelf`), тап → открыть `MainActivity`
- [ ] на каждую пачку `RawFix`: `applicationScope.launch { trackRepository.insertAll(...) }`, обновлять `container.trackRecordingState` (`Recording(teamId, count)`); на стоп — снять updates, `stopForeground`+`stopSelf`, `trackRecordingState = Idle`
- [ ] (тестов нет — Service/адаптер по конвенции; логика выбора движка/маппинг уже покрыты Task 3/6; lint должен пройти — проверить `ForegroundServicePermission`/`NewApi`)

### Task 8: TrackCard UI + поток запуска в MainActivity

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] ⚠️ **(из ревью) НЕ дизейблить старт по GPS-железу.** Выше выбран `FusedLocationEngine` + fallback на `NETWORK_PROVIDER` — устройство без GPS-чипа всё равно даёт coarse/network-локацию, блокировать его = регрессия. `TrackCard(state: TrackState, pointCount, lengthMeters, hasTeam, degradedAccuracy: Boolean, onStart, onStop, onClear, first/lastPointTime)` — stateless: `Idle` → «Начать запись» (`OrangeCta`, **дизейбл только при `!hasTeam`**); `degradedAccuracy = true` (нет GPS-провайдера, только network) → не дизейбл, а тихий хинт «Только примерная геолокация» под кнопкой; `Recording` → пульсирующая точка + «N точек · ~Xм» + «Остановить»; метрики (число точек, длина м/км, время первой/последней) + «Очистить трек»
- [ ] gate старта = `hasTeam` (UI) + permission/location-enabled/наличие хотя бы одного usable-провайдера (хост, Task 8 ниже); отсутствие GPS-железа — это `degradedAccuracy`, а не блок. `degradedAccuracy` хост считает как «нет ни `GPS_PROVIDER`, ни вообще точного провайдера, но есть `NETWORK_PROVIDER`»
- [ ] встроить `TrackCard` в `TeamScreen` как `SectionCard` (рядом с «Прочее»), прокинуть колбэки/состояние от хоста
- [ ] в `MainActivity`: collect `container.trackRecordingState`, `track`-флоу `remember(selectedTeamId){ trackRepo.observeTrack(it) }`, счётчик/длина; вычислить `degradedAccuracy` = есть `NETWORK_PROVIDER`, но нет `GPS_PROVIDER` (через `LocationManager`), прокинуть в `TrackCard`; `onStart` → проверка permissions через `rememberLauncherForActivityResult` (`ACCESS_FINE_LOCATION`, на 13+ `POST_NOTIFICATIONS`) → проверка, что включён **хотя бы один** usable-провайдер (`LocationManager.isProviderEnabled(GPS)` ИЛИ `isProviderEnabled(NETWORK)`; ни одного → баннер + deep-link `ACTION_LOCATION_SOURCE_SETTINGS`, но `startForegroundService` всё равно — фиксы пойдут, как только тумблер включат) → `ContextCompat.startForegroundService(Intent extras raceId/teamId)`
- [ ] `onStop` → `Intent` STOP в сервис / `stopService`; `onClear` доступен **только в состоянии `Idle`** (из ревью: чистка во время записи → сервис продолжит вставлять после wipe, счётчик прыгнет) → подтверждающий `AlertDialog` (как unbind) → `applicationScope.launch { trackRepo.deleteForTeam(teamId) }`; отказ навсегда (`!shouldShowRationale`) → диалог с deep-link в настройки приложения
- [ ] смена команды во время записи → останавливать сервис (в существующем `LaunchedEffect(selectedTeamId)` рядом с другими overlay-ресетами)
- [ ] (тестов нет — Compose/MainActivity по конвенции; чистые длины/метрики уже покрыты Task 3)

### Task 9: Verify Фаза 1

- [ ] verify: запись стартует/стопится вручную, точки пишутся в Room, карточка показывает счётчик и длину, переживает сворачивание/поворот
- [ ] verify edge cases: нет команды (кнопка дизейбл), GPS выключен (баннер+deep-link), смена команды (стоп), reboot (`START_NOT_STICKY`, не возобновляется)
- [ ] run `./gradlew testDebugUnitTest` — все JVM-тесты зелёные
- [ ] run `./gradlew lintDebug` — без новых ошибок (foreground-service-type, NewApi)
- [ ] run `./gradlew connectedDebugAndroidTest` (эмулятор/устройство) — `MigrationTest` зелёный

---

# ФАЗА 2 — Двойная батч-выгрузка (когда серверы готовы)

### Task 10: Track upload DTO + ApiClient методы (cloud)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/TrackDtos.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Create: `app/src/test/java/.../TrackUploadTest.kt`

- [ ] `@Serializable` `TrackUploadRequest(team_id, points: List<TrackPointDto>)` + `TrackPointDto(id, lat, lon, accuracy, gps_time_ms, trusted_ms: Long?, elapsed_at, boot_count: Int?)` + `TrackUploadResponse(accepted: List<String>)` (snake_case `@SerialName`)
- [ ] чистый маппер `TrackPointEntity.toDto(): TrackPointDto`
- [ ] **Решено (из ревью): один метод `uploadTrack`, два инстанса `ApiClient`.** `ApiClient` сейчас держит один `baseUrl` + один `okHttpClient` — двум таргетам нужны **два инстанса** `ApiClient` (не per-call baseUrl, чтобы не рефакторить существующие методы). В Task 10 добавить **один** метод `ApiClient.uploadTrack(raceId, teamId, points): PostResult<TrackUploadResponse>` (строит `"$baseUrl/app/race/$raceId/track/"` через `post(...)`) — он же используется и для cloud, и для local; выбор таргета = выбор инстанса `ApiClient`. Никаких `uploadTrackCloud`/`uploadTrackLocal` методов
- [ ] write tests `TrackUploadTest` (MockWebServer как `ApiClientTest`): `200` → `Success(accepted)`, `403/401/400/429/offline` маппинг, батч-маппинг entity→DTO, пустой батч
- [ ] run tests — должны пройти перед Task 11

### Task 11: Local OkHttpClient + network security config + uploadTrackLocal

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/test/java/.../TrackUploadTest.kt`

- [ ] `network_security_config.xml`: `domain-config cleartextTrafficPermitted=true` только для `192.168.1.5`; остальное — дефолт HTTPS-only; подключить `android:networkSecurityConfig` в `<application>`
- [ ] ⚠️ **(из ревью) короткие таймауты — конкретное изменение.** `defaultOkHttpClient` сейчас жёстко задаёт 10s connect/read. Добавить ему параметры `connectTimeoutMs: Long = 10_000, readTimeoutMs: Long = 10_000` (дефолты = текущее поведение, cloud-вызовы не меняются), применять их в билдере. Альтернатива (если не хочется трогать сигнатуру) — `localOkHttpClient = defaultOkHttpClient(signatureInterceptor).newBuilder().connectTimeout(3, SECONDS).readTimeout(3, SECONDS).build()`. Выбрать первый вариант (параметры) — `newBuilder()` поверх не отменяет уже добавленные интерсепторы, но явные параметры читаются понятнее
- [ ] в `AppContainer`: отдельный `localOkHttpClient` = `defaultOkHttpClient(signatureInterceptor, connectTimeoutMs = 3_000, readTimeoutMs = 3_000)` **без** `ServerTimeInterceptor` (из ревью: local — второй хост; держим на отдельном клиенте, чтобы не якорить trusted-часы от LAN и не нарушать host-less допущение `ServerTimeInterceptor`; cloud-клиент local-хост НЕ получает; короткий таймаут — чтобы upload не висел ~10с, когда телефон не в Wi-Fi события); отдельный инстанс `localApiClient = ApiClient(LOCAL_API_BASE_URL, localOkHttpClient, json)`
- [ ] метод `uploadTrack` уже есть (Task 10) — local просто вызывает `localApiClient.uploadTrack(...)`; недоступность (не в той Wi-Fi) → `Offline`/timeout быстро (3s)
- [ ] write tests: `uploadTrack` против local-инстанса — маппинг статусов через MockWebServer; offline (нет сервера) → `Offline`
- [ ] run tests — должны пройти перед Task 12

### Task 12: uploadPending + оппортунистический дослыл

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`
- Modify: `app/src/test/java/.../track/TrackRepositoryTest.kt`

- [ ] `TrackRepository.uploadPending(raceId, teamId)`: для каждого таргета независимо — читать `unuploadedLocal`/`unuploadedCloud(raceId, teamId, 500)` пачками, слать через соответствующий инстанс (`localApiClient.uploadTrack` / `apiClient.uploadTrack`), на `Success` → `markUploadedLocal`/`markUploadedCloud(accepted)`; ошибки таргета не валят другой (репо принимает оба `ApiClient` инстанса)
- [ ] ⚠️ **(из ревью) break при отсутствии прогресса.** Цикл «пока есть неотправленные» обязан останавливаться, если за итерацию **не помечено ни одной** строки (`accepted` пустой или подмножество, не двигающее курсор) — иначе те же строки ретраятся в тугом бесконечном цикле. Условие выхода: `accepted.isEmpty()` ИЛИ ответ не `Success` → break (дошлём при следующем стопе/запуске); не `markUploaded` строки, которых нет в `accepted`
- [ ] на стопе записи в сервисе → `applicationScope.launch { trackRepository.uploadPending(raceId, teamId) }`
- [ ] ⚠️ **(из ревью) оппортунистический дослыл по ВСЕМ pending-scope, не только текущему.** Добавить `TrackRepository.uploadAllPending()`: читает `trackDao.pendingUploadScopes()` и для каждого `TrackScope` зовёт `uploadPending(raceId, teamId)`. Вызывать из `Kolco24App` (Launch B / при выборе команды) в `applicationScope` — так застрявшие точки прошлых гонок/команд тоже дошлются
- [ ] ⚠️ **(из ревью) guard от параллельных запусков.** `uploadAllPending`/`uploadPending` защитить от конкурентного входа (стоп-сервиса + Launch B одновременно): `Mutex.tryLock()` в `TrackRepository` (`if (!mutex.tryLock()) return` — пропускаем, дойдём следующим триггером), чтобы две выгрузки не слали одни и те же строки и не плодили дублей запросов
- [ ] write tests: `uploadPending` ставит правильный флаг по таргету, не дублирует уже выгруженное, частичный `accepted` помечает только принятые, **пустой `accepted` → break без зацикливания**, ошибка одного таргета не мешает другому (fake DAO + fake ApiClient); `uploadAllPending` обходит все `pendingUploadScopes()` (две разные `(raceId, teamId)` пары → обе выгружены); повторный вход под удержанным `Mutex` (guard) — no-op
- [ ] run tests — должны пройти перед Task 13

### Task 13: Verify acceptance criteria
- [ ] verify all requirements from Overview реализованы (запись, фон, метрики, двойная выгрузка)
- [ ] verify edge cases: local недоступен → cloud всё равно уходит и наоборот; ретрай не дублирует (idempotent по id); `trusted_ms=null` сериализуется
- [ ] run full test suite: `./gradlew testDebugUnitTest`
- [ ] run `./gradlew lintDebug`
- [ ] run `./gradlew connectedDebugAndroidTest`

### Task 14: [Final] Документация
- [ ] обновить `CLAUDE.md`: новый `data/track/` слой, `TrackRecordingService`, Room v11, двойная выгрузка трека, `LOCAL_API_BASE_URL`/network-security-config, расширение `TrustedClock.trustedAt`
- [ ] обновить `docs/API.md` контрактом `POST /app/race/<id>/track/` (или отметить как «спроектировано, ждёт backend»)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Требуют внешних действий — без чекбоксов, информационно*

**Backend / инфраструктура:**
- поднять эндпоинт `POST /app/race/<raceId>/track/` с upsert по client `id`, приёмом `trusted_ms=null`, rate-limit, той же HMAC-подписью что у `bindTag` — до этого Фаза 2 пишет в Room с `uploaded*=0` (данные не теряются).
- **local-сервер на `192.168.1.5` (из ревью):** должен валидировать те же 6 заголовков `X-App-*`/`X-Install-Id` с **тем же key id / secret** и окном ±300 с по trusted-времени (либо отключить проверку времени). Иначе каждый local-upload → `403`, `uploadedLocal` молча остаётся `0`, данные «зависают» без ошибки пользователю. Тот же контракт тела/ответа, что у cloud.

**Ручная проверка на устройстве:**
- фоновая запись со спящим экраном 1–2 часа: реальный расход батареи, отсутствие пропусков, корректность времени точек при батчинге.
- поведение на телефоне без GMS (Huawei) — fallback на `LegacyLocationEngine`.
- двойная выгрузка: в Wi-Fi события (оба таргета), вне Wi-Fi (только cloud, local дошлётся позже).
