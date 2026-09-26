# Per-tag check method (offline / cloud / local)

## Overview
- Every checkpoint tag has a server field `check_method`. Android stores it (`TagEntity.checkMethod`) and
  only shows it on the admin check-chip screen; scoring ignores it.
- New rule (port of the iOS feature, spec: `~/src/kolco24_ios/kolco24/docs/plans/completed/20260924-check-method.md`):
  - `offline`: no change. A complete take counts.
  - `cloud`: the take counts only if the **cloud** server accepted the mark **while the scan overlay was open**.
  - `local`: the same, through the **local (LAN)** server.
- A later background upload still sends the mark, but does **not** confirm it. Unconfirmed takes stay visible
  (dimmed tile + notice), but are not counted.
- Server values are already renamed to `offline` / `cloud` / `local` (kolco24 `11c7a19`). Unknown value → `offline`.
- Closes item 1 of `docs/IOS-PARITY.md`.

## Context (from discovery)
- Tag → take path: `data/LegendRepository.kt` (`unlock` loads `tagEntity`, `UnlockOutcome` at the file end)
  → `ui/scan/ScanSession.kt` (`classifyTag`, `ScanEvent.Kp`, `reduce`, `isComplete`)
  → `MainActivity.kt` `onScanTag` (~:1776–1920, `ScanTakeState`, `startKpTake`/`addMember` awaited on
  `applicationScope.async` under `ScanScreen.scanMutex`) → `data/MarkRepository.kt` (`startKpTake`).
- "Taken" = `complete` today: `data/MarkRepository.kt:558–593` (`takenPointCount` ×2, `takenPoints`, `totalScore`);
  `ui/marks/MarksScreen.kt` (`marksToTiles` :160, `photoReviewSummary` :223, `hiddenTakenTokens` :261);
  `MainActivity.kt:913` (`takenIds = takenPoints(safeMarks)`) feeds the legend.
- Upload: `MarkRepository.flushScope`/`uploadLoop` (`uploadMutex.tryLock`), `markLocalGpsAware`/`markCloudGpsAware`,
  `backfillTrustedMs`, `toDto`; `UploadTarget`/`UploadResultKind` in `data/track/TrackModels.kt:217`.
- Schema: `data/db/AppDatabase.kt` (v7, `MIGRATION_6_7` :157, `.addMigrations` :170), `data/db/MarkEntity.kt`,
  `data/db/MarkDao.kt` (`addMember` rebuilds via `mark.copy(...)`).
- Scan UI: `ui/scan/ScanScreen.kt` (`process`, window `LaunchedEffect(session?.lastScanAt)`,
  completion `LaunchedEffect(allScanned)`, `ScanTopBar`, `COMPLETE_FANFARE_DELAY_MS`/`SUCCESS_HOLD_MS`).
- Marks UI: `ui/marks/MarksScreen.kt` (`Mark` tile model, `PhotoReviewNotice` :714, `HiddenKpNotice` :771,
  `PhotoLightbox`/`PhotoKpChip`).
- Tests: `app/src/test/.../data/MarkRepositoryTest.kt`, `MarkRepositoryUploadTest.kt`, `LegendRepositoryTest.kt`,
  `ui/scan/ScanSessionTest.kt`, `ScanTagDecisionTest.kt`, `ScanFeedbackTest.kt`, `ui/marks/MarksMappingTest.kt`;
  instrumented `MigrationTest.kt`, `MarkDaoTest.kt`.
- `material-icons-extended` is already a dependency (`Icons.Outlined.CloudOff`).

## Development Approach
- **testing approach**: Regular (code first, then tests in the same task)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - write unit tests for new and modified functions
  - cover both success and error scenarios
  - update existing tests if behavior changes
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility: old rows and unknown values behave as `offline`
- work on branch `feat/check-method`, never commit to `main`

## Testing Strategy
- **unit tests**: JVM (`./gradlew testDebugUnitTest`) for pure models, repos with fake DAOs/uploaders,
  and the confirm loop (`runTest` virtual time).
- **instrumented tests**: `./gradlew connectedDebugAndroidTest` for `MIGRATION_7_8` and the new `MarkDao` query.
- **e2e**: none in the project. Compose UI is untested by convention; covered by pure-function tests and
  manual checks (Post-Completion).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
- **Snapshot on the mark.** At scan time the tag's method is copied into the new mark (`marks.checkMethod`).
  A later legend change does not affect old takes.
