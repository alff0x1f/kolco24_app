# TrustedClock — защита времени взятия КП от перевода часов на телефоне

## Overview
- Время взятия КП сейчас пишется из wall-clock (`System.currentTimeMillis()`) — переведут часы на телефоне, и зафиксированное время «уедет». Это критично, т.к. позже появится функционал времени старта/финиша.
- Цель: не только **предупреждать** о расхождении часов, но и **чинить** — писать в отметку доверенное время, не зависящее от wall-clock телефона.
- Подход A: «Доверенные часы» (`TrustedClock`), заякоренные на монотонный `SystemClock.elapsedRealtime()` + серверное время из HTTP-заголовка `Date`. **Ноль изменений на бэкенде.**
- Ключевой принцип: `trusted = serverEpochMs + (elapsedRealtime() − anchorElapsedMs)`. Перевод wall-clock на это не влияет (монотонный таймер не подкручивается из настроек). Якорь рушит **только reboot** (elapsedRealtime обнуляется).
- Дополнительно: 20-секундное окно скана (`SCAN_WINDOW_MS`) переводится с wall-clock на монотонный таймер — окно становится иммунным к переводу часов.

## Context (from discovery)
- **DB уже на версии 9** (v9 добавила `legend_meta`) — новая миграция будет **9 → 10** (в брейншторме ошибочно значилось 8→9). `version = 9`, `exportSchema = true`, `schemas/.../{1..9}.json` закоммичены, нет `fallbackToDestructiveMigration`.
- `MarkEntity` (`data/db/MarkEntity.kt`): поля `takenAt: Long`, `updatedAt: Long` — wall-ms; `present: List<Int>`, `complete: Boolean` и т.д.
- `MarkRepository.startKpTake(...)` и `addMember(...)` принимают `now: Long`.
- `AppSignatureInterceptor` (пример инъекции времени — `nowSeconds: () -> Long = { System.currentTimeMillis()/1000 }`), окно сервера ±300 c.
- `ApiClient.defaultOkHttpClient(signatureInterceptor)` собирает `OkHttpClient.Builder().addInterceptor(signatureInterceptor)` — расширим под второй интерсептор.
- `AppContainer`: lazy-DI, паттерн `tokenProvider`-лямбды для разрыва цикла конструирования; `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- `MainActivity`: `CapturedScan(code, uid, System.currentTimeMillis())` в idle-пути `onTagDiscovered` (binder-поток); `onScanTag` берёт `now`; `ScanTakeState` считает истечение окна по `now`; статусы (`adminSession`/`themeMode`) собираются через `collectAsState`.
- `ui/scan/ScanSession.kt`: `SCAN_WINDOW_MS = 20_000L`, `reduce(...)`, `lastScanAt` — всё `Long`; `ScanScreen` тикер каждые 250 мс по `System.currentTimeMillis()`.
- `ui/marks/MarksScreen.kt`: `marksToTiles` форматит время через `SimpleDateFormat("HH:mm")`.

### Related patterns found
- **Чистые модели** (Android-free, JVM-тестируемые): `ScanSession.kt`, `ThemeMode.kt`, `CheckpointColor.kt` — образец для `TrustedClock`.
- **Store-паттерн** (`AdminTokenStore.kt`, `ThemePreference.kt`): чистые `load`/`save`-лямбды + `fromSharedPreferences(context)` адаптер, синхронное чтение при конструировании — образец для `ClockAnchorStore`.
- **Аддитивная миграция** (`MIGRATION_7_8`, `MIGRATION_8_9`): `ALTER TABLE ... ADD COLUMN` / `CREATE TABLE`, verbatim сверка с `N.json`, гард в `MigrationTest`.

### Constraints (репо)
- minSdk 24, без `java.time`/desugaring → `SimpleDateFormat`/ручная арифметика.
- `exportSchema = true`, нет `fallbackToDestructiveMigration` — бамп версии без миграции = краш на апгрейде.
- Миграции — raw SQL, должны **побайтово** совпадать с генерируемым Room `10.json` (проверяется только в рантайме / `MigrationTest`, не при сборке).
- Чистые модели JVM-тестируются; тонкие Android-адаптеры (реальный `SharedPreferences`, реальный `NfcA`) не тестируются по конвенции.

## Development Approach
- **Testing approach**: Regular (код, затем тесты в той же задаче) — соответствует конвенции репо (чистая логика покрывается, адаптеры нет).
- Каждая задача завершается полностью (включая тесты) до перехода к следующей.
- **Каждая задача с изменением логики ОБЯЗАНА включать новые/обновлённые тесты** (success + edge), кроме задач, трогающих только тонкие Android-адаптеры/UI (по конвенции репо не тестируются — явно помечено в задаче).
- **Все тесты зелёные перед началом следующей задачи.**
- Команды: `./gradlew testDebugUnitTest` (JVM-юниты), `./gradlew connectedDebugAndroidTest` (миграции, нужен эмулятор/устройство), `./gradlew lintDebug`.
- Обновлять этот файл при изменении скоупа.

## Testing Strategy
- **Unit (JVM)**: `TrustedClockTest`, `ClockAnchorStoreTest`, обновления `MarksMappingTest`, `MarkRepositoryTest`. `ScanSessionTest` не должен сломаться (тип `Long` сохраняется, меняется лишь смысл).
- **Instrumented**: `MigrationTest.migrate9To10` (строка marks переживает, новые колонки с дефолтами; verbatim `10.json`).
- **`ServerTimeInterceptor` тестируется обязательно** — граница доверия (Date-парсинг, кэш/сеть-гейт, RTT-диапазон вкл. отрицательный, midpoint), исключение из «адаптеры не тестируем».
- **UI/проводка не тестируются**: `ScanScreen`/баннер/`MainActivity`-проводка — по конвенции репо.

## Progress Tracking
- `[x]` сразу по завершении пункта.
- ➕ — вновь обнаруженные задачи; ⚠️ — блокеры.
- Держать план в синхроне с фактической работой.

## Solution Overview
- **`TrustedClock`** (чистое ядро) хранит `ClockAnchor` и вычисляет доверенное время по монотонному таймеру; помечает якорь `verified` после первого сетевого ответа в текущем процессе.
- **`ServerTimeInterceptor`** на каждом ответе читает заголовок `Date` и переякоривает часы — бесплатный источник серверного времени без бэкенд-изменений.
- **`ClockAnchorStore`** персистит якорь с `bootCount`; **настоящий тёплый старт**: при совпадении `bootCount` после рестарта процесса доверенное время доступно сразу, без сети (та же boot-сессия ⇒ монотонная линия непрерывна).
- **Отметки** получают времена: `trustedTakenAt` (зачёт + порядок), `takenAt` (сырой wall), `elapsedRealtimeAt` + `bootCount` (монотонная метка + boot-сессия для форензики Δ).
- **Окно скана** переводится на монотонный таймер.
- **UI-баннер**: `Skewed` — глобально (под `TopAppBar`) **и** акцентом в скане; `NoSync` — мягкая плашка **только в скане** (глобально не рендерится).
- **Подпись запросов** (`X-App-Ts`) переходит на доверенное время (Task 4b): при уехавших часах синк не умирает (`403` → якорь по `Date` даже на `403` → пере-подпись доверенным `ts` → `200`), приложение самочинится; баннер всё равно нудит «поправьте часы».

## Technical Details

### TrustedClock (ядро)
```kotlin
data class ClockAnchor(
    val serverEpochMs: Long, val anchorElapsedMs: Long, val capturedWallMs: Long,
    val bootCount: Int?,        // Settings.Global.BOOT_COUNT в момент захвата — boot-session identity (null если недоступен)
)
data class TimeSample(val wallMs: Long, val elapsedMs: Long, val trustedMs: Long?, val bootCount: Int?)
sealed interface ClockStatus {
    data object NoSync : ClockStatus
    data object Ok : ClockStatus                       // skew payload опущен (YAGNI — баннер для Ok ничего не рендерит)
    data class Skewed(val skewMs: Long) : ClockStatus
}
private data class ClockState(val anchor: ClockAnchor?, val verified: Boolean)  // единый immutable снимок
```
- Зависимости впрыснуты: `elapsedProvider: () -> Long`, `wallProvider: () -> Long`, `bootCountProvider: () -> Int?` (адаптер над `Settings.Global.BOOT_COUNT`, API 24, без permission; `null` если прочитать не удалось), `persist: (ClockAnchor) -> Unit = {}` (по образцу `ThemePreference`, владеющего своим `save` — `AppContainer` передаёт `clockAnchorStore::write`; пустой дефолт держит модель чистой в тестах).
- **Инвариант времени:** `elapsedProvider()` — это **сырой** `SystemClock.elapsedRealtime()`, и `TimeSample.elapsedMs == elapsedProvider()` побайтово. Любой прямой вызов `SystemClock.elapsedRealtime()` в `ScanScreen` взаимозаменяем с `sample.elapsedMs` (один монотонный источник, иначе 20-секундное кольцо «прыгнет»).
- **`bootCount` читается один раз за процесс (P2):** `BOOT_COUNT` неизменен в течение жизни процесса, поэтому `AppContainer` снимает его **один раз** при создании и передаёт стабильным значением (`bootCountProvider = { cachedBootCount }`). Это исключает транзиентную ошибку `Settings.Global.getInt` посреди работы; если единственное чтение упало → `null` на весь процесс (тёплый старт выключен, fallback на `elapsed`-эвристику).
- **Потокобезопасность (P1):** состояние — `AtomicReference<ClockState>` для **дешёвых консистентных чтений** (`sample()`/`trusted()`/`status()` берут `.get()` ровно один раз). **Все мутации `statusFlow`/`ref` — под одним `synchronized(lock)`**, не CAS-цикл: это и `onServerTime`/инвалидация (читаем текущий state, применяем правило приёма, при accept атомарно **три шага в порядке** — `ref.set(new)` → `persist(anchor)` → `statusFlow.value = …`), и `recomputeStatus()` (тик читает state и пишет `statusFlow` под тем же lock). Сериализация писателей гарантирует, что ни поздний сетевой поток, ни тик не перезапишут store/flow более старым значением (закрывает и откат часов после рестарта, и затирание свежего `Ok` тиком). Читатели остаются lock-free.
- **`persist` — best-effort, не может ни бросить, ни рассинхронизировать state (P2):** шаг `persist(anchor)` обёрнут в `runCatching { persist(anchor) }` (исключение проглатывается, опц. лог), чтобы (1) `onServerTime` исполняется на OkHttp-потоке внутри `ServerTimeInterceptor` — необработанное исключение там превратит успешный HTTP-ответ в провал вызова; (2) при ошибке записи `ref` уже обновлён, и пропуск `statusFlow.value` оставил бы `ref`/`statusFlow` рассинхронизированными. In-memory якорь (`ref`) — источник истины процесса; `persist` нужен **только** тёплому старту следующего процесса, поэтому единственная корректная деградация при сбое записи — «тёплый старт выключен на следующем запуске», без влияния на текущую сессию или сетевой ответ. Порядок `ref.set → persist → statusFlow` сохраняется; `runCatching` гарантирует, что `statusFlow.value` всё равно выставляется.
- **Тёплый старт через boot identity (P0):** `verified = persisted != null && currentBoot != null && persisted.bootCount == currentBoot` — **оба** `bootCount` non-null **и** равны. Кейс `null == null` **не** даёт тёплый старт (иначе verified был бы ошибочно `true`). Иначе (reboot, либо любой `null`) ⇒ `verified = false`. **`statusFlow` засевается в конструкторе** вычисленным из warm-состояния статусом (`Ok`/`Skewed` при verified, иначе `NoSync`) — UI получает верный статус с первого кадра, без `NoSync`-до-первого-5-секундного-тика.
- **Монотонная регрессия = авторитетный reboot-детект (P0).** Якорь не может быть «в будущем» монотонных часов: если `current.anchorElapsedMs > elapsedProvider()` (свежее живое чтение) ⇒ часы откатились ⇒ **только** reboot (в той же сессии `anchorElapsedMs` снят в прошлом, т.е. `<= now`). Этот признак работает **без `BOOT_COUNT`** и применяется и на чтении, и на приёме.
- `trusted(): Long?` `= serverEpochMs + (elapsedNow − anchorElapsedMs)` — non-null только при `verified`. **Reboot-детект на чтении:** инвалидировать, если `current.anchorElapsedMs > elapsedNow` (монотонная регрессия), **или** `anchor.bootCount != null && currentBoot != null && различны`. `null` сам по себе reboot не доказывает (его ловит регрессия).
- **Правило приёма якоря (P0 + P1 out-of-order + P2 null):** `onServerTime(serverMs, anchorElapsed, wallNow, bootNow)`, прочитав свежий `elapsedNow = elapsedProvider()`, принимает, если: **(a)** нет текущего; **(b)** текущий монотонно невалиден — `current.anchorElapsedMs > elapsedNow` (reboot) — тогда **stale-якорь отбрасывается и входящий принимается безусловно** (закрывает «старый якорь блокирует все ответы навсегда»); **(c)** оба `bootCount` non-null и различны (reboot по boot id); **или (d)** (та же сессия) `anchorElapsed >= current.anchorElapsedMs` (новее по монотонике). Поздний out-of-order с меньшим `anchorElapsed` в **той же** сессии (current монотонно валиден) **отбрасывается** ветвью (d). `bootNow == null` не даёт «новая сессия всегда побеждает» — reboot ловится монотонной регрессией (b), а не отсутствием boot id.
- `skewMs = wallNow − trustedFromSnapshot` (только при verified); порог `SKEW_THRESHOLD_MS = 60_000L` → `Ok`/`Skewed`; при отсутствии trusted → `NoSync`.
- `statusFlow: StateFlow<ClockStatus>`. **`sample(): TimeSample` — единый снимок (P1):** берёт **один** `ClockState` (`.get()`), **один** `elapsedNow`, **один** `wallNow`, **один** `bootNow`, и вычисляет всё через чистый helper `computeTrusted(state, elapsedNow, bootNow): Long?` (без повторного `.get()`/повторного вызова провайдеров). `trustedMs` и `bootCount` берутся из этих же снятых значений.

### Room 9 → 10 (аддитивно)
```sql
ALTER TABLE marks ADD COLUMN trustedTakenAt INTEGER;
ALTER TABLE marks ADD COLUMN elapsedRealtimeAt INTEGER;
ALTER TABLE marks ADD COLUMN bootCount INTEGER;
```
- `trustedTakenAt: Long?` — зачётное время (null, если синка не было).
- `elapsedRealtimeAt: Long?` — монотонная метка; **nullable** (NULL на legacy-строках честно отличает «нет данных» от реального `0` сразу после boot, P6/P2).
- `bootCount: Int?` — boot-session id монотонной метки (P6); **обязателен для форензики** Δwall/Δelapsed: значения из разных boot-сессий лежат на разных монотонных шкалах, без `bootCount` сверка даст ложный «скачок». NULL на legacy.
- `takenAt` остаётся сырым wall.

### Порядок строк (P1)
`MarkDao.observeForTeam` сейчас `ORDER BY takenAt DESC` (`MarkDao.kt:11`) — после перевода часов плитки покажут доверенное время, но в неверном порядке. Меняем на `ORDER BY COALESCE(trustedTakenAt, takenAt) DESC` (сортировка по зачётному времени; untrusted-строки падают на wall — лучшего источника нет). `marksToTiles` остаётся newest-first + reverse.

### Привязка серверного времени (P1)
`Date` сервер штампует **до** передачи тела и с секундной точностью. Снимаем `elapsedBefore` до `proceed()` и `elapsedAfter` после; `rtt = elapsedAfter − elapsedBefore`; за `anchorElapsedMs` берём **overflow-safe midpoint** `elapsedBefore + rtt / 2` (не `(before+after)/2` — сумма может переполнить `Long`), что вычитает ~полRTT и сетевую задержку. Ответы с RTT вне `0..MAX_RTT_MS` (напр. 10 000; отрицательный RTT — аномалия) **не принимаем**. Хранение per-anchor uncertainty/RTT — **сознательно отложено** (YAGNI: для минутного зачёта midpoint+reject достаточно). Гард в коде: только сетевой ответ (`networkResponse != null`, не кэш) + RTT-reject. Доверие к источнику обеспечивается **конструктивно**: этот `OkHttpClient` — одно-хостовый, ходит только на `BuildConfig.API_BASE_URL` поверх HTTPS, поэтому явной проверки `request.url.host` не делаем (если когда-нибудь на клиент навесят второй хост — добавить host-гейт; зафиксировать это в Task 3 как условие).

### Окно скана на монотонном таймере
- Математика длительностей (`SCAN_WINDOW_MS`, тикер, истечение) → `elapsedRealtime`; `TimeSample.elapsedMs` служит «сейчас» для окна.
- `ScanSession`/`reduce`/`lastScanAt` остаются `Long` (меняется смысл, не сигнатуры) → `ScanSessionTest` не ломается.
- `CapturedScan.capturedAt: Long` → `sample: TimeSample`.

## What Goes Where
- **Implementation Steps** (`[ ]`): код, тесты, миграция, UI, доки — всё в этом репозитории.
- **Post-Completion** (без чекбоксов): ручная проверка на устройстве (перевод часов, reboot, офлайн), будущая серверная сверка Δwall/Δelapsed при аплоаде (когда появится upload).

## Implementation Steps

### Task 1: Ядро `TrustedClock` + типы

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/time/TrustedClock.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/time/TrustedClockTest.kt`

