# Trusted Time Hardening — защита от изменения часов

## Overview

Реализация шести рекомендаций из ревью подсистемы доверенного времени. Цель — закрыть
основные сценарии обхода: офлайн-отметки с поддельным wall-временем, отсутствие
источника времени в локальном (LAN) режиме, деградация качества якоря, судейская
станция в NoSync на старте/финише.

Ключевые изменения:
1. **Backfill `trusted_ms` при выгрузке** marks/judge scans через `TrustedClock.trustedAt`
   (офлайн-отметки получают честное время в момент первого выхода в сеть — трек уже так делает).
2. **Исключить `kolco24.clock` из backup rules** (восстановленный на другом устройстве якорь
   может пройти warm start при коллизии `BOOT_COUNT`).
3. **Единая модель неопределённости якоря**: `ClockAnchor.uncertaintyMs` + правило замены
   «принимаем, если эффективная неопределённость кандидата ≤ текущей» (с учётом дрейфа по возрасту).
   Хороший якорь (RTT 50 мс) больше не затирается плохим (RTT 9.9 с).
4. **GPS-якорь**: `Location.time` + `Location.elapsedRealtimeNanos` — офлайн-источник
   доверенного времени (лес, локальный режим, восстановление после ребута без сети).
   Пассивные точки питания: фиксы во время записи трека и one-shot фикс КП-скана;
   вне их GPS-якорь доступен только вручную с судейского экрана (Task 6).
5. **Подписанный LAN time-эндпоинт** (HMAC + nonce поверх cleartext): LAN-сервер становится
   легитимным якорем в локальном режиме. Клиент + контракт; серверная часть — вне этого репо.
6. **Усиленное NoSync-предупреждение на судейском экране** старта/финиша + действия
   «проверить сеть» / «время по GPS».

## Context (from discovery)

- Ядро: `data/time/TrustedClock.kt` (чистая модель, `TrustedClockTest`),
  `data/time/ClockAnchorStore.kt` (атомарный однострочный prefs-формат),
  `data/api/ServerTimeInterceptor.kt` (tested, trust boundary).
- Запись времени: `MarkRepository.startKpTake`/photo-take и `JudgeScanRepository.record`
  сохраняют `TimeSample` → `takenAt`(wall)/`trustedTakenAt`/`elapsedRealtimeAt`/`bootCount`.
- Выгрузка: мапперы `toDto()` в `data/api/dto/MarkDtos.kt` и `JudgeScanDtos.kt` шлют
  **сохранённое** `trustedTakenAt` — null навсегда остаётся null. Трек-выгрузка уже
  использует `trustedAt` (см. `docs/design/UPLOAD.md`).
- Backup rules: `res/xml/backup_rules.xml` + `data_extraction_rules.xml` исключают
  `kolco24.install.xml` и `kolco24.admin.xml`, но **не** `kolco24.clock.xml`.
