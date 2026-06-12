# Races sync: получение и офлайн-хранение списка гонок (GET /app/races/)

## Overview
- Слой данных для списка гонок: сеть (подписанный HMAC-запрос `GET /app/races/` с ETag/304) + офлайн-хранение в Room.
- Приложение работает без интернета: Room — единственный источник правды, сеть лишь обновляет базу; при офлайне UI (будущий) продолжает читать кеш.
- Без UI в этой задаче. Минимальный потребитель — fire-and-forget `refreshRaces()` при старте приложения с логированием результата.
- Дизайн согласован в brainstorm-сессии 2026-06-12; решения ниже не пересматривать без причины.

## Context (from discovery)
- Проект: single-activity Jetpack Compose, пакет `ru.kolco24.kolco24`, minSdk 24, targetSdk 36, AGP 9.2.1 (Kotlin 2.2.10 встроен в AGP, явного KGP-плагина нет), Compose BOM 2026.02.01.
- Сейчас нет сети, БД, ViewModel, DI, Application-класса — всё добавляется с нуля.
- Описание API: `docs/API.md` — HMAC-SHA256-подпись каждого запроса (6 заголовков, окно ±300 с), ETag/304, при `200` полный список (замещать локальную копию целиком), ETag хранить раздельно по origin.
- Версии зависимостей: `gradle/libs.versions.toml`; сборка приложения: `app/build.gradle.kts`.

## Development Approach
- **testing approach**: Regular (код, затем тесты в рамках той же задачи)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change

## Testing Strategy
- **unit tests** (JVM, `./gradlew testDebugUnitTest`): обязательны для каждой задачи с логикой.
- Room DAO инструментальными тестами **не** покрываем (решение brainstorm: запросы тривиальные). Репозиторий тестируем на JVM с in-memory фейками DAO-интерфейсов + MockWebServer.
- e2e-тестов в проекте нет — не добавляем.
- Перед мержем обязателен `./gradlew lintDebug` (правило проекта).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
- **Repository + Flow, Room как единственный источник правды.** `RaceRepository.races: Flow<List<RaceEntity>>` читает базу; `refreshRaces()` ходит в сеть с сохранённым ETag и при `200` полностью замещает таблицу.
- **OkHttp + kotlinx.serialization, без Retrofit/Hilt.** Подпись — OkHttp-интерцептор; DI — ручной `AppContainer` (lazy) в Application-классе.
- **Секреты вне git:** `local.properties` → `BuildConfig` (`API_BASE_URL`, `APP_KEY_ID`, `APP_SECRET`); сборка падает с понятной ошибкой, если свойства не заданы.
- Ключевые компромиссы: `RaceEntity` и есть модель (без третьего доменного слоя); даты/`reg_status` хранятся строками как пришли (forward-compatible); ETag сохраняется **после** замещения данных — при падении между шагами следующий refresh просто получит `200` повторно.

## Technical Details