- [x] создать `TrustedClock.kt` с `ClockAnchor` (+`bootCount`), `TimeSample` (+`bootCount`), `ClockStatus` (sealed; `Ok` без payload), приватным `ClockState`, `SKEW_THRESHOLD_MS = 60_000L`
- [x] конструктор: `elapsedProvider: () -> Long`, `wallProvider: () -> Long`, `bootCountProvider: () -> Int?`, `persist: (ClockAnchor) -> Unit = {}`, `persisted: ClockAnchor? = null`
- [x] состояние — `AtomicReference<ClockState>` для lock-free чтений; **записи под `synchronized(lock)`**: в одном критическом участке прочитать текущий state → применить правило приёма → при accept по порядку `ref.set(new)` → `runCatching { persist(anchor) }` → `statusFlow.value = …` (сериализация писателей, P1). **`persist` — best-effort в `runCatching` (P2):** сбой записи не бросает (зов на OkHttp-потоке — иначе успешный HTTP-ответ упадёт) и не пропускает `statusFlow.value` (иначе `ref`/`statusFlow` разойдутся); деградация — тёплый старт выключен на следующем запуске
- [x] **тёплый старт (P0, null-safe):** `verified = persisted != null && currentBoot != null && persisted.bootCount == currentBoot` (где `currentBoot = bootCountProvider()`) — `null == null` тёплый старт **не** включает. **Начальный `statusFlow` (доп.):** в конструкторе сразу вычислить статус из (warm) состояния и засеять им `MutableStateFlow` (verified → `Ok`/`Skewed`, иначе `NoSync`) — UI получает корректный статус с первого кадра, **не** `NoSync`-до-первого-тика
- [x] чистый helper `computeTrusted(state, elapsedNow, bootNow): Long?` (без побочек/`.get()`); `trusted()`/`sample()` дёргают его на **уже снятых** значениях. **Reboot-инвалидация: `anchor.anchorElapsedMs > elapsedNow` (монотонная регрессия) ИЛИ оба boot non-null и различны**; `null` не доказывает reboot (его ловит регрессия)
- [x] `onServerTime(serverMs, anchorElapsed, wallNow, bootNow)` — прочитать свежий `elapsedNow`; приём: нет текущего / **current монотонно невалиден `current.anchorElapsedMs > elapsedNow` ⇒ stale отбросить, принять безусловно (P0)** / оба boot non-null и различны / иначе `anchorElapsed >= current.anchorElapsedMs`. Транзишн под `synchronized`
- [x] **`sample(): TimeSample` — единый снимок (P1):** один `state = ref.get()`, один `elapsedNow`, один `wallNow`, один `bootNow`; `trustedMs = computeTrusted(state, elapsedNow, bootNow)`, `elapsedMs = elapsedNow`, `wallMs = wallNow`, `bootCount = bootNow` — без повторных `.get()`/провайдеров
- [x] `recomputeStatus()` — публичный метод для локального тика; **выполняется под тем же `synchronized(lock)`**, что и `onServerTime` (читает текущий state и пишет `statusFlow` атомарно) — иначе гонка «тик прочитал старый state → сервер записал `Ok` → тик затёр `NoSync`». При отсутствии trusted → `NoSync` (без throw); `MutableStateFlow` сам дедупит равные значения → нет лишних рекомпозиций каждые 5 c; зафиксировать KDoc-инвариант `TimeSample.elapsedMs == elapsedProvider()` (сырой `elapsedRealtime`)
- [x] **`signingSeconds(): Long`** (источник `ts` для подписи, Task 4b) `= (trusted() ?: wallProvider()) / 1000` — доверенные секунды при verified, иначе честный fallback на wall; снимок `AtomicReference`, зовётся с OkHttp-потоков
- [x] написать `TrustedClockTest`: формула `trusted` (verified); **`signingSeconds()` — trusted-секунды при verified, wall при NoSync**; тёплый старт — persisted с совпадающим non-null `bootCount` → `trusted()` non-null без `onServerTime`; **`null == null` (persisted.bootCount=null + provider=null) → `verified=false`/`NoSync`**; persisted с другим `bootCount` (reboot) → `null`/`NoSync`; reboot-детект на чтении; **P0 — `bootCount` недоступен (null), persisted-якорь с большим `anchorElapsedMs`, после reboot `elapsedNow` мал: новый `onServerTime` принимается безусловно (монотонная регрессия), а не блокируется ordering'ом**; начальный `statusFlow` для verified persisted — `Ok`/`Skewed` сразу (не `NoSync`); out-of-order — поздний `onServerTime` с меньшим `anchorElapsed` в той же сессии (current монотонно валиден) отбрасывается, при reboot принимается; **`bootNow=null` не понижает хороший якорь**; **persist/statusFlow упорядочены — после серии конкурентных `onServerTime` последний persisted якорь и `statusFlow.value` соответствуют победителю (наибольший `anchorElapsed`)**, не только in-memory; `persist` вызывается на приёме; **`persist` бросает → `onServerTime` не пробрасывает исключение, `ref` обновлён и `statusFlow.value` всё равно выставлен (best-effort, P2)**; границы порога 60 c (59 999/60 000/60 001); знак skew
- [x] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 2

