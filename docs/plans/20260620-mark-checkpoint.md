# «Отметить КП» — рабочий скан-экран + локальные отметки

## Overview

Сейчас `ui/scan/ScanScreen.kt` — статический мок (хардкод `MOCK_SCAN_CHIPS`, фейковый таймер). Реализуем настоящую механику «взятия КП».

**Суть взятия КП:** команда у метки одновременно сканирует **чип КП** + **браслеты всех участников** в скользящем окне 20 сек (каждый скан сбрасывает таймер на 20 с; пауза >20 с закрывает сессию). Всё работает **оффлайн** (на полигоне нет интернета). Скан чипа КП офлайн расшифровывает легенду (`cost`/`description`) через готовый `LegendRepository.unlock(...)`. Каждое взятие — событие в локальной таблице `marks`, спроектированной под будущую выгрузку на два сервера.

**Что даёт:**
- Чип КП → unlock/reveal легенды (видно сразу во вкладке «Легенда»).
- Накопление present-set участников; КП **засчитывается** (`taken`), только когда отмечены **все** участники ростера.
- Вкладки «Отметки» и «Легенда» переходят с моков на реальные данные.
- Анти-чит лог: записываем физический тег КП (`cpUid` + `cpCode`) для серверной постфактум-сверки.

**Вне объёма (по решению брейншторма):** выгрузка на сервер (только поля-заделы), фото-fallback (только поля таблицы), `cp-visitor-chain.md` (запись хэшей на метки), контрольное время «ДО КВ» (placeholder).

## Context (from discovery)

- **Проект:** single-activity Jetpack Compose, minSdk 24 **без desugaring** (никакого `java.time` — только `SimpleDateFormat`), без ViewModel/Navigation, состояние в `MainActivity` через `rememberSaveable`, manual DI через `AppContainer`, Room — единственный источник истины.
- **NFC уже built, но не подключён к скану:** `MainActivity.onTagDiscovered` (binder-поток) маршрутизирует хуки `onTagForWrite` (raw `Tag`) → `onTagScanned` (uid) → idle. `readChipCode(tag): ByteArray?`, `chipCodeHex(code)`, `normalizeNfcUid(tag.id)` готовы (`data/nfc/MifareUltralightWriter.kt`, `data/NfcUid.kt`).
- **Unlock готов, но без вызывающих (кроме теста):** `LegendRepository.unlock(raceId, code): UnlockOutcome` → `Revealed(point, ids)` / `IdentityOnly(point)` / `Unknown` / `Failed`. `Revealed` уже пишет `checkpointDao.reveal(id, cost, description)`.
- **Привязки чипов готовы:** `MemberChipBindingRepository.observeForTeam(teamId)`, сущность `MemberChipBindingEntity(teamId, numberInTeam, nfcUid, participantNumber)`. Это единственная связь `nfcUid → член команды`.
- **`CheckpointEntity`** уже имеет `cost: Int?` (revealed = не null) и `taken: Boolean = false` (всегда false сейчас). `CheckpointDao.replaceAllForRace` — preserve-on-resync (сохраняет revealed `cost`/`description`).
- **Паттерны для копирования:** `TeamMembersConverter` (List↔JSON `@TypeConverter`), `MemberChipBindingRepository` + `MemberChipBindingRepositoryTest` (local-only repo + fake DAO), `MigrationTest` (`MigrationTestHelper`), `CheckpointDaoTest` (instrumented `@Transaction`), `decideBind`/`TeamPickerLogic` (top-level pure + unit test).
- **Room сейчас v5**, `exportSchema=true` (`app/schemas/.../{1..5}.json`), `addMigrations(MIGRATION_1_2..MIGRATION_4_5)`, без `fallbackToDestructiveMigration`.

## Development Approach

- **Testing approach:** Regular (код, затем тесты) — но каждая задача завершается тестами до перехода к следующей.
- Маленькие сфокусированные изменения; полностью завершать задачу перед следующей.
- **Каждая задача с изменением кода ОБЯЗАНА включать новые/обновлённые тесты** (success + error/edge). Pure-логика (`reduce`, различение чипа, деривация счёта) — JVM unit; DAO/миграции — instrumented.
- **Все тесты зелёные до старта следующей задачи.**
- Обновлять этот файл при изменении объёма.
- Совместимость: миграция `MIGRATION_5_6` additive — существующие таблицы не трогаем; апгрейд с v5 без потери данных.