- **Separate confirmation field.** `marks.confirmedAt` is set only by `MarkRepository.confirm`, called from the
  open scan overlay. The background drain never sets it; `addMember` keeps it (row rebuilt via `copy`).
- **One pure rule** `isCounted` replaces `complete` in all scoring/"taken" derivations.
- **Confirm in the overlay (approach A).** A pure, JVM-tested `runConfirm` loop in `ui/scan/ConfirmLoop.kt`;
  `ScanScreen` holds `confirmState` and runs the loop when the roster is complete for a cloud/local take;
  `MainActivity` supplies `confirmAttemptFor`, a factory that snapshots the take id **once** and returns an
  attempt closure running the POST on `applicationScope`, so an in-flight request outlives the overlay.
- **Confirm mode is entered synchronously** inside `process()` under `scanMutex`, on the completing
  transition (mirrors iOS `handleCompletionCheck`). A tap already queued on `scanMutex` then sees
  `confirmState != null` inside the lock and is dropped before `onScanTag` can touch the DB.
- **Android simplifications vs iOS:** no `memberWrites` tracking (`onScanTag` awaits every write under
  `scanMutex`, so the row is complete when `isComplete` flips); no scanner stop (reader mode — tags are
  just ignored while `confirmState != null`).

**Deliberately unchanged / unaffected**
- `decidePhotoTarget` (`data/marks/PhotoTarget.kt`, still `complete`): a photo may attach to an unconfirmed take as evidence.
- `ControlTime` (`controlTimeState` filters on `method == "nfc"`, never reads `complete`; start/finish КП are not expected to be cloud/local).
- `MapLogic` pins (where the team was, not the score).
- `marksToTiles` filter (unconfirmed takes keep their tile, with a flag).
- Photo takes are always `offline`.

## Technical Details

**Schema: v7 → v8**
```sql
ALTER TABLE marks ADD COLUMN checkMethod TEXT NOT NULL DEFAULT 'offline';
ALTER TABLE marks ADD COLUMN confirmedAt INTEGER;  -- wall ms; NULL = not confirmed
```
`MarkEntity`: `@ColumnInfo(defaultValue = "offline") val checkMethod: String = "offline"`, `val confirmedAt: Long? = null`.
Room validates the migrated table against `@ColumnInfo(defaultValue)` only (exported as `"defaultValue": "'offline'"`
in `8.json`); the Kotlin `= "offline"` just keeps existing constructor call sites compiling.

`MarkDao.setConfirmedAt(id: String, at: Long)` = `UPDATE marks SET confirmedAt = :at WHERE id = :id`
(no `updatedAt` bump, no version guard).

**Pure rule (`data/marks/CheckMethod.kt`)**
```kotlin
enum class CheckMethod {
    Offline, Cloud, Local;
    val uploadTarget: UploadTarget? get() = when (this) { Cloud -> UploadTarget.Cloud; Local -> UploadTarget.Local; Offline -> null }
    companion object { fun parse(raw: String?): CheckMethod = when (raw) { "cloud" -> Cloud; "local" -> Local; else -> Offline } }
}
fun MarkEntity.isCounted(): Boolean = complete && (CheckMethod.parse(checkMethod) == CheckMethod.Offline || confirmedAt != null)
fun MarkEntity.isUnconfirmed(): Boolean = complete && CheckMethod.parse(checkMethod) != CheckMethod.Offline && confirmedAt == null
```

**`unconfirmedTokens(marks, costOf): List<String>`** — marks newest-first: filter `isUnconfirmed`, drop КП that
have any `isCounted` take, dedupe by `checkpointId` (keep newest), reverse to oldest-first. Token = `"<cost>-<NN>"`
with live `costOf`, or `"NN"` for cost 0 (same format as `photoReviewSummary`).

**Confirm call (`MarkRepository`)**
```kotlin
suspend fun confirm(markId: String, target: UploadTarget, now: Long): UploadResultKind
```
1. `markDao.getById(markId)` — missing → `Error`.
2. POST a one-mark batch through `cloudUploader`/`localUploader` (`backfillTrustedMs(it).toDto()`, `sourceInstallId`).
3. `Success` with `markId ∈ accepted` → `setConfirmedAt(markId, now)` + `markCloudGpsAware`/`markLocalGpsAware`
   for the one-row batch → `Ok`. `Success` without the id → `Error`. Otherwise `uploadResultKind(result)`.