### Task 2: `ClockAnchorStore` (персистентность)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/time/ClockAnchorStore.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/time/ClockAnchorStoreTest.kt`

- [x] **атомарная запись (P1):** хранить якорь **одной сериализованной строкой под одним ключом** (`anchor`), не четырьмя — иначе остановка процесса между `apply()` оставит смесь старых/новых полей, которая распарсится, но будет неконсистентной. Формат — делимитированная строка `"$serverEpochMs|$anchorElapsedMs|$capturedWallMs|${bootCount ?: ""}"` (пустой хвост = `bootCount == null`)
- [x] чистые `load: (String) -> String?` + `save: (String, String?) -> Unit` (single-key seam сохраняется), методы `read(): ClockAnchor?` / `write(anchor)` / `clear()`; `write` — один `save("anchor", serialized)` → один `Editor.apply()`
- [x] `companion object fun fromSharedPreferences(context)` — файл `"kolco24.clock"`, ключ `anchor`, `apply()`, синхронное чтение
- [x] `read()` парсит строку → `null` если ключ отсутствует или формат битый (число полей/парс `Long` не сошлись); пустой 4-й сегмент → `bootCount = null`
- [x] написать `ClockAnchorStoreTest`: round-trip (вкл. `bootCount` non-null и `null`), `clear()` убирает ключ `anchor`, `read()` с пред-засеянного стора, отсутствие/битьё строки → null
- [x] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 3

