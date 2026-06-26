# Координата места при взятии КП (анти-чит)

## Overview
- При взятии checkpoint (КП) записывать GPS-координату места отметки на строку взятия (`MarkEntity`).
- Цель — **анти-чит / проверка**: подтвердить, что команда физически была на КП. Нужна свежая и точная координата именно в момент отметки + метаданные качества (accuracy, возраст фикса), чтобы сервер мог судить о достоверности.
- Подход (Вариант A из брейншторма): **всегда** активный one-shot GPS-запрос на момент отметки; координата дописывается в уже созданную строку взятия **асинхронно** (двухфазно, как `present`). Работает даже когда запись трека выключена.
- Интегрируется в существующую двухфазную модель такта (`startKpTake` → строка живёт сразу, дополняется позже) и переиспользует `RawFix` + паттерн `LocationEngineFactory`.

## Context (from discovery)
- **Строка взятия:** `MarkRepository.startKpTake` (`app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`) создаёт строку с UUID; потом `addMember` её дополняет. `MarkDao.addMember` (`app/.../data/db/MarkDao.kt`) — образец `@Transaction`-апдейта по `id`.
- **Точка врезки:** `MainActivity.kt`, ветка `is ScanEvent.Kp ->` (~строки 1000–1037), внутри `if (expired || scanTake.markId == null || scanTake.checkpointId != event.checkpointId)`, сразу после `scanTake.markId = id`. Только для **новой** строки взятия (re-stamp того же КП и `addMember` координату не трогают).
- **`RawFix`** (`app/.../data/track/TrackModels.kt`): `lat, lon, accuracy, altitude:Double?, verticalAccuracyMeters:Float?, gpsTimeMs, elapsedRealtimeNanos`. Переиспользуем как носитель фикса.
- **Образец seam:** `LocationEngine` + `LocationEngineFactory` с чистым `chooseEngineType(gmsAvailable): EngineType` (JVM-тест `LocationEngineFactoryTest`). Fused-зависимость (`play-services-location`) уже подключена (`FusedLocationEngine.kt`).
- **БД:** Room `version = 1`, единственная committed-схема `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/1.json`, миграций нет (свёрнутая история, единственный dev-девайс). Изменение `MarkEntity` → регенерация `1.json` + очистка данных / переустановка приложения (это норма для проекта; миграции вернут, когда появятся реальные пользователи).
- **Permission:** в track-фиче (wiring `TrackCard` в `MainActivity`) уже есть `trackPermissionLauncher` с `ACCESS_FINE_LOCATION` — **но его callback стартует `TrackRecordingService` на грант** (`MainActivity.kt:671`), поэтому для отметок нужен **отдельный** launcher. Существующие паттерны для переиспользования: флаг `hasRequestedLocation` (`MainActivity.kt:642`), permanent-denial → `ACTION_APPLICATION_DETAILS_SETTINGS` + `Uri.fromParts` (`MainActivity.kt:1554`), device-location-off → `ACTION_LOCATION_SOURCE_SETTINGS` (`MainActivity.kt:1532`). Нужна только **foreground**-локация (отметка всегда на переднем плане; `ACCESS_BACKGROUND_LOCATION` не нужен).

## Development Approach
- **Testing approach: Regular** (код, потом тесты в той же задаче) — конвенция репозитория.
- Конвенция тестов: чистое ядро (pure helpers, мапперы, репозитории с фейковым DAO) — юнит-тесты; Android-адаптеры (Fused/Legacy провайдеры) и Compose/`MainActivity` — **не** покрываются.
- Завершать каждую задачу полностью (вкл. тесты) до перехода к следующей; маленькие фокусные изменения; все тесты зелёные перед следующей задачей.
- Обновлять этот файл при изменении объёма.

## Testing Strategy
- **Unit (JVM):**
  - `MarkRepositoryTest` (фейковый `MarkDao`): `attachLocation` пишет все `loc*`-колонки из `RawFix`; `fix == null` → no-op; маппинг `elapsedRealtimeNanos → locElapsedRealtimeAt` (деление на 1_000_000); повторное взятие = новая строка получает свой захват.
  - `LocationEngineFactoryTest` (или новый `CurrentLocationProviderFactoryTest`): переиспользование/проверка `chooseEngineType` (Fused при GMS, иначе Legacy).