4. Does **not** take `uploadMutex` (a running drain must never skip the confirm). A repeat POST of an already
   uploaded id is safe (server de-dupes by UUID).

**Confirm loop (`ui/scan/ConfirmLoop.kt`)**
```kotlin
sealed interface ConfirmState {
    data class Sending(val target: UploadTarget, val attempt: Int) : ConfirmState
    data object Confirmed : ConfirmState
    data class Failed(val offline: Boolean) : ConfirmState
}
const val CONFIRM_TIMEOUT_MS = 20_000L
const val CONFIRM_RETRY_MS = 3_000L
suspend fun runConfirm(
    target: UploadTarget,
    attempt: suspend () -> UploadResultKind,
    onState: (ConfirmState) -> Unit,
    elapsedNow: () -> Long,
    timeoutMs: Long = CONFIRM_TIMEOUT_MS,
    retryMs: Long = CONFIRM_RETRY_MS,
)
```
Loop: `start = elapsedNow()`; for `n in 1..` { `onState(Sending(target, n))`; `r = attempt()`; `Ok` →
`onState(Confirmed)`, return; `elapsedNow() - start >= timeoutMs` → `onState(Failed(offline = r == Offline))`,
return; `delay(retryMs)` }. Cancellation propagates normally. Real upper bound = timeout + one request timeout.

**ScanSession**: new constructor param `checkMethod: CheckMethod = CheckMethod.Offline` (default on the
constructor, so existing test literals compile), set by `reduce` on `Kp`. `ScanEvent.Kp` gains
`checkMethod: CheckMethod = CheckMethod.Offline` (default keeps ~20 test construction sites compiling;
`classifyTag` always passes it explicitly).

**ScanScreen**
- New param `confirmAttemptFor: (UploadTarget) -> (suspend () -> UploadResultKind)` (default returns an attempt
  that yields `Error`, for previews), held via `rememberUpdatedState` like the other callbacks.
- `var confirmState by remember { mutableStateOf<ConfirmState?>(null) }`, `var confirmAttempt: (suspend () -> UploadResultKind)?`,
  `var confirmJob: Job?`; all reset in `finalizeSession`.
- `process()`: the `confirmState != null` early return is checked **inside** `scanMutex.withLock` (a tap queued
  behind the completing tap must be dropped before `currentOnScanTag` runs). No feedback for dropped taps.
- Completing transition (still under the lock), when `session.checkMethod != Offline`:
  play the ordinary tick (no fanfare), set `confirmState = Sending(target, 1)`, and snapshot
  `confirmAttempt = confirmAttemptFor(target)` — this is the only place the take id is captured.
  Offline keeps today's tick + fanfare.
- A `LaunchedEffect(confirmCycle)` (a counter bumped on entry and on «Повторить») launches `runConfirm(target,
  confirmAttempt, ...)` as `confirmJob`. On `Confirmed`: fanfare, `completed = true`, `SUCCESS_HOLD_MS`,
  `finalizeSession()`, `onCompleted()`, `onClose()`. On `Failed`: stay open. `LaunchedEffect(allScanned)`
  skips its auto-close when `confirmState != null` (the confirm path closes the overlay itself).
- Window timer: the expiry loop checks `confirmState != null` inside its existing `scanMutex.withLock` block
  and `break`s without `finalizeSession()`/`onClose()`. `ScanTimerStrip` (gated at ScanScreen.kt:343) is also
  hidden while `confirmState != null`, so no countdown/«осталось» shows next to «Отправка…»/«Нет связи».
- «Повторить» (only in `Failed`) bumps `confirmCycle`, reusing the same `confirmAttempt` (same take id).
  «Закрыть» and top-bar ✕ close the overlay. The top-bar «Готово» is disabled in `Sending`/`Failed`.
- System back is handled by MainActivity's `BackHandler` → `closeScanOverlay` (ScanScreen has none; do **not**
  add one). Leaving composition cancels `rememberCoroutineScope`, and with it `confirmJob`. An attempt already
  in flight on `applicationScope.async` finishes; if accepted, `confirmedAt` is still written (intended).
- `ConfirmStatus` composable under the КП header:
  - `Sending`: spinner + «Отправка на сервер…» (Cloud) / «Отправка на локальный сервер…» (Local) + «попытка N»
  - `Failed(offline = true)`: «Нет связи — КП не подтверждён»; `Failed(offline = false)`: «Сервер не принял — КП не подтверждён»;
    buttons «Повторить» / «Закрыть».
  - «Готово!» is hidden while `Sending`/`Failed`.