### Task 3: `ServerTimeInterceptor` + проводка в OkHttp

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/ServerTimeInterceptor.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt` (`defaultOkHttpClient`)
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/ServerTimeInterceptorTest.kt`

- [x] создать `ServerTimeInterceptor(onServerTime: (serverMs, anchorElapsed, wallNow, bootNow) -> Unit, elapsed: () -> Long, wall: () -> Long, bootCount: () -> Int?, maxRttMs: Long = 10_000)`: снять `elapsedBefore = elapsed()`, `proceed`, снять `elapsedAfter = elapsed()`; `rtt = elapsedAfter − elapsedBefore`; переякоривать **только на сетевом ответе** (`response.networkResponse != null`) и **только если `rtt in 0..maxRttMs`** (отрицательный RTT — аномалия часов/ошибка — отбрасываем); `anchorElapsed = elapsedBefore + rtt / 2` (**overflow-safe midpoint**, не `(before+after)/2`); `response.headers.getDate("Date")?.time?.let { onServerTime(it, anchorElapsed, wall(), bootCount()) }`; `Date == null` / кэш / RTT вне диапазона → не трогать якорь
- [x] подтвердить, что у `OkHttpClient` нет response-`Cache` (каждый `Date` — живой заголовок); `304 Not Modified` несёт живой `Date` и корректно переякоривает — желаемое (бесплатный ресинк по ETag) — `defaultOkHttpClient` не вызывает `.cache(...)`, зафиксировано KDoc-комментарием
- [x] **источник доверенный конструктивно**: клиент одно-хостовый (`BuildConfig.API_BASE_URL`/HTTPS) → host-гейт не реализуем; если на этот клиент добавят второй хост — ввести `request.url.host == <api host>` перед переякориванием — условие зафиксировано в KDoc `ServerTimeInterceptor`
- [x] расширить `defaultOkHttpClient(signatureInterceptor, serverTimeInterceptor)` — добавить `.addInterceptor(serverTimeInterceptor)` (после подписи, внутренний интерсептор); параметр опционален (`= null`) → существующий вызов в `AppContainer` компилируется без изменений, реальный инстанс прокидывается в Task 4
- [x] **`ServerTimeInterceptorTest` обязателен** (это граница доверия, тут сосредоточены критичные правила — исключение из «адаптеры не тестируем»): сетевой `Date` → `onServerTime` с распарсенным epoch и midpoint-`anchorElapsed`; ответ из кэша (`networkResponse == null`) → не дёргает; `Date` отсутствует/битый → не дёргает; RTT 10_000 → принимает, 10_001 → отбрасывает; **отрицательный RTT → отбрасывает**; out-of-order (два ответа, второй с меньшим `elapsedBefore`) → `onServerTime` зовётся с корректными значениями каждого (упорядочивание — забота `TrustedClock`, тут проверяем лишь корректную передачу midpoint/RTT-гейта)
- [x] запустить `./gradlew testDebugUnitTest` + `./gradlew lintDebug` — зелёно перед Task 4

