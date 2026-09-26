# Авто-старт/стоп записи трека по взятию КП

## Overview
- Новое взятие КП типа `start` запускает запись GPS-трека (`TrackRecordingService`).
- Любой другой КП (не `test`, не `finish`) тоже запускает запись, если она не идёт — страховка на случай, если Старт пропустили или запись остановили вручную.
- Взятие `finish` останавливает запись.
- КП типа `test` ни на что не влияет (используется до соревнований).
- После того как у команды есть отметка на `finish`-КП, авто-старт больше не срабатывает. Ручная кнопка «Начать запись» работает как раньше.
- Работает и для NFC-взятия, и для фото-отметки (отдельной).

## Context (from discovery)
- Тип КП: `CheckpointEntity.type` ∈ `start|finish|test|kp` (`docs/design/API.md:309`, `data/api/dto/LegendResponse.kt:28`). Уже используется в `ui/marks/ControlTime.kt` (`checkpointTypes` map).
- Старт/стоп сервиса: `TrackRecordingService.start(context, raceId, teamId)` / `.stop(context)` (`TrackRecordingService.kt:394-403`); стоп без потерь (`flushThen`).
- Ручной старт: `onStartTrack` → `trackPermissionLauncher` → `TrackRecordingService.start` (`MainActivity.kt:~1134-1205`).
- NFC-взятие: `markRepo.startKpTake(...)` в ветке нового take row (`MainActivity.kt:~1825-1870`), тип доступен через `localCheckpointsById[event.checkpointId]?.type`.
- Фото-отметка: `markRepo.createPhotoMark(cp = photoCp, ...)` в `onCommit` (`MainActivity.kt:~2660-2690`); ветка `attach` (`attachPhotos`) — дополнение к уже существующей NFC-отметке.
- Оверлей скана уже запрашивает геолокацию при первом открытии (`MainActivity.kt:1303`), поэтому разрешение к моменту Старта обычно есть.
- Смена команды уже останавливает запись (`MainActivity.kt:~1125`).
- Тесты `data/track/` живут в `app/src/test/java/ru/kolco24/kolco24/data/track/`.

## Development Approach
- **testing approach**: TDD (tests first)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- Compose UI / wiring в `MainActivity` не тестируется по конвенции проекта (см. CLAUDE.md); тестируется чистая логика.

## Testing Strategy
- **unit tests**: `TrackAutoControlTest` (JVM) — все комбинации входов.
- e2e/UI тестов в проекте нет; wiring проверяется вручную (см. Post-Completion).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
Подход A: явный вызов в двух местах взятия + чистая функция-решение.
- Чистая функция `trackAutoAction(cpType, recording, finishTaken)` решает `Start | Stop | None`.
- `MainActivity` вызывает хелпер `onTrackAutoTake(cpType)` сразу после создания **новой** отметки (NFC или фото). Повторный скан того же КП в окне, `addMember`, `attachPhotos` и перезапуск приложения не срабатывают.
- Отвергнуто: реакция на поток отметок из Room (трудно отличить новую отметку от старой после холодного старта → ложные старты); управление сервисом из `MarkRepository` (слой данных не должен управлять сервисом и разрешениями).

## Technical Details

```kotlin
// data/track/TrackAutoControl.kt
enum class TrackAutoAction { Start, Stop, None }

fun trackAutoAction(cpType: String?, recording: Boolean, finishTaken: Boolean): TrackAutoAction = when {
    cpType == null || cpType == "test" -> TrackAutoAction.None
    cpType == "finish" -> if (recording) TrackAutoAction.Stop else TrackAutoAction.None
    finishTaken -> TrackAutoAction.None
    recording -> TrackAutoAction.None
    else -> TrackAutoAction.Start
}
```