- **Не покрываем:** `FusedCurrentLocationProvider` / `LegacyCurrentLocationProvider` (реальные адаптеры), wiring в `MainActivity`, nudge-карточку (Compose).
- **Схема БД:** после изменения `MarkEntity` перегенерировать `1.json` (KSP exportSchema); сборка падает, если committed-схема расходится с сгенерированной.
- **Команды:** `./gradlew testDebugUnitTest`, `./gradlew lintDebug`.

## Progress Tracking
- `[x]` сразу по завершении пункта.
- ➕ — новые обнаруженные задачи; ⚠️ — блокеры.
- Держать план в синхроне с фактом.

## Solution Overview
1. **Хранение:** nullable `loc*`-колонки прямо на `MarkEntity` (без отдельной таблицы, без `locSource` — при варианте A источник всегда one-shot; «фикса нет» = `lat == null`).
2. **Провайдер:** новый seam `CurrentLocationProvider` (one-shot, без foreground-сервиса), Fused/Legacy реализации, выбор через `chooseEngineType`.
3. **Врезка:** в ветке `ScanEvent.Kp` после рождения строки — fire-and-forget `applicationScope.launch { attachLocation(id, provider.current()) }`.
4. **Разрешение:** nudge ведёт (карточка до гонки в empty-state «Отметок»), системный запрос — fallback при первом открытии scan-оверлея, если проигнорировали.

## Technical Details
- **`loc*`-колонки `MarkEntity`** (все nullable, дефолт `null` — additive; единый `loc*`-namespace, чтобы не путать с `TrackPointEntity.lat/lon` при grep):
  - `locLat: Double?`, `locLon: Double?` — координата (null = фикс не получен).
  - `locAccuracy: Float?` — горизонтальная точность (метры), ключ для анти-чита.
  - `locAltitude: Double?` — высота (`RawFix.altitude`).
  - `locVerticalAccuracy: Float?` — вертикальная точность (`RawFix.verticalAccuracyMeters`); без неё высота — слабый сигнал для анти-чита, поэтому храним обе.
  - `locGpsTimeMs: Long?` — спутниковое время фикса (`RawFix.gpsTimeMs`).
  - `locElapsedRealtimeAt: Long?` — монотонный момент фикса (`RawFix.elapsedRealtimeNanos / 1_000_000`); вместе с уже существующим `MarkEntity.elapsedRealtimeAt` взятия даёт **возраст фикса** (Δ) для анти-чита.
- **`CurrentLocationProvider`** (`data/track/`):
  ```kotlin
  fun interface CurrentLocationProvider {
      /** Один свежий фикс или null (таймаут / нет провайдера / нет разрешения). Никогда не бросает. */
      suspend fun current(timeoutMs: Long = 8_000): RawFix?
  }
  ```
  - `FusedCurrentLocationProvider`: `getCurrentLocation(CurrentLocationRequest, cancellationToken)` (НЕ priority-overload — он может вернуть кэшированный фикс, что подрывает анти-чит). Запрос: `CurrentLocationRequest.Builder().setPriority(PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(0).setDurationMillis(timeoutMs).build()` — `setMaxUpdateAgeMillis(0)` форсит **свежий** фикс (без кэша), `setDurationMillis` = таймаут на стороне GMS. Обёрнут в `suspendCancellableCoroutine`; `SecurityException`/ошибка/таймаут → `null`.
  - `LegacyCurrentLocationProvider`: API ≥ 30 — `LocationManager.getCurrentLocation`; API 24–29 — разовый listener `GPS_PROVIDER` + `NETWORK_PROVIDER` fallback + таймаут. `Location → RawFix` маппинг как в track-движках (`hasAltitude()`/`hasVerticalAccuracy()` → nullable). **Проверка свежести:** `getCurrentLocation`/listener тоже могут отдать недавний кэш — отбрасывать фикс, чей возраст (`(SystemClock.elapsedRealtimeNanos() − location.elapsedRealtimeNanos)/1e6`) больше документированного порога (напр. `MAX_FIX_AGE_MS = 10_000`) → `null` (для анти-чита лучше «нет координаты», чем устаревшая).
  - Выбор: переиспользовать `LocationEngineFactory.chooseEngineType(gmsAvailable)` + тонкий `create(context): CurrentLocationProvider` (непокрытый адаптер).