### Task 4: Проводка `TrustedClock` в `AppContainer` + локальный тик

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] **прочитать `BOOT_COUNT` один раз за процесс (P2):** `val cachedBootCount: Int? = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()` (API 24, без permission; константа на жизнь процесса — нет транзиентных ошибок mid-run); `bootCountProvider = { cachedBootCount }`
- [ ] lazy `clockAnchorStore = ClockAnchorStore.fromSharedPreferences(context)` и lazy `trustedClock = TrustedClock(elapsedProvider = { SystemClock.elapsedRealtime() }, wallProvider = { System.currentTimeMillis() }, bootCountProvider = { cachedBootCount }, persist = clockAnchorStore::write, persisted = clockAnchorStore.read())`
- [ ] **персистентность решена в Task 1**: `persist` инъектируется в `TrustedClock`, контейнер передаёт `clockAnchorStore::write` — никакой записи в лямбде интерсептора
- [ ] в `apiClient`-блоке создать `ServerTimeInterceptor` с `onServerTime = { s, e, w, b -> trustedClock.onServerTime(s, e, w, b) }`-лямбдой (разрыв цикла как `tokenProvider`), прокинуть в `defaultOkHttpClient`
- [ ] запустить `./gradlew testDebugUnitTest` + `./gradlew lintDebug` — зелёно перед Task 4b
- [ ] (тестов в этой задаче нет: только DI-проводка тонких адаптеров — по конвенции репо не тестируется; пометка обязательна)