**Канонная строка и подпись** (`docs/API.md`):
```
GET \n full_path(+query, со слешем в конце) \n ts(секунды) \n sha256_hex(пустого тела)
```
- hex SHA-256 пустого тела — константа `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- `sig = lowercase hex( HMAC-SHA256(secret_utf8, canonical_utf8) )`.
- `ts` берётся в момент вызова интерцептора (интерцептор срабатывает и на ретраях → ts/подпись пересчитываются сами).
- Заголовки: `X-App-Key-Id`, `X-App-Sig`, `X-App-Ts`, `X-Install-Id` (UUID при первом запуске, SharedPreferences), `X-App-Platform: android`, `X-App-Version` = `BuildConfig.VERSION_NAME`.

**Сеть:**
- `ApiClient.fetchRaces(etag: String?)`: `GET {baseUrl}/app/races/`; если `etag != null` — заголовок `If-None-Match` ровно как сохранили (с кавычками).
- Результат — sealed `FetchResult`: `Success(races: List<RaceDto>, etag: String?)` | `NotModified` | `Forbidden` | `Error(code: Int?)`. `IOException` и кривой JSON → `Error`, исключения наружу не летят.
- `Json { ignoreUnknownKeys = true }`; таймауты connect/read = 10 с.

**Room v1** (`AppDatabase`, без миграций, destructive fallback не включаем):
- `races`: `id Int` (PK, серверный), `name`, `slug`, `date String`, `dateEnd String?`, `place`, `regStatus String`, `isLegendVisible Boolean`.
- `sync_meta`: составной PK `(origin, resource)`, `etag String`. `origin` = базовый URL, `resource` = `"races"` (переиспользуется для teams/legend).

**Репозиторий:**
- `races: Flow<List<RaceEntity>>` — `SELECT * FROM races ORDER BY date DESC, id DESC`.
- `refreshRaces(): RefreshResult` = `Updated | NotModified | Offline | Forbidden | HttpError(code)`:
  читает ETag для `(baseUrl, "races")` → `fetchRaces(etag)` → при `Success`: `raceDao.replaceAll(races)` (`@Transaction`: deleteAll + insertAll), затем `syncMetaDao.upsert(etag)`; при `NotModified` — ничего.

**Версии (добавить в `libs.versions.toml`):**
- KSP: пин **строго** под Kotlin 2.2.10 — версия вида `2.2.10-2.0.x` (взять последнюю `2.2.10-*` с https://github.com/google/ksp/releases). Плагин `org.jetbrains.kotlin.plugin.serialization` — `version.ref = "kotlin"`.
- `okhttp` 4.12.x + `mockwebserver` той же версии, `kotlinx-serialization-json` 1.9.x, `room` 2.8.x (`room-runtime`, `room-ktx`, ksp `room-compiler`), `kotlinx-coroutines-android` + `kotlinx-coroutines-test` 1.10.x.
- ⚠️ AGP 9 со встроенным Kotlin (KGP не применён явно): сначала пробуем применить только `ksp` + `kotlin.plugin.serialization` поверх встроенного Kotlin. Если `assembleDebug` падает с ошибкой несоответствия версий Kotlin-плагинов — конкретный fallback: проверить, что версия KSP начинается ровно с `2.2.10-`, и что `settings.gradle.kts` pluginManagement содержит `gradlePluginPortal()`/`google()` для резолва KSP. **Не** применять `org.jetbrains.kotlin.android` поверх встроенного Kotlin (даст «plugin applied twice»). Разрешение этого конфликта — блокирующий критерий Task 1.

**BuildConfig из local.properties:**
- Строковые поля требуют экранирования в Java-литерал: `buildConfigField("String", "API_BASE_URL", "\"$value\"")`.
- Чтение: сначала `local.properties`, затем fallback на переменные окружения `KOLCO24_API_BASE_URL` / `KOLCO24_APP_KEY_ID` / `KOLCO24_APP_SECRET` — чтобы `lintDebug`/`testDebugUnitTest` (merge-гейт) работали в CI/чистом окружении без файла. Если значения нет нигде — `error(...)` с перечислением недостающих свойств и обоих способов их задать.

## What Goes Where
- **Implementation Steps**: изменения кода, тестов и документации в этом репозитории.
- **Post-Completion**: ручная проверка на устройстве, получение боевой пары (key_id, secret) — вне кода.

## Implementation Steps

### Task 1: Подключить плагины и зависимости, конфиг из local.properties

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `local.properties` (вне git — вручную/локально)

- [x] определить точную версию KSP под Kotlin 2.2.10 (`2.2.10-2.0.x`, последний релиз) и записать в `libs.versions.toml`; убедиться, что pluginManagement в `settings.gradle.kts` резолвит KSP (gradlePluginPortal/google) — выбран `2.2.10-2.0.2`; pluginManagement уже содержит gradlePluginPortal/google
- [x] добавить в `libs.versions.toml` версии и алиасы: ksp, kotlin-serialization (plugin, `version.ref = "kotlin"`), okhttp, mockwebserver, kotlinx-serialization-json, room (runtime/ktx/compiler), coroutines (android/test)
- [x] подключить в `app/build.gradle.kts` плагины `ksp` и `kotlin.plugin.serialization`; включить `buildFeatures { buildConfig = true }`
- [x] читать `kolco24.apiBaseUrl`, `kolco24.appKeyId`, `kolco24.appSecret` из `local.properties` с fallback на env `KOLCO24_*` → `buildConfigField("String", ..., "\"$value\"")` (экранирование в Java-литерал); если значения нет нигде — `error(...)` с перечислением недостающих свойств и обоих способов задать
- [x] добавить зависимости: okhttp, kotlinx-serialization-json, room-runtime/ktx + ksp room-compiler, coroutines-android; testImplementation: mockwebserver, coroutines-test
- [x] добавить `<uses-permission android:name="android.permission.INTERNET"/>` в `AndroidManifest.xml` (прямой потомок `<manifest>`, до `<application>`)
- [x] проверка: `./gradlew assembleDebug` проходит; падение при отсутствии свойств проверить, временно убрав строку из local.properties (и env). Примечание: KSP/serialization здесь только конфигурируются — полная валидация тулчейна произойдёт в Task 3 (`@Serializable`) и Task 5 (Room codegen). ⚠️ AGP 9 built-in Kotlin × KSP: потребовался `android.disallowKotlinSourceSets=false` в `gradle.properties` (KSP регистрирует генерируемые исходники через `kotlin.sourceSets`, что запрещено встроенным Kotlin)

### Task 2: Канонная строка и подпись + интерцептор

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/AppSignatureInterceptor.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/SigningTest.kt`

