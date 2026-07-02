# Judge-scan upload status in «Загрузка данных»

## Overview

Add a **«Судейские отметки»** section to the «Загрузка данных» overlay (`UploadScreen`) so a
judge can confirm every start/finish pik recorded on the device has reached the server. Today the
overlay shows three sections — Отметки / Фото / GPS-трек — each a receipt card with an «Интернет»
(cloud) row and a «Финиш» (LAN) row, `n/total` counts plus the last-flush outcome. Judge scans
(`JudgeScanRepository`) run the same dual-target upload loop but currently surface **no count flow
and track no upload outcomes**, so there is no way to verify they were sent.

This change makes judge scans a fourth section that reuses the existing receipt UI verbatim.

**Problem solved:** a judge has no in-app confirmation that start/finish piks uploaded — this closes
that gap using the established upload-status pattern.

## Context (from discovery)

- **Files/components involved:**
  - `ui/upload/UploadScreen.kt` — the overlay; renders `UploadSection`/`ReceiptLine`, gated by
    `showFinishLine`. Stateless; host derives all `TrackUploadStatus` values.
  - `ui/upload/UploadStatusModels.kt` — `TrackUploadStatus(total, local, cloud)` + `TargetLine`.
  - `data/JudgeScanRepository.kt` — raceId-scoped, write-once rows, dual-target upload loop;
    `uploadLoop` already returns terminal `UploadResultKind?` but `flushRace` discards it.
  - `data/db/JudgeScanDao.kt` — has `unuploaded*`/`markUploaded*`/`pendingUploadRaces`; **no counts query**.
  - `data/db/TrackDao.kt` — home of `data class UploadCounts(total, local, cloud)` (reused as-is).
  - `data/db/MarkDao.kt` — `uploadCountsMetadata` is the exact template for the new judge counts query.
  - `AppContainer.kt` — `markUploadOutcomes`/`trackUploadOutcomes` StateFlows + repo `onUploadOutcome` wiring.
  - `MainActivity.kt` — `rememberUploadStatus` (team+race scoped), collects outcome maps, passes
    statuses + PTR `onRefresh` (supervisorScope) into `UploadScreen`.
- **Related patterns found:** the marks outcome plumbing (`onUploadOutcome` → `markUploadOutcomes`
  StateFlow → `rememberUploadStatus` → `UploadScreen`) is the pattern being mirrored, minus the team
  dimension. `showFinishLine` keeps the LAN row silent until it reports.
- **Dependencies identified:** existing `UploadCounts`, `TargetUploadOutcome`, `UploadTarget`,
  `UploadResultKind`, `TrackUploadStatus`/`TargetLine`, `UploadSection`/`ReceiptLine`/`showFinishLine`.
  No new pure model. Prior art: `docs/plans/20260703-judge-start-finish-scan.md` (the feature that
  introduced judge scans).

## Development Approach

- **Testing approach:** Regular (code first, then tests) — matches project convention; the pure/trust
  boundaries are the tested surfaces, Compose wiring is untested by convention.
- Complete each task fully (including its tests) before starting the next.
- Small, focused changes; keep the four upload sections consistent (duplicate-don't-couple — the
  race-only status derivation is a deliberate sibling of `rememberUploadStatus`, not a refactor).
- **Every task with code changes includes new/updated tests.** All tests pass before the next task.
- Maintain backward compatibility — the new `JudgeScanRepository` param and `UploadScreen` param both
  default, so every existing call site is unaffected.

## Testing Strategy