### Task 4b: Подпись запросов доверенным временем + self-heal на clock-403

Цель: при уехавших часах (>±300 c) подпись перестаёт проходить (`403`) и синк умирает. Подписываем `ts` доверенным временем (когда verified) → синк выживает; `ServerTimeInterceptor` якорится даже на `403` (несёт `Date`), поэтому приложение **самочинится**. Время взятия КП уже защищено `trustedTakenAt` независимо; баннер о расхождении остаётся (мягкое давление «поправьте часы»).

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` (`nowSeconds`-провайдер интерсептора)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/AppSignatureInterceptor.kt` (опц. ретрай-один-раз)
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/SigningTest.kt`

- [ ] в `AppContainer`: передать `nowSeconds = { trustedClock.signingSeconds() }` в `AppSignatureInterceptor` вместо дефолта-wall (лямбда срабатывает в момент запроса — цикла нет, `TrustedClock` не трогает `apiClient`)
- [ ] **порядок интерсепторов:** `AppSignatureInterceptor` добавлен **первым** (внешний), `ServerTimeInterceptor` — вторым (внутренний), чтобы к возврату из `proceed()` в подписи якорь уже стоял (anchor-on-`403`); зафиксировать комментарием в `defaultOkHttpClient`
- [ ] **опц. ретрай-один-раз — ТОЛЬКО для безопасных методов `GET`/`HEAD` (P1)**: после `proceed()` если `request.method in ("GET","HEAD") && response.code == 403 && !retried && nowSeconds() != usedTs` → `response.close()`, пере-подписать (свежий `ts`), `proceed` ещё раз, вернуть. **POST не ретраим автоматически** (`login`/`logout`/`bindTag`): replayability ≠ идемпотентность, а сервер отдаёт обобщённый `403` без кода clock-skew, так что auth-фейл от clock-skew не отличить — повтор POST небезопасен; POST самочинится со следующего user-инициированного действия (подпись уже доверенная)
- [ ] **корректность гарда:** `nowSeconds() != usedTs` — необходимое, но **не** достаточное условие (первый ответ ставит якорь, и `ts` может сместиться на ±1 c даже при верных часах) → спорадический ретрай GET на «честном» `403` (плохой ключ) безвреден (вернёт тот же `403`), но именно ограничение `GET`/`HEAD` даёт безопасность, а не этот гард
- [ ] `SigningTest`: без регресса (фикс. `nowSeconds`); ретрай — `GET 403`+изменившийся `ts` → один ретрай и второй `proceed`; `GET 403`+тот же `ts` → без ретрая; **`POST 403` → без ретрая (даже при изменившемся `ts`)**; `GET 200` с первого раза → без ретрая
- [ ] запустить `./gradlew testDebugUnitTest` + `./gradlew lintDebug` — зелёно перед Task 5

### Task 5: Room 9 → 10 — колонки времени в `marks`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt` (`ORDER BY`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt` (`version = 10`, `MIGRATION_9_10`, `addMigrations`)
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/10.json` (генерируется сборкой)
- Modify: `app/src/androidTest/.../MigrationTest.kt`

- [ ] в `MarkEntity` добавить (последними полями) `val trustedTakenAt: Long? = null`, `val elapsedRealtimeAt: Long? = null`, `val bootCount: Int? = null`; KDoc: NULL = legacy/неизвестно (отличается от реального `0` сразу после boot); `bootCount` нужен, чтобы Δelapsed-сверка не смешивала разные boot-сессии
- [ ] поменять `MarkDao.observeForTeam` `ORDER BY takenAt DESC` → `ORDER BY COALESCE(trustedTakenAt, takenAt) DESC` (P1 — порядок по зачётному времени)
- [ ] `version = 10`; добавить `MIGRATION_9_10` с тремя nullable `ALTER TABLE marks ADD COLUMN ...` (SQL из Technical Details, без `NOT NULL DEFAULT`); зарегистрировать в `addMigrations(...)`
- [ ] собрать `./gradlew assembleDebug` для генерации `10.json`; **побайтово** сверить SQL миграции с дельтой `9.json`→`10.json`
- [ ] добавить `MigrationTest.migrate9To10`: пред-вставка строки marks на v9, миграция, проверка — строка цела, `trustedTakenAt`/`elapsedRealtimeAt`/`bootCount` IS NULL
- [ ] запустить `./gradlew connectedDebugAndroidTest` (эмулятор/устройство) — миграции зелёные перед Task 6

### Task 6: `MarkRepository` — приём `TimeSample`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt` (если `addMember`-транзакция пишет `updatedAt`)
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt`

- [ ] `startKpTake(...)`: заменить `now: Long` на `sample: TimeSample`; писать `takenAt = sample.wallMs`, `updatedAt = sample.wallMs`, `trustedTakenAt = sample.trustedMs`, `elapsedRealtimeAt = sample.elapsedMs`, `bootCount = sample.bootCount`
- [ ] `addMember(...)`: заменить `now: Long` на `sample: TimeSample`; `updatedAt = sample.wallMs` (через `MarkDao.addMember`; обновить сигнатуру DAO-транзакции при необходимости)
- [ ] убедиться, что пути семплирования сохраняют поведение `complete`/буфера (логика не меняется)
- [ ] обновить `MarkRepositoryTest`: фейковый `MarkDao` — проверить запись всех времён из `TimeSample` (включая `trustedMs == null` → колонка null, `bootCount`); сохранение прежних свойств (UUID-уникальность, дренаж буфера, идемпотентность, repeat = новая строка)
- [ ] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 7

### Task 7: `ScanSession`/`CapturedScan` — окно на монотонном таймере

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (`CapturedScan`, `onTagDiscovered`, `onTagForMark`, `ScanInput`, `onScanTag`, `ScanTakeState`, вызовы `startKpTake`/`addMember`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt` (комментарии о смысле `Long`; граница окна)
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanSessionTest.kt`

- [ ] ⚠️ **захват `TimeSample` в момент касания (P1)**: текущий код намеренно снимает `now` **до** `Mutex`/NFC-I/O/Room, чтобы медленная предыдущая операция не «протухлила» 20-секундное окно. Снимать `val sample = trustedClock.sample()` **там же**, **до** `scope.launch`: для **Live**-пути — первой строкой лямбды `onTagForMark` (она идёт через `mainHandler.post` → **main-поток**, как сейчас `now` в `ScanScreen.kt:140`); для **opening/Captured**-пути — в idle-ветке `onTagDiscovered` (**binder-поток**). `sample()` потокобезопасен (снимок `AtomicReference`) и валиден на обоих потоках. **Не** вызывать `sample()` внутри `onScanTag` (это уже во время обработки)
- [ ] `ScanInput.Live(tag)` → нести `sample` вместе с тегом (или менять сигнатуру `onScanTag` на приём `sample`); `CapturedScan.capturedAt: Long` → `sample: TimeSample`
- [ ] ⚠️ **исправить `0L`-сентинел**: `ScanTakeState.lastScanAt` сейчас `Long`=`0L` с гардом `lastScanAt != 0L` (`MainActivity.kt:791`). С монотонным таймером `0L` легально сразу после reboot (целевой сценарий) → заменить на `Long?` (или `hasScan: Boolean`)
- [ ] окно/`ScanTakeState`-истечение по `sample.elapsedMs`, граница **`>=`** (как в текущем коде `MainActivity.kt:791`): `lastScanAt != null && sample.elapsedMs − lastScanAt >= SCAN_WINDOW_MS`; в `startKpTake`/`addMember` передавать `sample`
- [ ] `reduce(...)` вызывать с `now = sample.elapsedMs` (монотонное «сейчас»); подтвердить инвариант Task 1 (`sample.elapsedMs` — сырой `elapsedRealtime`, один источник с тикером `ScanScreen`)
- [ ] `ScanSessionTest`: подтвердить незыблемость (тип `Long`); добавить **граничные тесты окна 19_999 / 20_000 / 20_001** (фиксируем `>=`); комментарий о монотонности `now`
- [ ] подтвердить `ScanSessionTest` зелёным (тип `Long` сохранён); добавить тест-комментарий, что `now` теперь монотонный
- [ ] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 8

### Task 8: `ScanScreen` тикер + плитки `marksToTiles`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt` (тикер; `pendingScan` дренаж как `ScanInput.Captured` с `sample`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt` (`marksToTiles`)
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/marks/MarksMappingTest.kt`

- [ ] `ScanScreen` тикер (250 мс): `remaining = SCAN_WINDOW_MS − (SystemClock.elapsedRealtime() − lastScanAt)` — `elapsedRealtime()` вместо `currentTimeMillis()`; дренаж `pendingScan` использует `sample.elapsedMs` для окна и `sample` для персиста
- [ ] `marksToTiles`: форматить время из `trustedTakenAt ?: takenAt` (`SimpleDateFormat("HH:mm", Locale.US)`)
- [ ] обновить `MarksMappingTest`: плитка берёт `trustedTakenAt` когда не-null (кейс, где `trustedTakenAt` и `takenAt` различаются на минуты — рендерится доверенное); fallback на `takenAt` когда null
- [ ] (UI-проводка `ScanScreen` не тестируется по конвенции — пометка)
- [ ] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 9

### Task 9: UI-баннер расхождения часов (глобально + в скане)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/common/ClockWarningBanner.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (`collectAsState` статуса; баннер под `TopAppBar`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt` (акцент-плашка)

- [ ] **единое правило видимости (P2):** глобальный баннер — **только `Skewed`**; `NoSync` глобально **ничего не рендерит** (мягкая плашка про неподтверждённое время живёт только в скане, где пишется время). `Ok` — ничего
- [ ] `ClockWarningBanner(status: ClockStatus, modifier)`: `Skewed` → красно/янтарная плашка «Часы телефона расходятся с сервером на N мин — проверьте дату и время» (N = `skewMs` → минуты); `NoSync`/`Ok` → ничего (баннер глобальный)
- [ ] в `MainActivity`: `val clockStatus by container.trustedClock.statusFlow.collectAsState()`; рендер баннера под `TopAppBar` на всех вкладках (виден только при `Skewed`); локальный тик `LaunchedEffect { while(true){ delay(5000); container.trustedClock.recomputeStatus() } }`
- [ ] в `ScanScreen`: яркий акцент при `Skewed`; **мягкая плашка «Время не подтверждено, подключитесь к сети» при `NoSync`** (отметку всё равно пишем) — это **единственное** место, где `NoSync` показывается
- [ ] хелпер форматирования `skewMs → «N мин»` — чистая функция: **по модулю и `round`** (`Math.round(abs(skewMs) / 60_000.0)`). `round` (не `ceil`) согласован с тест-кейсами: 60_001 → «1 мин», 90_000 → «2 мин» (1.5→2), 119_000 → «2 мин». Баннер показывается только при `Skewed` (skew всегда `> 60_000`), так что `round` даёт ≥ «1 мин». Текст без направления → **abs** (отстающие часы дают отрицательный skew, нельзя «−2 мин»); `abs` считать в `Double` (`abs(skewMs.toDouble())`), **не** `abs(Long.MIN_VALUE)` напрямую. Мини-тест: оба знака (+90 000 и −90 000 → «2 мин»), 60 001 → «1 мин», 119 000 → «2 мин», `Long.MIN_VALUE` не падает/не отрицательно
- [ ] (Compose-UI баннера/экранов не тестируется по конвенции — пометка; тестируется только чистый форматтер)
- [ ] запустить `./gradlew testDebugUnitTest` + `./gradlew lintDebug` — зелёно перед Task 10

### Task 10: Verify acceptance criteria
- [ ] доверенное время пишется в `trustedTakenAt`, wall — в `takenAt`, монотонное — в `elapsedRealtimeAt` + `bootCount`; плитки **и порядок** — по `COALESCE(trustedTakenAt, takenAt)`
- [ ] перевод часов в той же boot-сессии не ломает якорь (trusted остаётся верным офлайн); рестарт процесса в той же сессии → тёплый старт (trusted сразу, по `bootCount`); reboot → `NoSync` до ресинка
- [ ] окно скана 20 c считается по монотонному таймеру с границей `>=` (не схлопывается/не зависает при переводе часов; `TimeSample` снят в момент касания до `scope.launch` — Live на main-потоке `onTagForMark`, opening на binder-потоке `onTagDiscovered`, не во время обработки)
- [ ] `onServerTime` потокобезопасен, поздний out-of-order ответ не перезаписывает свежий якорь; midpoint+RTT-reject применён к `Date`
- [ ] баннер `Skewed` появляется в течение ~5 c после перевода часов без событий; `NoSync` — мягкая плашка в скане
- [ ] `Date == null`/смена таймзоны не дают ложных срабатываний
- [ ] подпись `ts` идёт доверенным временем при verified (Task 4b); при часах >±300 c синк самочинится (`403`→якорь→пере-подпись→`200`), не остаётся пустым
- [ ] полный прогон: `./gradlew testDebugUnitTest`, `./gradlew connectedDebugAndroidTest`, `./gradlew lintDebug`

### Task 11: [Final] Документация
- [ ] обновить `CLAUDE.md`: новый раздел про `TrustedClock`/`ServerTimeInterceptor`/`ClockAnchorStore`, три времени в `MarkEntity`, монотонное окно скана, баннер, **подпись `ts` доверенным временем + self-heal на clock-403**; зафиксировать Room v10 + `MIGRATION_9_10`
- [ ] обновить `docs/design/API.md` при необходимости (использование заголовка `Date` как источника серверного времени)
- [ ] переместить этот план в `docs/plans/completed/`

## Post-Completion
*Без чекбоксов — ручные/внешние действия.*

**Ручная проверка на устройстве:**
- перевести системные часы вперёд/назад во время открытого скана → окно не ломается, баннер появляется ~5 c, `trustedTakenAt` остаётся корректным.
- reboot устройства офлайн посреди «гонки» → статус `NoSync`, отметка пишет wall+elapsed, после ресинка статус восстанавливается.
- смена таймзоны (без правки UTC) → баннер НЕ появляется.
- холодная установка офлайн → `NoSync`, `trustedTakenAt == null`, мягкая плашка только в скане.
- перевести часы на >10 мин **онлайн** → первый запрос `403`, затем синк самочинится (легенда/команды грузятся), баннер `Skewed` висит до правки часов.

**Будущие серверные/upload-изменения (когда появится аплоад отметок):**
- сервер при приёме сверяет Δwall и Δelapsed между взятиями → детект скачка часов задним числом; **сверка Δelapsed валидна только в пределах одной boot-сессии** — сравнивать `elapsedRealtimeAt` лишь у строк с равным `bootCount` (иначе разные монотонные шкалы дадут ложный скачок); строки с NULL `bootCount`/`elapsedRealtimeAt` (legacy) из сверки исключать.
- функционал старт/финиш-времени опирается на `trustedTakenAt`.