- [ ] чистые функции `buildCanonical(method: String, fullPath: String, ts: String): String` и `sign(secret: String, canonical: String): String` (lower-case hex HMAC-SHA256); константа хэша пустого тела
- [ ] `AppSignatureInterceptor(keyId, secret, installIdProvider, appVersion)`: на каждый запрос берёт свежий `ts`, строит канонную строку из `encodedPath` (+ `?query` если есть), подписывает и добавляет все шесть заголовков
- [ ] тест `buildCanonical` против примера из `docs/API.md` (путь `/app/race/8/teams/`, ts `1718200000`, ожидаемая 4-строчная канонная строка)
- [ ] тест `sign` против фиксированного тест-вектора (известный секрет + канонная строка → заранее посчитанный HMAC); вектор сгенерировать эталоном сервера `src/apps/mobile/signing.py` (репо kolco24) или, если сервер недоступен, независимым инструментом (`python hmac`/`openssl`) — не своей же реализацией
- [ ] run tests - must pass before task 3

### Task 3: DTO и парсинг ответа races

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/RacesResponse.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/RacesResponseTest.kt`

- [ ] `@Serializable` `RacesResponse(races: List<RaceDto>)` и `RaceDto(id, name, slug, date, dateEnd: String?, place, regStatus, isLegendVisible)` с `@SerialName` под snake_case поля API
- [ ] тест: парсинг JSON-примера из `docs/API.md` → корректные значения всех полей
- [ ] тест: лишнее неизвестное поле в JSON не ломает парсинг (`ignoreUnknownKeys`); отсутствие обязательного поля → исключение сериализации
- [ ] run tests - must pass before task 4

### Task 4: ApiClient с ETag и обработкой ответов

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt`

- [ ] sealed `FetchResult`: `Success(races, etag)` | `NotModified` | `Forbidden` | `Error(code: Int?)`
- [ ] `ApiClient(baseUrl, okHttpClient, json)` c `suspend fun fetchRaces(etag: String?): FetchResult`: GET `/app/races/`, `If-None-Match` как сохранили; 200 → парсинг + ETag из заголовка, 304/403/прочее → соответствующие результаты; `IOException`/`SerializationException` → `Error(null)`; вызовы на `Dispatchers.IO`
- [ ] собрать `OkHttpClient` с таймаутами 10 с и `AppSignatureInterceptor` (фабрика в ApiClient или AppContainer — решить по месту)
- [ ] тесты с MockWebServer: 200 + ETag → `Success` с гонками и etag; в перехваченном запросе присутствуют все 6 заголовков подписи; `If-None-Match` уходит с кавычками
- [ ] тест валидности подписи end-to-end: путь перехваченного запроса — ровно `/app/races/` (со слешем); пересчитать подпись в тесте из известного тестового секрета + перехваченных `path`/`X-App-Ts` и сравнить с `X-App-Sig` (ловит расхождение «подписали один путь — отправили другой», главную причину 403)
- [ ] тесты с MockWebServer: 304 → `NotModified`; 403 → `Forbidden`; 500 → `Error(500)`; обрыв соединения → `Error(null)`; 200 с невалидным JSON → `Error`
- [ ] run tests - must pass before task 5

### Task 5: Room — entities, DAO, база

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/RaceEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/RaceDao.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/SyncMetaEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/SyncMetaDao.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`

- [ ] `RaceEntity` (`@Entity(tableName = "races")`, PK = серверный `id`) и `SyncMetaEntity` (`@Entity(tableName = "sync_meta", primaryKeys = ["origin", "resource"])`)
- [ ] `RaceDao`: `observeRaces(): Flow<List<RaceEntity>>` (`ORDER BY date DESC, id DESC`), `insertAll`, `deleteAll`, `@Transaction suspend fun replaceAll(races)` = deleteAll + insertAll
- [ ] `SyncMetaDao`: `getEtag(origin, resource): String?`, `@Upsert upsert(SyncMetaEntity)`
- [ ] `AppDatabase` (version = 1, entities = races + sync_meta) с companion-фабрикой `build(context)`
- [ ] тесты: не пишем для DAO (решение brainstorm); проверка — `./gradlew assembleDebug` (KSP-генерация Room проходит без ошибок)
- [ ] run build - must pass before task 6

### Task 6: RaceRepository

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/RaceRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/RaceRepositoryTest.kt`