- `cpType == null` — легенда не загружена или КП не найден → ничего.
- Неизвестный новый тип (не `test`/`finish`) ведёт себя как `kp`.
- `recording` = `container.trackRecordingState.value as? TrackState.Recording` с `teamId == selectedTeamId`. `trackRecordingState` — `StateFlow`, читается с любого потока. `TrackRecordingService.start` идемпотентен (повторный вход сохраняет сегмент, `TrackRecordingService.kt:141-152`), поэтому два быстрых скана до перехода в `Recording` безвредны — отдельный guard не нужен.
- `finishTaken` = есть отметка команды на КП типа `finish` — любая (nfc/фото, полная или нет). Считается **до** текущего взятия (`safeMarks` ещё не обновился). Тип КП для `finishTaken` берём из того же источника, что и `cpType`:
  - NFC: из `localCheckpointsById` (свежий снимок DAO) — на холодном старте Compose-`checkpointTypes` может быть пуст, и КП после финиша ложно запустил бы запись.
  - Фото: из `checkpointTypes` (`safeCheckpoints`; `photoCp` оттуда же).
- **NFC-решение вычисляется до `applicationScope.async`** (на Main, `onScanTag` работает на Main через `rememberCoroutineScope`). Действие Start/Stop выполняется внутри `applicationScope.async` сразу после `startKpTake` — если оверлей закроется во время `await()` (отмена scope), запуск/остановка всё равно произойдут. `TrackRecordingService.start/stop` — обычные вызовы `Context`, потокобезопасны. Установка `pendingTrackAutoStart` — на Main, до `async` (решение уже известно).
- Фото: решение вычисляется в `onCommit` (Main) до `applicationScope.launch`.

Хелпер в `MainActivity` (рядом с `onStartTrack`) делится на две части:
- `trackAutoDecide(cpType, finishTaken): TrackAutoAction` — читает `recording`, вызывает `trackAutoAction`. Если `Start` и нет разрешения (fine или coarse, `ContextCompat.checkSelfPermission`) → `pendingTrackAutoStart = true` и возвращает `None`. Если `Stop` → `pendingTrackAutoStart = false`.
- `applyTrackAuto(action, raceId, teamId)` — `Start` → `TrackRecordingService.start(context, raceId, teamId)`; `Stop` → `TrackRecordingService.stop(context)`; `None` → ничего.

Отложенный запрос разрешения:
- `pendingTrackAutoStart` — `rememberSaveable`. Отдельного флага «спрошено» нет — используется существующий общий `hasRequestedLocation` (`MainActivity.kt:1146,1260`).
- `LaunchedEffect(showScan, photoCaptureMarkId, pendingTrackAutoStart, locationAutoAskInFlight)`: срабатывает, когда флаг стоит, оба оверлея закрыты (`!showScan && photoCaptureMarkId == null`) **и** нет запроса в полёте (`!locationAutoAskInFlight`). Тогда:
  - сброс флага;
  - повторная проверка разрешения: если уже есть (пользователь разрешил в авто-запросе оверлея скана) → сразу `TrackRecordingService.start(...)`, без диалога;
  - иначе, только если `!hasRequestedLocation` → существующий `onStartTrack`. Если уже спрашивали в этой сессии и отказали — молча пропускаем (второй отказ на Android 11+ становится постоянным; параллельный `launch` возвращает пустой результат, который лаунчер принял бы за реальный отказ и испортил бы `permissionLog`).
- Следствие: т.к. оверлей скана сам спрашивает геолокацию при первом открытии, отдельный диалог после скана почти никогда не нужен — основной путь «разрешили в диалоге оверлея → запись стартует после его закрытия».
- `pendingTrackAutoStart = false` добавить в сброс в `LaunchedEffect(selectedTeamId)` (`MainActivity.kt:~1117`), иначе `onStartTrack` запустит запись для новой команды.

Без новых UI-уведомлений: карточка трека и уведомление сервиса и так показывают запись.

## What Goes Where
- **Implementation Steps**: код, тесты, документация в репо.
- **Post-Completion**: ручная проверка на устройстве.

## Implementation Steps

### Task 1: Pure decision function `trackAutoAction`