## Testing Strategy

- **Unit (JVM, `app/src/test/`):** `ScanSessionTest` (reduce), `ScanTagDecisionTest` (различение чипа), `MarkRepositoryTest` (fake DAO — UUID, инкремент, complete→taken, повтор=новая строка, деривация), `MarksConverterTest` (round-trip), маппинг событий в `MarksScreen` если вынесен в pure-функцию.
- **Instrumented (`app/src/androidTest/`, нужен эмулятор/девайс):** `MigrationTest` кейс 5→6; `CheckpointDaoTest` — preserve `taken` при ресинке; `MarkDaoTest` (опц.) — `@Transaction addMember`.
- Нет UI-e2e фреймворка в проекте — e2e не пишем; ручная проверка скан-флоу в Post-Completion.
- **Перед мержем:** `./gradlew lintDebug` + `./gradlew testDebugUnitTest` (обязательно); `./gradlew connectedDebugAndroidTest` (миграция/DAO — требует устройство).

## Progress Tracking

- `[x]` сразу по завершении пункта.
- Новые задачи — с префиксом ➕; блокеры — ⚠️.
- Держать план в синхроне с фактом.

## Solution Overview

**Поток данных одного взятия:**
1. Юзер тапает FAB «Отметить КП» → overlay `ScanScreen` (как сейчас, через `showScan`). `ScanScreen` через `DisposableEffect` арм-ит новый хук `MainActivity.onTagForMark: ((Tag) -> Unit)?` (приоритет: `onTagForWrite` > `onTagForMark` > `onTagScanned` > idle).
2. Каждый тап тега → `onScanTag(tag): ScanEvent` (в `MainActivity`/репо, off-main):
   - `readChipCode(tag)` → `code`. Если `code != null` → `legendRepo.unlock(raceId, code)`:
     - `Revealed(point, _)` / `IdentityOnly(point)` → **резолв `number`/`cost`**: `unlock` возвращает только `point` (= id КП), без `number`/`cost`. После reveal'а ищем `CheckpointEntity` по id (`point`) в `legendCheckpoints` (карта `checkpointsById`) → `number` и `cost`. `cost` гарантированно не-null после `Revealed` (reveal записал) и для `IdentityOnly` (открытая КП). Если всё же `null` (легенда не синкнута) → `ScanEvent.BadKp("легенда не загружена")`. Иначе `ScanEvent.Kp(point, number, cost, cpUid, cpCode)`.
       **NB:** при `Revealed(point, checkpointIds)` помечаем `taken` **только** `point`; остальные `checkpointIds` лишь revealed (расшифрованы), но не taken.
     - `Unknown` / `Failed` → `ScanEvent.BadKp(reason)`.
   - Если `code == null` → `uid = normalizeNfcUid(tag.id)`; `bindings[uid]` → `ScanEvent.Member(numberInTeam)` либо `ScanEvent.UnboundChip`.
3. `reduce(session, event, now)` (pure) обновляет `ScanSession`. На `Kp`: репо `startKpTake(...)` создаёт строку события (UUID, `point`, `cpUid`, `cpCode`, снимок `cost`/`number`/`expectedCount`), вливает буфер участников. На `Member`: репо `addMember(...)` (`@Transaction`) добавляет в `present`, пересчитывает `complete`; при `complete` → `checkpointDao.markTaken(point)`.
4. Таймер: `LaunchedEffect(session.lastScanAt)` тикает ~250 мс; `remaining = 20_000 - (now - lastScanAt)`; `<= 0` → finalize (закрыть сессию). «Готово» — finalize досрочно.
5. Вкладки читают Room: «Отметки» — `markRepo.observeMarks(teamId)` (плитка на событие); «Легенда» — `taken` из `CheckpointEntity` (наполняется).

**Ключевые правила:**
- Скан КП **сразу** делает reveal + создаёт строку (взятие переживёт смерть процесса).
- `taken` (= зачёт/баллы) только при `present ⊇ весь ростер`. Частичный сбор хранится (для серверного лога), но не засчитывается.
- Окно истекло, а `point == null` (КП не сканировали) → ничего не коммитим.
- Повторное взятие того же КП после finalize → **новая** строка (история для серверного порядка).