- [ ] sealed/enum `RefreshResult`: `Updated | NotModified | Offline | Forbidden | HttpError(code)`
- [ ] `RaceRepository(apiClient, raceDao, syncMetaDao, origin)`: `val races = raceDao.observeRaces()`; `suspend fun refreshRaces()`: ETag из `sync_meta` → `fetchRaces` → `Success`: `raceDao.replaceAll`, затем `syncMetaDao.upsert(etag)`; маппинг `FetchResult` → `RefreshResult` (`Error(null)` → `Offline`, `Error(code)` → `HttpError`)
- [ ] порядок «данные → etag» двумя отдельными транзакциями **намеренный** (не объединять и не менять местами): падение между ними оставит свежие данные со старым etag → следующий refresh получит 200 и самовосстановится; обратный порядок дал бы новый etag со старыми данными навсегда
- [ ] маппер `RaceDto` → `RaceEntity`
- [ ] тесты с фейковыми in-memory DAO + ApiClient на MockWebServer: 200 → таблица замещена целиком (старые записи исчезли), etag сохранён; 304 → база не тронута; второй refresh шлёт сохранённый etag
- [ ] тесты: офлайн (обрыв) → `Offline`, база не тронута; 403 → `Forbidden`
- [ ] run tests - must pass before task 7

### Task 7: AppContainer, Application, запуск рефреша

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/InstallId.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/InstallIdTest.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] `InstallId`: логика «прочитать или сгенерировать UUID» как чистая функция над инжектируемым key-value-хранилищем (`getOrCreate(load: () -> String?, save: (String) -> Unit): String`); адаптер к SharedPreferences — тонкая обёртка
- [ ] `AppContainer(context)`: lazy `installId` (через `InstallId` + SharedPreferences), `OkHttpClient` + интерцептор (значения из `BuildConfig`), `Json`, `ApiClient`, `AppDatabase`, `RaceRepository`; `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- [ ] `Kolco24App : Application`: создаёт `AppContainer`, в `onCreate` — `applicationScope.launch { Log.i(..., container.raceRepository.refreshRaces().toString()) }`
- [ ] зарегистрировать `android:name=".Kolco24App"` в манифесте
- [ ] тесты `InstallId` (JVM, фейковое хранилище): генерирует один раз и сохраняет; при повторных вызовах возвращает то же значение; результат ≤64 символов (требование API)
- [ ] остальной контейнер — связывание без ветвлений, юнит-тестов не требует; проверка — сборка + Task 8 (ручной прогон)
- [ ] run `./gradlew assembleDebug testDebugUnitTest` - must pass before task 8

### Task 8: Verify acceptance criteria
- [ ] все требования Overview реализованы: подписанный GET с ETag, офлайн-чтение из Room, полное замещение при 200, ETag по origin
- [ ] edge cases: повторный запуск без изменений → `NotModified`; нет сети → `Offline`, кеш цел; 403 → `Forbidden`
- [ ] run full test suite: `./gradlew testDebugUnitTest`
- [ ] run `./gradlew lintDebug` — должен проходить (правило проекта)
- [ ] run `./gradlew assembleDebug`

### Task 9: [Final] Update documentation
- [ ] обновить CLAUDE.md: раздел архитектуры — слой данных (data/api, data/db, RaceRepository, AppContainer/Kolco24App), конфиг local.properties → BuildConfig
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**
- прописать боевые `kolco24.apiBaseUrl` / `kolco24.appKeyId` / `kolco24.appSecret` в `local.properties`
- запуск на устройстве: в logcat при первом старте `Updated`, при повторном — `NotModified`; в авиарежиме — `Offline`
- при массовых 403 сверить чек-лист отладки из `docs/API.md` (часы устройства, путь со слешем, hex lower-case)

**External system updates:**
- бэкенд видит установку в статистике по `X-Install-Id` / `X-App-Version` (попросить бэкендера подтвердить, что подпись принимается)
- экран «обновите приложение» на `Forbidden` — отдельная задача вместе с UI