- Pure text mapping `confirmStatusText(state): String` lives in `ConfirmLoop.kt` so it is JVM-testable.

**MainActivity wiring**
```kotlin
confirmAttemptFor = { target ->
    val id = scanTake.markId  // snapshot once, under scanMutex, at the completing transition
    suspend {
        if (id == null) UploadResultKind.Error
        else container.applicationScope.async { markRepo.confirm(id, target, System.currentTimeMillis()) }.await()
    }
}
```

**Marks tab**
- `Mark.unconfirmed: Boolean = false`; `marksToTiles` sets it from `isUnconfirmed()`.
- Tile: ~45% alpha + `Icons.Outlined.CloudOff` corner icon when `unconfirmed`.
- `UnconfirmedNotice` (copied structure of `PhotoReviewNotice`, `errorContainer` palette):
  «Не подтверждены сервером (N): <tokens> — отметьтесь на КП ещё раз при наличии связи». Hidden when empty.
- Lightbox `PhotoKpChip`: extra caption «не подтверждён сервером» when the frame's take is unconfirmed.

## What Goes Where
- **Implementation Steps**: Android code, tests, docs in this repo.
- **Post-Completion**: manual device checks.

## Implementation Steps

### Task 1: Mark fields + MIGRATION_7_8 + DAO support

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/8.json` (generated by the build)
- Modify: `app/src/androidTest/.../MigrationTest.kt`
- Modify: `app/src/androidTest/.../MarkDaoTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt` (`FakeMarkDao` :595)
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryUploadTest.kt` (`FakeMarkUploadDao` :789)

- [x] add `checkMethod: String = "offline"` (`@ColumnInfo(defaultValue = "offline")`) and `confirmedAt: Long? = null` to `MarkEntity` with KDoc
- [x] bump DB to `version = 8`, add `MIGRATION_7_8` (two `ALTER TABLE`), append it to `.addMigrations(...)`
- [x] add `MarkDao.setConfirmedAt(id, at)` column-scoped `UPDATE`
- [x] implement `setConfirmedAt` in both fake DAOs (column only; `updatedAt` untouched) so the unit-test build compiles
- [x] build to export `schemas/8.json`; check `checkMethod` has `"defaultValue": "'offline'"`; commit it
- [x] instrumented test: `7 → 8` migration keeps old rows with `checkMethod == "offline"`, `confirmedAt == null`, via `runMigrationsAndValidate(testDb, 8, true, MIGRATION_7_8)` (pattern at MigrationTest.kt:256)
- [x] instrumented tests: `addMember` resets `uploaded*` but keeps `checkMethod`/`confirmedAt`; `setConfirmedAt` round-trip, does not bump `updatedAt`; missing id is a no-op
- [x] run `./gradlew testDebugUnitTest` + `connectedDebugAndroidTest` — must pass before next task (unit tests + lint pass; instrumented run deferred to user — physical device attached; compiled via assembleDebugAndroidTest)

### Task 2: CheckMethod + isCounted in metrics

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/marks/CheckMethod.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/marks/CheckMethodTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt`

- [x] create `CheckMethod` (`parse`, `uploadTarget`), `isCounted`, `isUnconfirmed`
- [x] `takenPointCount` (both), `takenPoints`, `totalScore`: use `isCounted` instead of `complete`; update KDoc
- [x] confirm `MainActivity.kt:913` (`takenIds`) needs no change (goes through `takenPoints`) — confirmed, no change
- [x] tests: parse `"offline"`, `"cloud"`, `"local"`, `"online"`, `"local_server"`, `""`, `null` (unknown → Offline); `uploadTarget` mapping
- [x] tests: `isCounted`/`isUnconfirmed` matrix (complete × method × confirmedAt)
- [x] tests: metrics exclude an unconfirmed cloud take, include a confirmed one, offline unchanged
- [x] run tests — must pass before next task

### Task 3: Marks display (tiles flag, tokens, notices)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt` (pure functions only in this task)
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/marks/MarksMappingTest.kt`

- [x] `photoReviewSummary`, `hiddenTakenTokens`: use `isCounted` where they use `complete`
- [x] `Mark.unconfirmed: Boolean = false`; `marksToTiles` keeps all `complete` takes and sets the flag
- [x] add `unconfirmedTokens(marks, costOf)` per Technical Details
- [x] tests: `marksToTiles` keeps the unconfirmed tile and sets the flag; confirmed/offline → false
- [x] tests: `unconfirmedTokens` dedupe per КП, excludes КП with a counted take, oldest-first, cost-0 token
- [x] tests: `hiddenTakenTokens` ignores unconfirmed takes
- [x] tests (cross-case): КП with an unconfirmed cloud NFC take **plus** a photo take → the photo take is no longer "chip-verified", so the КП shows in `photoReviewSummary` (and counts via the photo), and is **not** in `unconfirmedTokens`
- [x] existing tests stay green
- [x] run tests — must pass before next task

### Task 4: Tag method flows into the take

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (pass-through only)
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/LegendRepositoryTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanTagDecisionTest.kt`, `ScanSessionTest.kt`, `ScanFeedbackTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryTest.kt`