**Files:**
- Create: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackAutoControlTest.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackAutoControl.kt`

- [ ] write `TrackAutoControlTest` first: `test` → None во всех комбинациях; `null` → None
- [ ] tests: `start`/`kp`/unknown type → Start когда не пишет и финиш не взят; None когда пишет; None когда финиш взят
- [ ] tests: `finish` → Stop когда пишет; None когда не пишет (с `finishTaken` true/false); повторный финиш после ручного рестарта (`recording = true`, `finishTaken = true`) → Stop
- [ ] implement `TrackAutoAction` + `trackAutoAction` (KDoc: правило и причина игнора `test`)
- [ ] run `./gradlew testDebugUnitTest` - must pass before task 2

### Task 2: Wire auto-start/stop into MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `pendingTrackAutoStart` (`rememberSaveable`) state; clear it in the `LaunchedEffect(selectedTeamId)` reset list
- [ ] add `trackAutoDecide(cpType, finishTaken)` (Main: reads `recording`, calls `trackAutoAction`, handles missing permission → pending, Stop → clears pending) and `applyTrackAuto(action, raceId, teamId)` near `onStartTrack`
- [ ] NFC call site, new-take-row branch only (not re-stamp / `addMember`): before `applicationScope.async`, decide with `cpType = localCheckpointsById[event.checkpointId]?.type` and `finishTaken` from `safeMarks` + `localCheckpointsById`; inside the async block, after `startKpTake`, call `applyTrackAuto`
- [ ] photo call site: in `onCommit`, standalone branch (`!attach && photoCp != null`, race/team non-null) decide on Main with `photoCp.type` + `checkpointTypes`, apply inside `applicationScope.launch` after `createPhotoMark`; `attach` branch does not call it
- [ ] add `LaunchedEffect(showScan, photoCaptureMarkId, pendingTrackAutoStart, locationAutoAskInFlight)`: when pending, overlays closed and no ask in flight → clear flag; permission granted → `TrackRecordingService.start`; else if `!hasRequestedLocation` → `onStartTrack`; else skip
- [ ] no new unit tests (Compose wiring is untested by convention); re-run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` - must pass before task 3

### Task 3: Verify acceptance criteria
- [ ] verify all requirements from Overview are implemented (start, any-KP fallback, finish stop, test ignored, no auto-start after finish, photo marks)
- [ ] verify edge cases: re-scan same KP in window, `attachPhotos`, legend not loaded (`null` type), permission missing
- [ ] run full test suite: `./gradlew testDebugUnitTest`
- [ ] run lint: `./gradlew lintDebug`

### Task 4: [Final] Update documentation
- [ ] `docs/design/DATA-NOTES.md`: add `TrackAutoControl` to the `data/track/` bullet (rule, `test` ignored, finish latch, tested by `TrackAutoControlTest`)
- [ ] `docs/design/UI-NOTES.md`: MainActivity wiring — `onTrackAutoTake` call sites (new NFC take row, standalone photo mark), deferred permission request after overlays close, once per session
- [ ] `CLAUDE.md`: add `TrackAutoControl` to the Pure models list; mention auto start/stop in the `data/track/` or `TrackRecordingService` module-map line
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual verification on device*

- Тест-КП: взятие не запускает запись.
- Старт: запись начинается; уведомление сервиса появилось.
- Остановить вручную посреди дистанции → взять обычный КП → запись снова пошла.
- Повторный скан того же КП в окне 20 с — без побочных эффектов.
- Финиш: запись остановилась, последние точки сохранены.
- После финиша взять обычный КП → запись не запускается; ручная кнопка работает.
- Фото-отметка Старта/Финиша ведёт себя так же.
- Повторное взятие Старта после истечения окна 20 с (новая строка) после ручной остановки — запись снова пошла (по правилу «любой КП»).
- Без разрешения на геолокацию, открытие оверлея тапом по чипу Старта: оверлей спрашивает разрешение → «разрешить» → после закрытия оверлея запись стартует без второго диалога. «Отказать» → повторно в этой сессии не спрашивает, запись не стартует.
- Взяли Старт без разрешения, затем сменили команду до закрытия оверлея → запись для новой команды не стартует.