- **`MarkRepository.attachLocation`:**
  ```kotlin
  suspend fun attachLocation(markId: String, fix: RawFix?) {
      if (fix == null) return
      markDao.attachLocation(markId, fix.lat, fix.lon, fix.accuracy,
          fix.altitude, fix.verticalAccuracyMeters, fix.gpsTimeMs,
          fix.elapsedRealtimeNanos / 1_000_000)
  }
  ```
- **`MarkDao.attachLocation`** — **column-scoped** `@Query("UPDATE marks SET locLat=:lat, locLon=:lon, locAccuracy=:acc, locAltitude=:alt, locVerticalAccuracy=:vacc, locGpsTimeMs=:gps, locElapsedRealtimeAt=:elapsed WHERE id=:id")`, пишет **только** 7 `loc*`-колонок.
  - ⚠️ **НЕ** делать full-row read-modify-write (не как `addMember`, который читает строку и `upsert`-ит её целиком). `attachLocation` запускается fire-and-forget на `applicationScope` **параллельно** с `addMember`-вызовами в окне; full-row запись устроила бы lost-update — поздний `attachLocation` откатил бы `present`/`complete`, или `addMember` затёр бы координату. Поскольку `addMember` — `@Transaction`, column-scoped `UPDATE` сериализуется вокруг неё атомарно (SQLite не даст ему вклиниться в середину транзакции) и трогает только `loc*`-колонки → гонки нет.
- **Врезка (MainActivity, `ScanEvent.Kp`)** после `scanTake.markId = id`:
  ```kotlin
  container.applicationScope.launch {
      val fix = container.currentLocationProvider.current()
      markRepo.attachLocation(id, fix)
  }
  ```

## What Goes Where
- **Implementation Steps** (`[ ]`): код, тесты, регенерация схемы — всё в этом репозитории.
- **Post-Completion** (без чекбоксов): ручная проверка на устройстве (реальный GPS-фикс на КП), очистка данных приложения после смены схемы, будущая серверная сторона анти-чита (потребление координат при выгрузке марок).

## Implementation Steps

### Task 1: Добавить `loc*`-колонки на `MarkEntity` + регенерация схемы

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Modify: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/1.json` (регенерируется сборкой)

- [x] добавить nullable-поля с дефолтом `null`: `locLat: Double?`, `locLon: Double?`, `locAccuracy: Float?`, `locAltitude: Double?`, `locVerticalAccuracy: Float?`, `locGpsTimeMs: Long?`, `locElapsedRealtimeAt: Long?`
- [x] KDoc к новым полям (зачем для анти-чита: `locAccuracy`/`locVerticalAccuracy` + возраст фикса через `locElapsedRealtimeAt` vs `elapsedRealtimeAt`)
- [x] собрать (`./gradlew assembleDebug`) чтобы KSP перегенерировал `1.json`; убедиться, что committed-схема обновилась и сборка зелёная (если `1.json` не обновился из-за stale KSP-кэша — `./gradlew clean` и пересобрать)
- [x] (тестов нет — чистая data-class; покрытие маппинга идёт в Task 3)
- [x] `./gradlew lintDebug` — зелёный перед следующей задачей

### Task 2: Seam `CurrentLocationProvider` + Fused/Legacy реализации + фабрика

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/CurrentLocationProvider.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/FusedCurrentLocationProvider.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyCurrentLocationProvider.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngineFactory.kt`
- Modify/Create: `app/src/test/java/.../data/track/LocationEngineFactoryTest.kt`

- [x] объявить `fun interface CurrentLocationProvider { suspend fun current(timeoutMs: Long = 8_000): RawFix? }` с KDoc (никогда не бросает; null-семантика; **свежесть** — кэшированные/устаревшие фиксы отбрасываются) — реализовано как обычный `interface` (Kotlin запрещает дефолт у SAM `fun interface`)
- [x] `FusedCurrentLocationProvider`: `getCurrentLocation(CurrentLocationRequest, token)` с `setMaxUpdateAgeMillis(0)` + `setPriority(PRIORITY_HIGH_ACCURACY)` + `setDurationMillis(timeoutMs)` → `suspendCancellableCoroutine`; `Location → RawFix`; все ошибки/таймаут → `null`
- [x] `LegacyCurrentLocationProvider`: API ≥ 30 `getCurrentLocation`, API 24–29 разовый listener GPS + NETWORK fallback + таймаут; `Location → RawFix`; ошибки → `null`; **отбросить фикс старше `MAX_FIX_AGE_MS` по `elapsedRealtimeNanos`** → `null`
- [x] добавить в `LocationEngineFactory` (или рядом) `createCurrentLocationProvider(context): CurrentLocationProvider`, выбор через существующий `chooseEngineType(gmsAvailable)`
- [x] тест фабрики: `chooseEngineType(true) == Fused`, `chooseEngineType(false) == Legacy` (оба кейса уже были; обновлён KDoc — выбор теперь общий для обеих фабрик)
- [x] `./gradlew testDebugUnitTest` — зелёный перед следующей задачей