## Technical Details

**Таблица (Room v5 → v6, additive):**

```kotlin
@Entity(
    tableName = "marks",
    indices = [Index("teamId"), Index("point")],   // upload-флаги без индексов: запросов выгрузки ещё нет (добавим аддитивной миграцией, когда появятся)
)
data class MarkEntity(
    @PrimaryKey val id: String,          // UUID (java.util.UUID) — ключ слияния БД двух серверов
    val raceId: Int,
    val teamId: Int,
    val point: Int,                      // id КП (из bid→point)
    val checkpointNumber: Int,           // снимок номера для заголовка/плитки
    val cost: Int,                       // снимок стоимости
    val method: String,                  // "nfc" | "photo" (сейчас только "nfc")
    val cpUid: String,                   // UID физического тега КП — анти-чит лог
    val cpCode: String,                  // hex кода с тега — пара (code,uid) для серверной сверки
    val present: List<Int>,              // numberInTeam отмеченных (JSON-конвертер); пусто для photo
    val expectedCount: Int,              // размер ростера на момент взятия
    val complete: Boolean,               // nfc: present.size >= expectedCount; photo: фото есть
    val photoPath: String? = null,       // задел под фото-fallback
    val takenAt: Long,                   // эпоха мс первого скана чипа КП
    val updatedAt: Long,
    val uploadedLocal: Boolean = false,
    val uploadedCloud: Boolean = false,
)
```

**`MIGRATION_5_6`** (raw SQL, точно под сгенерённую `6.json`, camelCase): `CREATE TABLE IF NOT EXISTS marks (...)` + `CREATE INDEX index_marks_teamId` + `index_marks_point`. Boolean → `INTEGER NOT NULL`, `photoPath` → `TEXT` (nullable). Сверить SQL с `app/schemas/.../6.json` после KSP-генерации (KSP это не валидирует на сборке).

**Pure-типы (top-level, в `ScanScreen.kt` или `ui/scan/ScanSession.kt`):**

```kotlin
data class ScanSession(
    val point: Int?, val checkpointNumber: Int?, val cost: Int?,
    val cpUid: String?, val cpCode: String?,
    val present: Set<Int>, val bufferedBeforeKp: Set<Int>, val lastScanAt: Long,
)
sealed interface ScanEvent {
    data class Kp(val point: Int, val number: Int, val cost: Int, val cpUid: String, val cpCode: String) : ScanEvent
    data class Member(val numberInTeam: Int) : ScanEvent
    data object UnboundChip : ScanEvent
    data class BadKp(val reason: String) : ScanEvent
}
fun reduce(session: ScanSession?, event: ScanEvent, now: Long): ScanSession?

// Чистое различение; checkpointsById даёт number/cost для Kp (unlock возвращает только point).
fun classifyTag(
    code: ByteArray?, uid: String, unlock: UnlockOutcome?,
    bindings: Map<String, Int>, checkpointsById: Map<Int, CheckpointEntity>,
): ScanEvent
```

**Деривация для UI (pure, тестируемо):** по `List<MarkEntity>`:
- `takenPoints = marks.filter { it.complete }.map { it.point }.toSet()`
- «ВЗЯТО» = `takenPoints.size`; «СУММА» = `marks.filter{complete}.distinctBy{point}.sumOf{cost}`
- Плитки = все события по `takenAt` убыв. (повтор — отдельная плитка).

**`CheckpointDao` preserve `taken`:** добавить `@Query("UPDATE checkpoints SET taken = 1 WHERE id = :id") suspend fun markTaken(id: Int)` и `@Query("SELECT id FROM checkpoints WHERE raceId = :raceId AND taken = 1") suspend fun takenIdsForRace(raceId): List<Int>`. В `replaceAllForRace` снапшотить taken-id **до** wipe, и после reinsert переприменять **отдельным безусловным циклом** по снапшоту (`for (id in prevTaken) markTaken(id)`) — **не** внутри существующей ветки `if (incoming.locked)` reveal-цикла: taken-КП может прийти на ресинке как открытая (`locked=false`), и в этом случае она тоже должна сохранить `taken`.

## What Goes Where