- [ ] `UnlockOutcome.Revealed`/`IdentityOnly` gain `checkMethod: String`; `unlock` fills it from `tagEntity` at all **three** sites (LegendRepository.kt:163 early `IdentityOnly`, :185 `Revealed`, :187 engine `IdentityOnly`)
- [ ] update test call sites: LegendRepositoryTest.kt:357, :386 (equality asserts); ScanTagDecisionTest.kt:34, 46, 70, 82
- [ ] `ScanEvent.Kp` gains `checkMethod: CheckMethod`; `classifyTag` parses it from the outcome
- [ ] `ScanSession.checkMethod` (default `Offline`), set by `reduce` on `Kp`
- [ ] `MarkRepository.startKpTake(checkMethod: String)` writes it; `createPhotoMark` stays `"offline"` (entity default)
- [ ] `MainActivity.onScanTag` passes `event.checkMethod` to `startKpTake`
- [ ] tests: `unlock` returns the tag's method for revealed and identity-only tags
- [ ] tests: `classifyTag` carries the method (cloud/local/unknown→offline); `reduce` sets `session.checkMethod`; `startKpTake` persists it; photo mark is `"offline"`
- [ ] run tests — must pass before next task

### Task 5: MarkRepository.confirm

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryUploadTest.kt`

- [ ] add `confirm(markId, target, now)` per Technical Details (no `uploadMutex`)
- [ ] on accept: `setConfirmedAt` + the GPS-aware `uploaded*` setter for that target only
- [ ] tests: cloud → only the cloud uploader is called, `confirmedAt` + `uploadedCloud` set, `Ok`
- [ ] tests: local → only the local uploader is called, `confirmedAt` + `uploadedLocal` set
- [ ] tests: offline, 5xx, 200 without the id → `confirmedAt == null`, correct kind; missing mark → `Error`
- [ ] tests: confirm while a drain holds `uploadMutex` (gated fake uploader) still POSTs
- [ ] tests: the background drain alone never sets `confirmedAt`
- [ ] tests: GPS fix attached between fetch and mark during confirm → `confirmedAt` set, `uploadedCloud` stays false (GPS guard fails, row re-uploads later)
- [ ] run tests — must pass before next task

### Task 6: Pure confirm loop

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ConfirmLoop.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ConfirmLoopTest.kt`

- [ ] add `ConfirmState`, `CONFIRM_TIMEOUT_MS`, `CONFIRM_RETRY_MS`, `runConfirm`, `confirmStatusText`
- [ ] tests (`runTest`, `elapsedNow = { testScheduler.currentTime }`): first-try `Ok` → `Sending(1)`, `Confirmed`
- [ ] tests: fail once then `Ok` → `Sending(1)`, `Sending(2)`, `Confirmed`; retry spacing = 3 s
- [ ] tests: always `Offline` → `Failed(offline = true)` after 20 s; always `Error` → `Failed(offline = false)`
- [ ] tests: cancel during the retry delay → no more attempts, no terminal state
- [ ] tests: cancel during an in-flight (suspended fake) `attempt()` → no terminal state, no further attempts
- [ ] tests: an attempt that starts before and returns `Ok` after the deadline → `Confirmed`, not `Failed`
- [ ] tests: `confirmStatusText` for Sending(Cloud/Local, n) and both Failed variants
- [ ] run tests — must pass before next task