- **Unit tests (JVM):** `JudgeScanRepositoryTest` — the `onUploadOutcome` callback contract.
- **Instrumented tests (Room):** `JudgeScanDaoTest` — the new `uploadCounts` flow (guarded by
  `connectedDebugAndroidTest`, already listed in CLAUDE.md's connected-test inventory).
- **Existing coverage reused:** `FinishLineVisibilityTest` covers `showFinishLine`; the new section
  reuses it, so no new UI logic to test. The race-only status derivation in `MainActivity` and the
  `UploadScreen` wiring are Compose — untested by convention.
- No e2e framework in this project (Android instrumented tests are the closest tier).

## Progress Tracking

- Mark completed items `[x]` immediately.
- New tasks get a ➕ prefix; blockers get a ⚠️ prefix.
- Keep this file in sync if scope shifts during implementation.

## Solution Overview

Four additive layers, mirroring the marks plumbing with the **team dimension dropped** (judge scans
are `raceId`-scoped only):

1. **DAO** — one new `uploadCounts(raceId): Flow<UploadCounts>` query (aggregate over existing
   `uploadedLocal`/`uploadedCloud` columns). **No schema change, no migration.**
2. **Repo** — a passthrough for that flow + an `onUploadOutcome` callback fired per target from
   `flushRace`, only when the loop actually attempted (non-null terminal kind).
3. **Container** — a `judgeScanUploadOutcomes` StateFlow keyed by `(raceId, UploadTarget)` (transient,
   in-memory, no persistence — re-derived by the opportunistic flush within seconds; no `onScopeCleared`
   because rows are write-once).
4. **UI wiring** — a race-only `rememberJudgeUploadStatus` in `MainActivity` feeds a new `judge`
   param into `UploadScreen`, which renders one more `UploadSection`. PTR also flushes judge scans.

**Key design decisions:**
- **One combined section** «Судейские отметки» summing both `eventType`s (start+finish) — *not* split
  into Старт/Финиш cards. A «Финиш» judge card would collide with the «Финиш» LAN-target row label.
  Combined also maps one-to-one onto the repo's existing raceId-only scope (one query, one outcome key).
- **No admin-mode gate** — the section is hidden when `total == 0` like every other section, so it
  appears only on devices that actually recorded judge scans.
- **Race-only sibling, not a generalized helper** — `rememberJudgeUploadStatus` duplicates the small
  scope-guard logic rather than parameterizing `rememberUploadStatus` over an optional teamId, keeping
  the two derivations decoupled.

## Technical Details

- **New DAO query** (mirror of `MarkDao.uploadCountsMetadata`, teamId dropped; note the existing
  DAO's comment about explicit `= :raceId` to avoid the truthy-column bug):
  ```sql
  SELECT COUNT(*) AS total,
         COALESCE(SUM(CASE WHEN uploadedLocal THEN 1 ELSE 0 END), 0) AS local,
         COALESCE(SUM(CASE WHEN uploadedCloud THEN 1 ELSE 0 END), 0) AS cloud
  FROM judge_scans WHERE raceId = :raceId
  ```
- **Repo callback signature:** `onUploadOutcome: (raceId: Int, target: UploadTarget, kind: UploadResultKind) -> Unit`
  (stateless — `raceId` threaded through from `flushRace`, so the container lambda needs no captured scope).
- **Outcome map key:** `Pair<Int, UploadTarget>` (raceId, target) — distinct from the marks/track maps
  keyed by `(TrackScope, UploadTarget)`, so it gets its own StateFlow.
- **Emit rule:** in `flushRace`, capture each `uploadLoop` return; call `onUploadOutcome` only when the
  returned `UploadResultKind?` is non-null (null = nothing pending, loop didn't attempt — leave the
  prior outcome untouched, mirroring `MarkRepository`'s idle-reflush rule).
- **Target mapping:** local loop → `UploadTarget.Local`, cloud loop → `UploadTarget.Cloud`.

## What Goes Where

- **Implementation Steps** (checkboxes): DAO query, repo counts+callback, container StateFlow+wiring,
  MainActivity derivation+PTR, UploadScreen section, docs, and their tests.
- **Post-Completion** (no checkboxes): on-device manual verification (record piks, watch the section
  reach `n/n` on both targets); relies on the server judge-scan endpoint already covered by the
  judge-start-finish-scan feature.

## Implementation Steps

### Task 1: Add `uploadCounts` query to `JudgeScanDao`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/JudgeScanDao.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/JudgeScanDaoTest.kt`

- [x] add `fun uploadCounts(raceId: Int): Flow<UploadCounts>` with the aggregate query above
      (import `androidx.room.*` `Flow`/`UploadCounts` as the other DAOs do); keep the explicit
      `raceId = :raceId` and a short comment matching the existing DAO style
- [x] confirm no schema bump is implied (read-only aggregate over existing columns — no `schemas/*.json`
      change, no `MIGRATION_*` needed)
- [x] write `JudgeScanDaoTest` case: total/local/cloud all reflect inserts (0 uploaded → local=0/cloud=0)
- [x] write `JudgeScanDaoTest` case: after `markUploadedLocal`/`markUploadedCloud`, the respective
      counts advance independently
- [x] write `JudgeScanDaoTest` case: rows of a **different** raceId are excluded from the scope
- [x] run `./gradlew connectedDebugAndroidTest` (or the `JudgeScanDaoTest` subset) — must pass before Task 2
      (skipped - no emulator/device available in this environment; verified `assembleDebug` and
      `compileDebugAndroidTestSources` both build cleanly)

### Task 2: Add counts passthrough + `onUploadOutcome` to `JudgeScanRepository`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/JudgeScanRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/JudgeScanRepositoryTest.kt`

- [x] add constructor param `onUploadOutcome: (raceId: Int, target: UploadTarget, kind: UploadResultKind) -> Unit = { _, _, _ -> }`
      (import `UploadTarget`); keep it last so existing call sites are unaffected
- [x] add `fun uploadCounts(raceId: Int): Flow<UploadCounts> = judgeScanDao.uploadCounts(raceId)` passthrough
- [x] update the in-test `FakeJudgeScanDao` (in `JudgeScanRepositoryTest.kt`) to implement the new
      `uploadCounts` method, or the test module won't compile
- [x] in `flushRace`, capture the local `uploadLoop` return and, when non-null, call
      `onUploadOutcome(raceId, UploadTarget.Local, kind)`; same for cloud with `UploadTarget.Cloud`
- [x] add KDoc noting the callback fires only when a loop attempted (null → untouched), mirroring `MarkRepository`
- [x] write test: successful drain of both targets → callback fires `Ok` for Local and Cloud
- [x] write test: an `Offline`/`Error` upload response → callback fires the mapped kind for that target
- [x] write test: idle re-flush (no pending rows → `uploadLoop` returns null) → callback **not** invoked
- [x] run `./gradlew testDebugUnitTest` (JudgeScanRepositoryTest) — must pass before Task 3

### Task 3: Add `judgeScanUploadOutcomes` StateFlow + wire the repo in `AppContainer`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] add `val judgeScanUploadOutcomes: MutableStateFlow<Map<Pair<Int, UploadTarget>, TargetUploadOutcome>> = MutableStateFlow(emptyMap())`
      with KDoc matching `markUploadOutcomes` (transient/in-memory rationale; no `onScopeCleared` since write-once)
- [ ] wire `onUploadOutcome = { raceId, target, kind -> judgeScanUploadOutcomes.update { it + ((raceId to target) to TargetUploadOutcome(kind, System.currentTimeMillis())) } }`
      into the `judgeScanRepository` builder
- [ ] no test (manual DI wiring, untested by convention — the callback contract is covered in Task 2)
- [ ] build check: `./gradlew assembleDebug` compiles — must pass before Task 4

### Task 4: Derive judge status + pass into `UploadScreen` in `MainActivity`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add a private `rememberJudgeUploadStatus(raceId: Int?, outcomes: Map<Pair<Int, UploadTarget>, TargetUploadOutcome>, counts: (raceId: Int) -> Flow<UploadCounts>): TrackUploadStatus?`
      — a race-only sibling of `rememberUploadStatus`: `produceState` keyed on `raceId`, guards
      `raceId != null` and `total > 0`, keys outcomes by `raceId to target`
- [ ] collect `val judgeScanUploadOutcomes by container.judgeScanUploadOutcomes.collectAsState()`
- [ ] derive `val judgeUploadStatus = rememberJudgeUploadStatus(selectedRaceId, judgeScanUploadOutcomes, container.judgeScanRepository::uploadCounts)`
      (add a `judgeScanRepo` local if it reads cleaner, matching the `trackRepo`/`markRepo` pattern)
- [ ] pass `judge = judgeUploadStatus` into the `UploadScreen(...)` call
- [ ] add a third `launch { container.judgeScanRepository.uploadAllPending() }` inside the PTR
      `onRefresh` `supervisorScope` (alongside track + marks)
- [ ] no test (Compose wiring, untested by convention)
- [ ] build check: `./gradlew assembleDebug` compiles — must pass before Task 5

### Task 5: Render the «Судейские отметки» section in `UploadScreen`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/upload/UploadScreen.kt`

- [ ] add param `judge: TrackUploadStatus? = null` to `UploadScreen`
- [ ] include `judge` in the `hasAny` list gate
- [ ] render `UploadSection(title = "Судейские отметки", status = judge, nowMs = nowMs)` after GPS-трек
- [ ] update the KDoc header (currently "up to three UploadSections") to say four
- [ ] update the other stale "three" strings in the same file: the empty-state KDoc "Nothing recorded
      yet in any of the three scopes" and the user-facing empty-state body "статус загрузки отметок,
      фото и GPS-трека" (extend to include судейские отметки)
- [ ] no new test — the section reuses `UploadSection`/`ReceiptLine`/`showFinishLine`, already covered
      by `FinishLineVisibilityTest`
- [ ] build check: `./gradlew assembleDebug` compiles — must pass before Task 6

### Task 6: Verify acceptance criteria

- [ ] a device with judge scans for the selected race shows the «Судейские отметки» section; a device
      with none does not (total == 0 gate)
- [ ] «Интернет» always shown; «Финиш» appears once the LAN target reports (`showFinishLine`)
- [ ] pull-to-refresh flushes judge scans and the counts advance toward `n/n`
- [ ] run `./gradlew lintDebug` — must pass
- [ ] run `./gradlew testDebugUnitTest` — must pass
- [ ] run `./gradlew connectedDebugAndroidTest` (if an emulator/device is available) — must pass

### Task 7: [Final] Update documentation

**Files:**
- Modify: `docs/design/DATA-NOTES.md`
- Modify: `docs/design/UI-NOTES.md`

- [ ] DATA-NOTES: update `JudgeScanRepository` / `JudgeScanDao` entries with the new `uploadCounts`
      flow + `onUploadOutcome` callback (and the `AppContainer.judgeScanUploadOutcomes` StateFlow)
- [ ] UI-NOTES: update the `UploadScreen` entry — now four sections incl. race-scoped «Судейские отметки»
- [ ] CLAUDE.md: no change needed (no new pattern; connected-test inventory already lists JudgeScanDaoTest)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Informational — no checkboxes.*

**Manual verification:**
- On a physical device, open Admin → judge start/finish scan, record a few piks with the LAN server
  unreachable, confirm «Судейские отметки» shows «Интернет» climbing and «Финиш» silent; then on the
  finish-line wifi, confirm «Финиш» appears and both reach `n/n`.
- Confirm the section stays hidden on a non-judge device (no piks recorded).

**External systems:**
- Relies on the server judge-scan upload endpoint delivered with the judge-start-finish-scan feature
  (`docs/plans/20260703-judge-start-finish-scan.md`) — no new server contract.