- LAN-клиент (`localApiClient` в `AppContainer`) сознательно без `ServerTimeInterceptor`
  (cleartext `Date` подделывается MITM'ом). Локальный режим — `SyncCoordinator`
  (`probeLocalAndRenew` — единственный heartbeat-примитив, 3 точки вызова).
- GPS: `data/track/` — location-движки + one-shot `CurrentLocationProvider`;
  `TrackRecordingService` — поток фиксов во время записи трека.
- Судейский экран: `ui/admin/JudgeScanScreen.kt` — сейчас показывает общий `ClockWarningBanner`
  при `!Ok`; чистая модель `JudgeScanModel`.
- Room-миграций **не требуется** (ни одна задача не меняет схему БД).
- Конвенции: чистые модели JVM-тестируются, Compose UI и Android-адаптеры — нет,
  trust boundaries (подпись, `ServerTimeInterceptor`) — тестируются. Lambda-seam DI.

## Development Approach

- **testing approach**: Regular (код, затем тесты в рамках той же задачи) — конвенция проекта.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  — в границах конвенции проекта: чистые модели и trust boundaries тестируются,
  Compose UI и Android-адаптеры — нет (для таких задач тестовый пункт покрывает
  чистую часть, вынесенную из адаптера).
- **CRITICAL: all tests must pass before starting next task** — no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./gradlew testDebugUnitTest` + `./gradlew lintDebug` after each task
- maintain backward compatibility (формат prefs-якоря, дефолтные параметры репозиториев,
  сервер без нового эндпоинта → тихий no-op)

## Testing Strategy

- **unit tests**: required for every task (в рамках конвенции — см. выше).
  Ключевые классы: `TrustedClockTest`, `ClockAnchorStoreTest`, `ServerTimeInterceptorTest`,
  новые `GpsTimeCandidateTest`, `LanTimeVerifierTest`, расширения тестов
  `MarkRepository`/`JudgeScanRepository` (fake-uploader'ы уже есть).
- **e2e tests**: в проекте нет UI-e2e; инструментальные тесты
  (`connectedDebugAndroidTest`) не затрагиваются — схема Room не меняется.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

Архитектурное решение (выбрано на этапе планирования): **единая модель неопределённости**.
`ClockAnchor` получает `uncertaintyMs`; каждый источник времени — HTTP `Date`, GPS-фикс,
LAN time-эндпоинт — производит «кандидата» с собственной оценкой неопределённости:

- сеть (HTTP `Date`): `rtt/2 + 500` (500 мс — секундная гранулярность заголовка);
- GPS-фикс: константа `GPS_UNCERTAINTY_MS = 500` (консервативно; пара
  `time`/`elapsedRealtimeNanos` фиксируется ОС в момент фикса);
- LAN time-эндпоинт: `rtt/2 + 50` (ответ несёт миллисекунды, без гранулярности `Date`).

Один код-путь принятия в `TrustedClock`: правила про отсутствие якоря / ребут остаются
безусловными, а внутри той же boot-сессии кандидат принимается, если его **эффективная**
неопределённость на момент `elapsedNow` не хуже текущей:

```
effective(a, elapsedNow) = a.uncertaintyMs + (elapsedNow − a.anchorElapsedMs) * DRIFT_PPM / 1_000_000
accept ⇔ effective(candidate) <= effective(current)
```

`DRIFT_PPM = 20` (≈1.7 с/сутки) — штраф за возраст: старый идеальный якорь со временем
проигрывает свежему среднему. Формула чистая и напрямую тестируется.

Backfill при выгрузке не мутирует строки (write-once сохраняется): `trusted_ms`
вычисляется в момент маппинга в DTO; сервер должен применять fill-if-null при
идемпотентном upsert'е (фиксируется в контракте UPLOAD.md).

## Technical Details

- **`ClockAnchor`**: `+ uncertaintyMs: Long`. Формат персиста — 5 сегментов
  `serverEpochMs|anchorElapsedMs|capturedWallMs|bootCount|uncertaintyMs`;
  legacy 4-сегментная строка читается с консервативным дефолтом `uncertaintyMs = 5_000`.
- **`TimeCandidate`** (новый чистый тип в `data/time/TrustedClock.kt`):
  `data class TimeCandidate(serverMs: Long, anchorElapsedMs: Long, uncertaintyMs: Long)`.
  `wallNow`/`bootNow` в тип НЕ входят — их снимает импурный адаптер в момент вызова,
  чистые мапперы (GPS/LAN) их не знают.
- **`TrustedClock.onServerTime` → `onTimeCandidate(candidate: TimeCandidate, wallNow, bootNow)`**
  (переименование + новый тип; правило (d) заменяется формулой effective-неопределённости).
  Кандидат с `anchorElapsedMs` в прошлом внутри той же сессии допустим (GPS-фикс) —
  формула симметрична по возрасту. ⚠️ Механическая правка тестов: ~26 вызовов в
  `TrustedClockTest` + арность лямбды в `ServerTimeInterceptorTest` — это не scope creep.
- **GPS-кандидат**: `RawFix` (`data/track/TrackModels.kt`) расширяется полями
  `isMock: Boolean = false` и `provider: String? = null` (заполняются в обоих
  `Location.toRawFix()`-мапперах движков; дефолты не ломают существующие тесты).
  Чистый маппер `gpsTimeCandidate(fix: RawFix): TimeCandidate?` — отсев: `isMock`,
  не-спутниковый фикс (`isSatelliteBacked`), `gpsTimeMs <= 0`, `accuracy > 100`;
  ⚠️ **Правка после ревью (iter1):** первоначальный фильтр `provider != "gps"` делал GPS-якорь
  мёртвым на GMS-устройствах (большинство телефонов) — `FusedLocationProviderClient`
  всегда штампует `provider = "fused"`, а обе точки питания (запись трека, one-shot
  КП-скан/кнопка «Время по GPS») идут через Fused при наличии GMS. Фильтр расширен до
  allowlist `{"gps", "fused"}`.
  ⚠️ **Правка после ревью (iter2, Finding E):** голый allowlist `{"gps","fused"}` вернул вектор
  подделки — WiFi/cell fused-фикс несёт wall-часы (`isMock=false` при смене часов в Настройках)
  и проходит грубый гейт точности. `isSatelliteBacked`: `"gps"` доверяем по имени (явный
  `GPS_PROVIDER`), `"fused"` — только при `altitude != null` (GNSS 3-D даёт высоту, WiFi/cell
  2-D — нет). Дешёвая корроборация, доступная с minSdk 24; закрывает вектор, сохраняя офлайн-якорь
  на GMS. Детали и остаточный риск — в «Осознанные ограничения».
  `anchorElapsedMs = elapsedRealtimeNanos / 1_000_000`. Врезки — у потребителей `RawFix`:
  фикс-путь `TrackRecordingService` и one-shot `CurrentLocationProvider` (КП-скан + Task 6).
- **Backfill**: в оба репозитория инжектится seam
  `trustedAt: (elapsedAt: Long, bootAt: Int?) -> Long?` (default `{ _, _ -> null }` —
  существующие тесты не трогаются); в upload-loop'е перед `toDto()`:
  `trustedMs = trustedTakenAt ?: trustedAt(elapsedRealtimeAt, bootCount)`.
  У `MarkEntity.elapsedRealtimeAt` nullable — null → без backfill.
- **LAN time контракт**: `GET /app/time/?nonce=<32-hex>` →
  `200 {"server_ms": <long>, "nonce": "<echo>"}` +
  заголовок `X-App-Signature: hex(HMAC_SHA256(APP_SECRET, "<nonce>|<server_ms>"))`.
  Клиент: свежий случайный nonce на запрос; проверка эха nonce и подписи
  (constant-time compare); RTT-коррекция как в `ServerTimeInterceptor`;
  `404`/ошибка/битая подпись → тихий no-op. Вызов: шаг внутри
  `SyncCoordinator.probeLocalAndRenew` (lambda seam) + действие с судейского баннера.
- **Судейский NoSync**: при `ClockStatus.NoSync` — крупная error-карточка над зоной
  сканирования (не блокирует скан — гонка важнее); действия: «Проверить сеть»
  (лёгкий signed GET к облаку — любой сетевой ответ ре-якорит через `Date`) и
  «Время по GPS» (one-shot `CurrentLocationProvider` → GPS-кандидат; с проверкой
  runtime-разрешения на локацию).

## What Goes Where

- **Implementation Steps** (`[ ]`): изменения кода, тестов и документации этого репозитория.
- **Post-Completion** (без чекбоксов): серверная часть LAN time-эндпоинта и fill-if-null
  семантика upsert'а, ручная полевая проверка, серверные фрод-проверки.

## Implementation Steps

### Task 1: Исключить якорь времени из бэкапа

**Files:**
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

- [x] добавить `<exclude domain="sharedpref" path="kolco24.clock.xml"/>` в `backup_rules.xml` (с комментарием-почему, по образцу соседних)
- [x] добавить то же исключение в оба блока (`cloud-backup`, `device-transfer`) `data_extraction_rules.xml`
- [x] тесты: не применимо (xml-ресурс, кода нет) — проверка через `./gradlew lintDebug`
- [x] run `./gradlew lintDebug` — must pass before next task

### Task 2: Backfill trusted_ms при выгрузке marks и judge scans

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/JudgeScanRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `docs/design/UPLOAD.md`
- Modify: `app/src/test/.../MarkRepository*Test.kt`, `app/src/test/.../JudgeScanRepositoryTest.kt`

- [x] добавить в конструкторы `MarkRepository` и `JudgeScanRepository` seam `trustedAt: (Long, Int?) -> Long?` с default `{ _, _ -> null }`
- [x] в upload-loop'ах backfill через **in-memory copy перед zero-arg `toDto()`** (вынесено в `backfillTrustedMs` для читаемости, семантика та же; judge scans: `elapsedRealtimeAt` non-null, без `let`); НЕ добавлять перегрузку `toDto(trustedMs)` (сломало бы `MarkDtoMappingTest`/`JudgeScanDtoTest`), строки БД не мутируются
- [x] в `AppContainer` прокинуть `trustedAt = trustedClock::trustedAt` в оба репозитория
- [x] обновить `docs/design/UPLOAD.md`: `trusted_ms` может стать non-null на повторной отправке того же `id` — сервер обязан применять fill-if-null при upsert'е
- [x] тесты: dto получает backfilled `trusted_ms` при null `trustedTakenAt` и живом якоре; сохранённый `trustedTakenAt` имеет приоритет; `trustedAt` вернул null (чужая boot-сессия) → `trusted_ms = null` (+ marks: null `elapsedRealtimeAt` → seam не вызывается)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: Модель неопределённости якоря (uncertaintyMs + правило замены)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/time/TrustedClock.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/time/ClockAnchorStore.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ServerTimeInterceptor.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/test/.../TrustedClockTest.kt`, `ClockAnchorStoreTest.kt`, `ServerTimeInterceptorTest.kt`

- [x] `ClockAnchor`: добавить `uncertaintyMs: Long` (default `LEGACY_UNCERTAINTY_MS` — legacy warm-start + не-time тесты типа `TrackRepositoryTest` не трогаются); новый чистый тип `TimeCandidate(serverMs, anchorElapsedMs, uncertaintyMs)` рядом в `TrustedClock.kt`; константы `DRIFT_PPM = 20`, `DATE_HEADER_GRANULARITY_MS = 500`, `LEGACY_UNCERTAINTY_MS = 5_000`
- [x] `ClockAnchorStore`: писать 5 сегментов; `read()` принимает и 4 (legacy → `LEGACY_UNCERTAINTY_MS`), и 5 сегментов; битый 5-й сегмент → null
- [x] `TrustedClock`: переименовать `onServerTime` → `onTimeCandidate(candidate: TimeCandidate, wallNow, bootNow)`; заменить правило (d) на сравнение эффективных неопределённостей `effective(a) = a.uncertaintyMs + abs(Δelapsed) * DRIFT_PPM / 1_000_000` (правила «нет якоря»/«ребут» — без изменений, безусловный accept); разрешить `anchorElapsedMs` в прошлом внутри той же сессии. ⚠️ Уточнение: на мс-масштабе drift-член усекается в 0, поэтому равные-по-качеству якоря дают tie — добавлен monotonic tie-break (`anchorElapsedMs >=`), чтобы сохранить отброс поздних out-of-order дубликатов (иначе `scrambledOrder`/`outOfOrder` тесты ломались)
- [x] `ServerTimeInterceptor`: собирать `TimeCandidate(serverMs, midpoint, rtt / 2 + DATE_HEADER_GRANULARITY_MS)`; обновить callback-сигнатуру и проводку в `AppContainer`
- [x] механически обновить существующие вызовы: ~26 мест в `TrustedClockTest` (через test-локальный `onServerTime`-shim с default uncertainty 500) + арность лямбды `onServerTime = { candidate, w, b -> ... }` в `ServerTimeInterceptorTest` (единственный production-вызов — `AppContainer`)
- [x] тесты `TrustedClockTest`: хороший якорь не затирается плохим кандидатом; плохой затирается хорошим; старый хороший проигрывает свежему среднему при достаточном Δelapsed (штраф за дрейф) + обратный кейс (мал Δelapsed → старый хороший держится); кандидат с `anchorElapsed` в прошлом принимается при лучшей effective-неопределённости; ребут-правила остались безусловными (хуже-кандидат принят после регрессии)
- [x] тесты `ClockAnchorStoreTest`: roundtrip 5 сегментов (write→5 сегментов + preseeded); legacy 4 сегмента → дефолтная неопределённость; битый 5-й сегмент → null; `tooManyFields` → 6 сегментов
- [x] тесты `ServerTimeInterceptorTest`: uncertainty = rtt/2 + 500 у принятого кандидата (два кейса)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 4: GPS-якорь времени

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/time/GpsTimeCandidate.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/time/GpsTimeCandidateTest.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt` (`RawFix` + `isMock`/`provider`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/FusedLocationEngine.kt` (`toRawFix`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyLocationEngine.kt` (`toRawFix`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt` (врезка в фикс-путь)
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` (врезка в one-shot путь КП-скана)
- Modify: `app/src/test/.../TrackModels*Test.kt` (если затронут `RawFix`-фикстуры)

- [x] `RawFix`: добавить `isMock: Boolean = false`, `provider: String? = null` (дефолты — существующие тесты и call sites не ломаются); заполнить оба поля в `Location.toRawFix()` (единый shared-маппер в `FusedLocationEngine.kt`, `LegacyLocationEngine` вызывает его же — второй копии нет) (`isMock`: API 31+ `location.isMock`, ниже — `isFromMockProvider`)
- [x] чистый маппер `gpsTimeCandidate(fix: RawFix): TimeCandidate?` в `GpsTimeCandidate.kt` — отсев `isMock`, `provider != "gps"`, `gpsTimeMs <= 0`, `accuracy > 100`; `anchorElapsedMs = elapsedRealtimeNanos / 1_000_000`; `GPS_UNCERTAINTY_MS = 500`
- [x] врезка 1 (запись трека): в фикс-пути `TrackRecordingService` (в `applicationScope.launch` перед `insertAll`) `fixes.forEach { container.anchorTrustedTimeFromGps(it) }` — правило замены само отбросит худшие
- [x] врезка 2 (one-shot фикс КП-скана): `currentLocationProvider` в `AppContainer` обёрнут декоратором, вызывающим `anchorTrustedTimeFromGps` на каждом свежем фиксе (общий seam `anchorTrustedTimeFromGps` снимает wall+boot); этим же путём пойдёт кнопка Task 6 — потребители в `MainActivity` не трогаются
- [x] тесты `GpsTimeCandidateTest` (JVM, чистый маппер): валидный фикс → кандидат с верными ms; mock / плохая accuracy / нулевое время / не-gps provider → null (+ граница accuracy принимается)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 5: Подписанный LAN time-эндпоинт (клиент + контракт)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/time/LanTimeVerifier.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/time/LanTimeVerifierTest.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt` (+ DTO в `data/api/dto/`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/sync/SyncCoordinator.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `docs/design/UPLOAD.md` (контракт эндпоинта)
- Modify: `app/src/test/.../SyncCoordinatorTest.kt`, `app/src/test/.../ApiClient*Test.kt`

- [x] задокументировать контракт в `docs/design/UPLOAD.md`: `GET /app/time/?nonce=<32-hex>` → `{"server_ms", "nonce"}` + `X-App-Signature = hex(HMAC_SHA256(APP_SECRET, "<nonce>|<server_ms>"))`; требования к серверу (эхо nonce, ms-точность)
- [x] чистый `LanTimeVerifier`: генерация nonce (инжектируемый RNG), проверка эха и HMAC constant-time-сравнением (`MessageDigest.isEqual`, переиспользует `sign()` из `AppSignatureInterceptor`), маппинг в кандидата `uncertaintyMs = rtt/2 + 50` (`LAN_TIME_GRANULARITY_MS`); никогда не бросает (RTT-гейт `0..maxRttMs` как у `ServerTimeInterceptor`)
- [x] `ApiClient.fetchLanTime(nonce)`: сырой ответ (`LanTimeResponse` = body `LanTimeDto` + заголовок `X-App-Signature`); `404`/сеть/битый JSON → null
- [x] `SyncCoordinator`: seam `syncLanTime: suspend () -> Unit` (default no-op), вызывается **вне leaseMutex** после reachable LAN-пробинга (`manifest != null`) в `probeLocalAndRenew`; проводка в `AppContainer`: elapsed-замер вокруг `localApiClient.fetchLanTime` → `verifier.verify` → `trustedClock.onTimeCandidate`
- [x] тесты `LanTimeVerifierTest`: валидная подпись → кандидат; подделанный `server_ms` / чужой nonce / неверный ключ / null dto / null sig → null; RTT-коррекция и uncertainty (rtt/2+50), граница RTT, upper-case подпись принимается, nonce = 32-hex
- [x] тесты `SyncCoordinatorTest`: `syncLanTime` вызывается после reachable пробинга (в т.ч. cloud-handback) и после применения lease, не вызывается при недоступном LAN; тесты `ApiClientTest`: `fetchLanTime` 200/подпись/nonce-query, missing header, 404, битый JSON, обрыв → null
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 6: Усиленный NoSync на судейском экране + действия синхронизации

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/JudgeScanScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/JudgeScanModel.kt` (чистая часть, если появляется решение-логика)
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (проводка действий)

- [x] при `ClockStatus.NoSync` показывать крупную error-карточку (M3 `errorContainer`) над зоной сканирования: «Время не подтверждено — синхронизируйте до начала работы»; сканирование НЕ блокировать (`JudgeScanNoSyncCard`)
- [x] действие «Проверить сеть»: лёгкий signed GET к облаку (`apiClient.fetchSync(raceId)` — любой ответ ре-якорит через `Date`); показать результат (якорь появился / сети нет) через `clockAnchored(status)` → `NoSyncActionOutcome`
- [x] действие «Время по GPS»: one-shot `container.currentLocationProvider.current()` (декоратор из Task 4 seam 2 сам ре-якорит через `anchorTrustedTimeFromGps`); проверка runtime-разрешения на локацию (`rememberLauncherForActivityResult` + `ContextCompat.checkSelfPermission`), показ результата
- [x] `Skewed` оставить текущему `ScanClockBanner`; карточка — только для `NoSync` (перешёл на `when (clockStatus)`)
- [x] тесты: чистая решение-логика вынесена в `JudgeScanModel` (`clockAnchored`, `noSyncActionMessage`, `NoSyncActionOutcome`) — JVM-тест в `JudgeScanModelTest`; Compose-обвязка без тестов (конвенция)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 7: Verify acceptance criteria

- [x] verify: офлайн-сценарий (запись без якоря → якорь появился → выгрузка) даёт non-null `trusted_ms` в DTO обоих репозиториев — `MarkRepositoryUploadTest.backfill_nullTrustedTakenAt_liveAnchor_dtoGetsComputedTrustedMs` + `JudgeScanRepositoryTest.backfill_nullTrustedTakenAt_liveAnchor_dtoGetsComputedTrustedMs` (плюс seam-null/precedence кейсы в обоих)
- [x] verify: плохой RTT-кандидат не затирает хороший якорь; GPS-кандидат принимается при NoSync — `TrustedClockTest.uncertainty_goodAnchorNotOverwrittenByBadCandidate` (bad RTT rejected) + `GpsTimeCandidateTest.validGpsFix_mapsToCandidate` (GPS-фикс → кандидат) + `TrustedClockTest.uncertainty_pastAnchorElapsedCandidate_acceptedWhenBetterEffective` / `persist_calledOnAccept` (кандидат принят при пустом/худшем якоре = NoSync)
- [x] verify: legacy-якорь (4 сегмента) читается после «обновления» формата — `ClockAnchorStoreTest.read_legacyFourSegments_defaultsUncertainty` + `read_reflectsPreSeededStore` (4 сегмента → `LEGACY_UNCERTAINTY_MS`)
- [x] verify: LAN time с битой подписью игнорируется; `404` — тихий no-op — `LanTimeVerifierTest.tamperedServerMs_isRejected`/`wrongKey_isRejected`/`mismatchedNonce_isRejected`/`nullSignature_isRejected` (битая подпись → null кандидат) + `ApiClientTest.fetchLanTime_404_returnsNull`/`fetchLanTime_connectionDrop_returnsNull`/`fetchLanTime_invalidJson_returnsNull` (тихий no-op)
- [x] run full test suite: `./gradlew testDebugUnitTest` — passed (BUILD SUCCESSFUL)
- [x] run `./gradlew lintDebug` — passed (BUILD SUCCESSFUL)
- [x] connectedDebugAndroidTest (skipped - no emulator/device available; Room schema unchanged so no migration regression risk)

### Task 8: [Final] Update documentation

- [x] обновить `docs/design/DATA-NOTES.md`: `TrustedClock` (onTimeCandidate, uncertainty-модель, DRIFT_PPM), `ClockAnchorStore` (5-сегментный формат + legacy), `ServerTimeInterceptor` (uncertainty), GPS-кандидат, `LanTimeVerifier`, backfill-seam обоих репозиториев, `SyncCoordinator.syncLanTime`
- [x] обновить `docs/design/UI-NOTES.md`: NoSync-карточка и действия `JudgeScanScreen`
- [x] обновить `CLAUDE.md`: строки module map (`data/time/`, `ServerTimeInterceptor`, репозитории), упоминание backup-исключения
- [x] move this plan to `docs/plans/completed/`

## Post-Completion

*Требует действий вне этого репозитория / ручной проверки — без чекбоксов.*

**Серверная часть** (бэкенд kolco24):
- реализовать `GET /app/time/` на LAN-сервере по контракту из `docs/design/UPLOAD.md` (эхо nonce, HMAC-подпись, ms-точность);
- fill-if-null семантика upsert'а `trusted_ms` для `/marks/` и `/judge_scans/` (повторная отправка того же `id` с non-null `trusted_ms` должна заполнять пропуск);
- фрод-проверки (дёшево, ловит остальное): флаг на старт/финиш с `trusted_ms == null`; флаг на большой `|wall_ms − trusted_ms|`; сверка согласованности `Δelapsed_at ↔ Δtrusted_ms` внутри одной boot-сессии; кросс-чек self-mark ↔ judge-скан ↔ GPS-трек.

**Manual verification** (полевая):
- телефон в авиарежиме с загрузки → отметка → сеть → выгрузка → на сервере non-null `trusted_ms`;
- судейский экран в лесу: NoSync-карточка, «время по GPS» устанавливает якорь;
- локальный режим: после пробинга LAN якорь появляется от time-эндпоинта;
- перевод часов вперёд/назад при живом якоре — `trusted_ms` не смещается, баннер Skewed показывается.

**Осознанные ограничения** (фиксируем, не чиним):
- root / перепаковка APK / извлечённый `APP_SECRET` обходят всю схему — потолок клиентской защиты, реальная последняя линия — серверные фрод-проверки выше;
- mock-location поднимает барьер подделки GPS-времени, но не устраняет его полностью.
- **Fused-провайдер и подделка wall-часов (Finding E, review iter2).** На GMS-устройстве
  `FusedLocationProviderClient` штампует `provider = "fused"` на *каждый* фикс, включая
  WiFi/cell-смешанные, у которых `Location.time == системные wall-часы` (их пользователь меняет в
  Настройках Android **без** mock-location-приложения, поэтому `isMock` остаётся `false`), а
  точность WiFi (~20–60 м) проходит грубый гейт `accuracy ≤ 100 м`. Такой фикс мог бы заякорить
  `TrustedClock` на поддельное время и, по правилу эффективной неопределённости, затереть
  легитимный облачный якорь. **Закрыто в `gpsTimeCandidate.isSatelliteBacked`:** `"gps"` доверяем
  по имени провайдера (явный `GPS_PROVIDER` — гарантия ОС о спутниковом источнике); `"fused"`
  принимаем **только** при `altitude != null` — GNSS даёт 3-D фикс с высотой, WiFi/cell 2-D — нет.
  Дешёвая корроборация, доступная с minSdk 24 (`Location.hasAltitude()`; `verticalAccuracyMeters`
  сильнее, но API 26+ — не требуем, иначе якорь мёртв на API 24/25). Остаточный риск: подделанный
  GNSS-эмулятор со «здоровым» фиксом (высота + точность + не-mock) всё ещё возможен на
  скомпрометированном устройстве — это тот же потолок клиентской защиты, что и первый пункт;
  реальная последняя линия — серверные фрод-проверки (Post-Completion).