- **Implementation Steps** (`[ ]`): код, тесты, схема, миграция, обновление CLAUDE.md.
- **Post-Completion** (без чекбоксов): ручной прогон скан-флоу на устройстве с реальными метками; будущая серверная выгрузка; фото-fallback; контрольное время.

## Implementation Steps

### Task 1: Таблица marks — сущность, конвертер, DAO

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/IntListConverter.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/db/IntListConverterTest.kt`

- [x] создать `MarkEntity` (поля по «Technical Details»; `@PrimaryKey val id: String`, индексы `teamId`/`point` только — upload-флаги без индексов).
- [x] создать `IntListConverter` (`List<Int>` ↔ JSON через `kotlinx.serialization`, по образцу `TeamMembersConverter`).
- [x] создать `MarkDao`: `observeForTeam(teamId): Flow<List<MarkEntity>>` (`ORDER BY takenAt DESC`), `getById(id): MarkEntity?`, `@Upsert suspend fun upsert(mark)`, `@Transaction suspend fun addMember(id, numberInTeam, now, expectedCount)` (читает строку, добавляет в `present` set-семантикой, пересчитывает `complete`/`updatedAt`, пишет обратно).
- [x] написать `IntListConverterTest` (round-trip пустой/непустой/дубли, success).
- [x] запустить `./gradlew testDebugUnitTest` — зелёно перед Task 2.

### Task 2: Room v6 — миграция + preserve taken + схема

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointDao.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/6.json` (генерится KSP)
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/CheckpointDaoTest.kt`
- Add `@TypeConverters(IntListConverter::class)` (рядом с `TeamMembersConverter`), `entities += MarkEntity`, `abstract fun markDao(): MarkDao`.

- [ ] поднять `version = 6`, добавить `MarkEntity` в `entities`, `markDao()`, конвертер.
- [ ] добавить `MIGRATION_5_6` (CREATE TABLE marks + 2 индекса: `index_marks_teamId`, `index_marks_point`) и зарегистрировать в `addMigrations(...)` (в конце цепочки после `MIGRATION_4_5`).
- [ ] `CheckpointDao`: добавить `markTaken(id)` и `takenIdsForRace(raceId)`; в `replaceAllForRace` снапшотить taken-id до wipe и переприменять **отдельным безусловным циклом** после reinsert (не внутри `if (incoming.locked)`).
- [ ] собрать `assembleDebug` (KSP сгенерит `6.json`), сверить SQL миграции с `6.json` (camelCase индексы), закоммитить `6.json`.
- [ ] дополнить `MigrationTest` кейсом 5→6 (вставка строки `marks`, проверка после миграции).
- [ ] дополнить `CheckpointDaoTest`: taken-строка переживает `replaceAllForRace` (для open- и locked-incoming).
- [ ] `./gradlew lintDebug testDebugUnitTest` + (на устройстве) `connectedDebugAndroidTest` — зелёно перед Task 3.

### Task 3: MarkRepository (local-only)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/AppContainer.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt`

- [ ] `MarkRepository(markDao, checkpointDao)`: `observeMarks(teamId)`; `startKpTake(raceId, teamId, point, number, cost, cpUid, cpCode, expectedCount, bufferedMembers, now): String` (генерит UUID, считает `complete`, upsert, при `complete` → `markTaken(point)`, возвращает id); `addMember(markId, point, numberInTeam, expectedCount, now)` (`@Transaction addMember`, при `complete` → `markTaken(point)`).
- [ ] вынести деривацию счёта/баллов в pure-функции (`takenPointCount`, `totalScore`) для теста и UI.
- [ ] `AppContainer`: lazy `markDao` + `markRepository` (как `memberChipBindingRepository`).
- [ ] `MarkRepositoryTest` (fake `MarkDao`/`CheckpointDao`): UUID уникален; буфер до КП вливается; инкремент present идемпотентен; `complete` при `present ⊇ ростер` ставит `taken`; повтор после закрытия = новая строка; деривация различных point.
- [ ] `./gradlew testDebugUnitTest` — зелёно перед Task 4.