### Task 3: `attachLocation` в DAO и репозитории + тесты

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/test/java/.../data/MarkRepositoryTest.kt`

- [x] `MarkDao.attachLocation(id, lat, lon, accuracy, altitude, verticalAccuracy, gpsTimeMs, elapsedRealtimeAt)` — **column-scoped** `@Query` UPDATE по `id`, пишет **только** 7 `loc*`-колонок (НЕ full-row upsert — см. concurrency-нота в Technical Details: гонка с `addMember`)
- [x] `MarkRepository.attachLocation(markId, fix: RawFix?)`: `fix == null` → return; иначе вызвать DAO, передав `fix.elapsedRealtimeNanos / 1_000_000` в `locElapsedRealtimeAt`
- [x] обновить фейковый `MarkDao` в тесте (реализовать `attachLocation`)
- [x] тест: `attachLocation` пишет все 7 значений из `RawFix` на нужную строку, не трогая `present`/`complete`/времена взятия
- [x] тест: `fix == null` → строка без координаты (no-op), остальные поля нетронуты
- [x] тест: маппинг `elapsedRealtimeNanos → locElapsedRealtimeAt` (деление на 1_000_000); повторное взятие того же КП = новая строка получает свой захват независимо
- [x] `./gradlew testDebugUnitTest` — зелёный перед следующей задачей

### Task 4: Wiring провайдера в `AppContainer` + захват в scan-пути

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] в `AppContainer` добавить ленивый `currentLocationProvider: CurrentLocationProvider = LocationEngineFactory.createCurrentLocationProvider(context)` рядом с track-зависимостями
- [x] в `MainActivity`, ветка `ScanEvent.Kp`, сразу после `scanTake.markId = id` (только в блоке создания **новой** строки): `container.applicationScope.launch { markRepo.attachLocation(id, container.currentLocationProvider.current()) }`
- [x] убедиться, что захват **не** запускается на re-stamp того же КП и на `addMember` (внутри `if (expired || markId == null || cp changed)`, после `scanTake.markId = id`)
- [x] (тестов нет — wiring/Compose не покрываются по конвенции; логика покрыта в Task 3)
- [x] `./gradlew lintDebug` + `./gradlew assembleDebug` — зелёные перед следующей задачей (попутно: `@RequiresApi(R)` на `LegacyCurrentLocationProvider.requestModern` — lint не гонялся в Task 2)

### Task 5: Разрешение на локацию — системный запрос при первом скане (fallback)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

> Манифест не трогаем — `ACCESS_FINE/COARSE_LOCATION` уже объявлены для track-фичи, используем только foreground-локацию (`ACCESS_BACKGROUND_LOCATION` не нужен).

- [x] ⚠️ **отдельный** `scanPermissionLauncher` (НЕ переиспользовать `trackPermissionLauncher` — его callback на грант вызывает `TrackRecordingService.start(...)`, см. `MainActivity.kt:671`; запрос локации ради отметки не должен запускать запись трека). Новый launcher только обновляет состояние разрешения (+ общий флаг + permanent-denial-ветка), трек не стартует.
- [x] переиспользовать существующий `hasRequestedLocation` (`rememberSaveable`, `MainActivity.kt:642`) как «уже спрашивали» — разрешение локации одно на ОС, общий флаг корректен для обоих путей
- [x] при первом открытии scan-оверлея (`showScan` → true), если `FINE/COARSE` не выдано и `!hasRequestedLocation` — один раз запустить `scanPermissionLauncher`
- [x] не блокировать скан/отметку при отказе (провайдер вернёт `null`, строка без координаты)
- [x] (тестов нет — Compose/permission flow не покрываются)
- [x] `./gradlew lintDebug` — зелёный перед следующей задачей

### Task 6: Nudge-карточка «Разрешите локацию для подтверждения КП» (ведущая, до гонки)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] в `MarksEmpty` добавить состояние/карточку nudge: показывать, когда локация **не** выдана (новый параметр `locationGranted: Boolean` + `onRequestLocation`; `LocationNudge` в ready-ветке над `TrackNudge`, только при `!locationGranted`)
- [x] кнопка → системный запрос (тот же `scanPermissionLauncher` из Task 5 через `onRequestMarkLocation`); при «не спрашивать снова» (permanent denial) — переиспользован существующий `showLocationDeniedDialog` → `ACTION_APPLICATION_DETAILS_SETTINGS` + `Uri.fromParts("package", packageName, null)` (паттерн `MainActivity.kt`, **НЕ** `ACTION_NFC_SETTINGS`/`ACTION_LOCATION_SOURCE_SETTINGS`)
- [x] прокинуть `locationGranted` из `MainActivity` (Activity-поле `locationGranted`, `ContextCompat.checkSelfPermission`, пересчёт в `onResume`), вписан в ready-ветку empty-state без ломки прочих веток
- [x] (тестов нет — Compose; чистой логики ветвления, требующей юнит-теста, не добавляется)
- [x] `./gradlew lintDebug` — зелёный перед следующей задачей

### Task 7: Verify acceptance criteria
- [x] координата пишется на **новую** строку взятия при включённом и при выключенном треке (захват через независимый one-shot `currentLocationProvider` в блоке создания новой строки `MainActivity.kt:1105`, не зависит от состояния трека; физический прогон — Post-Completion)
- [x] фикс **свежий** (Fused `setMaxUpdateAgeMillis(0)` `FusedCurrentLocationProvider.kt:33`, Legacy — отбрасывание по `MAX_FIX_AGE_MS` через `elapsedRealtimeNanos` `LegacyCurrentLocationProvider.kt:105`); устаревший кэш не записывается
- [x] запрос разрешения для отметки **не** запускает запись трека (`scanPermissionLauncher` только обновляет состояние разрешения; `TrackRecordingService.start` есть только в `trackPermissionLauncher`)
- [x] нет разрешения / GPS off / нет провайдера / таймаут → строка без координаты, без сбоев (провайдер возвращает `null`, `attachLocation` — no-op; тесты `attachLocation_nullFix_isNoOp`, `attachLocation_missingRow_isNoOp`)
- [x] несколько взятий подряд и переключение КП в окне → каждый захват дописывает свою строку, перезаписи нет (захват по `id` новой строки; тест `attachLocation_repeatTakeOfSamePoint_eachRowGetsOwnFix`)
- [x] nudge показывается до гонки при отсутствии разрешения; системный запрос — один раз при первом скане, если проигнорировали (`LocationNudge` в ready-ветке при `!locationGranted`; `LaunchedEffect(showScan)` спрашивает один раз через `hasRequestedLocation`)
- [x] полный прогон: `./gradlew testDebugUnitTest` + `./gradlew lintDebug` зелёные (exit 0)

### Task 8: [Final] Документация
- [x] обновить `CLAUDE.md`: `MarkEntity` (`loc*`-колонки), `MarkRepository.attachLocation`, `MarkDao.attachLocation`, новый `CurrentLocationProvider`/Fused/Legacy, врезка в `ScanEvent.Kp`, nudge в `MarksScreen`, схема (регенерация `1.json` + очистка данных)
- [x] переместить план в `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification:**
- Реальный прогон на устройстве с NFC + GPS: взять КП на улице, проверить, что `lat/lon/accuracy` записались и точность адекватная; проверить тёплый (трек включён) и холодный (трек выключен) GPS.
- Проверить поведение при отказе в разрешении и при выключенном GPS (строка без координаты, приложение стабильно).
- После смены схемы `MarkEntity` — **очистить данные приложения / переустановить** на dev-девайсе (Room бросит при несовпадении схемы на `version = 1`).

**External system updates:**
- Серверная сторона анти-чита: потребление координат взятия при будущей выгрузке марок (`uploadedLocal`/`uploadedCloud` сейчас только seed — upload-путь марок ещё не реализован). Координата складывается локально до появления выгрузки.
- Когда приложение пойдёт к реальным пользователям — вернуть Room-миграции (bump `version` + committed `schemas/<n>.json` + migration) вместо очистки данных.