### Task 7: ScanScreen confirm mode + MainActivity wiring

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `confirmAttemptFor` param (via `rememberUpdatedState`), `confirmState`, `confirmAttempt`, `confirmCycle`, `confirmJob`; reset in `finalizeSession`
- [ ] `process()`: drop taps while `confirmState != null`, checked **inside** `scanMutex.withLock`
- [ ] completing transition under the lock: cloud/local → tick only, `confirmState = Sending(target, 1)`, snapshot `confirmAttempt`; offline unchanged
- [ ] `LaunchedEffect(confirmCycle)` runs `runConfirm` as `confirmJob`; `Confirmed` → fanfare, «Готово!», hold, `onCompleted`, `onClose`; `Failed` → stay; `LaunchedEffect(allScanned)` skips auto-close in confirm mode
- [ ] window-expiry loop: check `confirmState` inside its `scanMutex.withLock`, `break` without finalize/close; hide `ScanTimerStrip` while `confirmState != null`
- [ ] `ConfirmStatus` composable (spinner / texts / «Повторить» / «Закрыть»); hide «Готово!» and disable the top-bar «Готово» in Sending/Failed; no BackHandler in ScanScreen
- [ ] `MainActivity`: wire `confirmAttemptFor` (id snapshot once, POST via `applicationScope.async { markRepo.confirm(...) }.await()`)
- [ ] no new unit tests (Compose UI, untested by convention; logic covered in Tasks 4–6); `./gradlew assembleDebug lintDebug testDebugUnitTest` must pass

### Task 8: Marks tab UI (tile, notice, lightbox)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (only if the notice input is computed there)

- [ ] tile: ~45% alpha + `Icons.Outlined.CloudOff` corner icon when `unconfirmed`
- [ ] `UnconfirmedNotice` (copy of `PhotoReviewNotice` structure), fed by `unconfirmedTokens` with live `costOf`; hidden when empty
- [ ] lightbox `PhotoKpChip`: caption «не подтверждён сервером» for unconfirmed takes
- [ ] no new unit tests (Compose; logic covered in Task 3); `./gradlew assembleDebug lintDebug testDebugUnitTest` must pass

### Task 9: Verify acceptance criteria
- [ ] verify all requirements from Overview are implemented
- [ ] verify edge cases: a confirmed retake counts the КП and drops it from the notice; legend method change doesn't affect old takes; unknown method = offline; photo takes = offline; closing during Sending leaves the take unconfirmed, and a late accepted POST still sets `confirmedAt`
- [ ] run full suite: `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] run instrumented: `./gradlew connectedDebugAndroidTest`

### Task 10: [Final] Update documentation
- [ ] `docs/design/DATA-NOTES.md`: Room v8, `checkMethod`/`confirmedAt`, `setConfirmedAt`, `confirm()` without the mutex, `isCounted`
- [ ] `docs/design/UI-NOTES.md`: ScanScreen confirm mode, `ConfirmLoop`, marks tile/notice/lightbox
- [ ] `CLAUDE.md` (compact): Room **v8**, `MIGRATION_7_8` in the `connectedDebugAndroidTest` line, `CheckMethod` in the pure-models list, one line: "`isCounted` replaces `complete` for scoring; `confirmedAt` is set only from the open scan overlay"
- [ ] `docs/design/UPLOAD.md`: single-mark confirm POST from the scan overlay, bypasses the drain mutex, sets `confirmedAt`
- [ ] `CLAUDE.md` «Dual-target upload loop» bullet: note that `MarkRepository.confirm` is the one marks POST outside `tryLock`
- [ ] `docs/IOS-PARITY.md`: remove item 1
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Server**
- Values already renamed (kolco24 `11c7a19`). `POST /app/race/<id>/marks/` must be deployed on prod before any tag
  is switched to `cloud`.

**Known limitation**
- A non-rotation config change (dark mode, locale, window size) recreates the Activity. `showScan` survives, but
  ScanScreen's `session`/`confirmState` and MainActivity's `scanTake` are plain `remember`: an in-progress confirm
  stops and the take stays unconfirmed. Same as today's session behavior; accepted.

**Manual verification on a device**
- Offline КП: scan → auto-close + confetti as before.
- Cloud КП with network: «Отправка на сервер…» → confirmed → auto-close; tile normal; score counts.
- Cloud КП in airplane mode: after ~20 s «Нет связи»; «Повторить» after turning the network on → confirmed.
- Close on failure: tile dimmed with the icon, notice lists the КП, score and legend don't count it; a later
  background upload does not change this.
- Local КП on the race LAN.