### Task 4: Pure-логика скан-сессии и различение чипа

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanSessionTest.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanTagDecisionTest.kt`

- [ ] `ScanSession` + `ScanEvent` + `reduce(session, event, now)` (top-level pure): `Kp` заполняет point/cost/cpUid/cpCode и вливает `bufferedBeforeKp`; `Member` до КП → в буфер, после КП → в `present`; `UnboundChip`/`BadKp` не двигают окно; любой принятый скан обновляет `lastScanAt`.
- [ ] чистая функция различения `classifyTag(code, uid, unlock, bindings, checkpointsById): ScanEvent` (без Android-зависимостей; `Tag`-IO и вызов `unlock` остаются в `MainActivity`). Резолвит `number`/`cost` для `Kp` из `checkpointsById[point]`; `cost==null` → `BadKp`.
- [ ] `ScanSessionTest`: КП заполняет point; Member копит present; идемпотентность повторного участника; `complete`-условие; буфер участников до КП вливается; `BadKp`/`UnboundChip` не сбрасывают таймер.
- [ ] `ScanTagDecisionTest`: code→Kp(Revealed/IdentityOnly) с number/cost из `checkpointsById`; code→BadKp(Unknown/Failed/cost==null); uid в bindings→Member; uid не в bindings→UnboundChip.
- [ ] `./gradlew testDebugUnitTest` — зелёно перед Task 5.

### Task 5: NFC-маршрутизация — хук onTagForMark

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] добавить `@Volatile var onTagForMark: ((Tag) -> Unit)? = null`.
- [ ] в `onTagDiscovered` вставить приоритет: `onTagForWrite` → `onTagForMark` (post в main с raw `Tag`) → `onTagScanned` → idle `readChipCode`.
- [ ] обновить KDoc на хуках/классе (приоритеты), синхронизировать раздел про NFC в CLAUDE.md в Task 9.
- [ ] (тестов на binder-IO нет — поведение хука покрыто pure-логикой Task 4; отметить в чеклисте).
- [ ] `./gradlew lintDebug` — зелёно перед Task 6.

### Task 6: ScanScreen — живой stateful-хост сессии

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (только обновить вызов под новую сигнатуру — иначе `assembleDebug` не соберётся)

- [ ] удалить `MOCK_SCAN_CHIPS`; новая сигнатура: `ScanScreen(roster: List<TeamMemberItem>, bindings: Map<String, Int>, nfcAvailable: Boolean, onScanTag: suspend (Tag) -> ScanEvent, onClose: () -> Unit)`.
- [ ] **обновить вызов `ScanScreen(...)` в `MainActivity`** с временной заглушкой `onScanTag = { ScanEvent.BadKp("stub") }` и `roster=emptyList()`/`bindings=emptyMap()`, чтобы `assembleDebug` оставался зелёным на границе задачи; реальная сборка состояния и `onScanTag` — Task 7.
- [ ] `var session by remember { mutableStateOf<ScanSession?>(null) }`; `DisposableEffect` ставит/снимает `activity.onTagForMark` (callback вызывает `onScanTag` в `scope.launch`, затем `reduce`).
- [ ] таймер: `LaunchedEffect(session?.lastScanAt)` тик ~250 мс, `remaining`, `<=0` → finalize (`session = null` + закрытие сессии в репо).
- [ ] рендер на существующих компонентах: `CpWaitingCard` (point==null → «приложите чип КП», иначе номер+стоимость); `ChipGrid` из `roster` (зелёный=present, непривязан=«чип не привязан»); `HeroTimerCard` с реальным `remaining`/остатком; строка/тост на `UnboundChip`/`BadKp`.
- [ ] «Готово» enabled при `session != null` → finalize + `onClose`; правило: окно истекло и `point==null` → молча ничего не коммитим.
- [ ] (UI; pure-части протестированы в Task 4) — собрать `assembleDebug`, `./gradlew lintDebug` — зелёно перед Task 7.

### Task 7: MainActivity — сборка состояния и проброс onScanTag

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] собрать `marks by remember(selectedTeamId){ markRepo.observeMarks(it) ?: flowOf(emptyList()) }.collectAsState(...)`.
- [ ] инвертировать `bindings` в `Map<String, Int>` (`nfcUid → numberInTeam`) для `ScanScreen`; ростер = `teamForTab?.members`.
- [ ] реализовать `onScanTag(tag)`: `readChipCode` → `unlock` / `normalizeNfcUid` → `classifyTag` → при `Kp` вызвать `markRepo.startKpTake(...)` (с буфером из текущей сессии), при `Member` → `markRepo.addMember(...)`; записи на `applicationScope`/IO, возврат `ScanEvent` для `reduce`.
- [ ] пробросить в `ScanScreen` (overlay) `roster`/`bindings`/`nfcAvailable`/`onScanTag`; гард как у других overlay (`!showScan` и т.д. уже есть).
- [ ] передать `marks` в `MarksScreen` (Task 8).
- [ ] `./gradlew lintDebug` + ручная сборка — зелёно перед Task 8.

### Task 8: Вкладки Отметки и Легенда на реальных данных

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/marks/MarksMappingTest.kt`

- [ ] удалить `MOCK_MARKS`; `MarksScreen(marks: List<MarkEntity>, onScanClick, ...)`.
- [ ] pure-маппинг `MarkEntity → Mark`: `number = checkpointNumber.toString().padStart(2,'0')`, `time = SimpleDateFormat("HH:mm", Locale.US).format(Date(takenAt))` (без `java.time`), `kind = if (method=="photo") PHOTO else NFC`, `isRecent` = самое свежее; **плитка на каждое событие**.
- [ ] метрики: «ВЗЯТО» = различные `point` с `complete`; «СУММА» = `Σ cost` по различным взятым `point`; «ДО КВ» — placeholder (без источника). Пустое состояние (нет команды/нет отметок).
- [ ] Легенда: **без изменений сигнатуры** — `taken` наполняется из Room (проверить, что `MainActivity` уже отдаёт `legendCheckpoints`).
- [ ] `MarksMappingTest`: маппинг события (nfc/photo, формат времени, padStart), деривация метрик (различные point, повтор не двоит баллы).
- [ ] `./gradlew lintDebug testDebugUnitTest` — зелёно перед Task 9.

### Task 9: Verify acceptance criteria
- [ ] проверить все требования Overview: скан КП→reveal+taken-при-полном-ростере, окно 20 с скользит, повтор=новая строка, оффлайн, `cpUid`/`cpCode` пишутся, вкладки на реальных данных.
- [ ] edge-кейсы: непривязанный браслет; чужой код (BadKp); окно истекло без КП; смерть процесса после скана КП (строка есть, present частичный).
- [ ] полный прогон: `./gradlew lintDebug testDebugUnitTest`; на устройстве `connectedDebugAndroidTest` (миграция/DAO).

### Task 10: [Final] Документация
- [ ] обновить CLAUDE.md: запись про `marks`/`MarkEntity`/`MarkDao`/`IntListConverter`, `MarkRepository`, Room v6 + `MIGRATION_5_6`, хук `onTagForMark` и приоритеты, живой `ScanScreen` (сессия/таймер/reduce), `MarksScreen` на реальных данных, preserve `taken` в `replaceAllForRace`.
- [ ] переместить план в `docs/plans/completed/`.

## Post-Completion

*Только ручные/внешние действия — без чекбоксов.*

**Ручная проверка (на устройстве с NFC и реальными метками):**
- Привязать всем участникам тестовой команды чипы (фича bind-chip), затем «Отметить КП»: скан чипа КП → видно reveal в Легенде; скан всех браслетов в окне 20 с → КП зачтён (`taken`, плитка в Отметках, сумма баллов). Частичный сбор → событие есть, но не зачтено.
- Проверить скользящее окно (пауза <20 с продолжает, >20 с закрывает) и «Готово».
- Повторное взятие того же КП → вторая плитка со временем, баллы не растут.
- Апгрейд с v5: поставить старую сборку, создать данные, обновиться — данные на месте, отметки работают.

**Будущие итерации (вне объёма):**
- Выгрузка `marks` на два сервера (локальный wifi + облако) по `id`-UUID; матчинг `cpCode→cpUid` (анти-чит) и порядок взятий на сервере; флаги `uploadedLocal`/`uploadedCloud`.
- Фото-fallback (камера + `photoPath`, `method="photo"`).
- Контрольное время «ДО КВ» (реальный источник вместо placeholder).
- Код на браслетах участников (тогда — лог `uid+code` участника, ветка «code есть, bid в пуле member_tags»).
