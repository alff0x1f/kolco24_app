# Track Upload Status Feedback (collapsible row in TrackCard)

## Overview
- Surface GPS-track upload status to the user: how much of the track is uploaded, to which of the two targets (cloud HTTPS «Интернет» / local LAN «Локальный»), and — when something is still pending — the last-attempt time and result (success / нет сети / ошибка).
- Placed low-key: one collapsible row at the bottom of the existing `TrackCard` on the «Команда» tab. Not prominent, must not compete with the orange «Начать запись» CTA.
- **Adaptive rule** (the core UX requirement): everything uploaded → a calm one-liner «Загружено» (per-server counters only on expand); something still pending → show last-attempt time + result per target.
- Solves the current blind spot: every `TrackPointEntity` already carries `uploadedLocal`/`uploadedCloud` flags and uploads run to two independent targets, but **nothing** in the UI reflects whether/where/when a flush happened.

## Context (from discovery)
- Files/components involved:
  - `data/db/TrackDao.kt` — upload queries already scoped by `(raceId, teamId)`; `countForTeam` exists. No `uploadedLocal`/`uploadedCloud` count query yet.
  - `data/track/TrackRepository.kt` — owns `uploadPending`/`uploadAllPending` → `flushScope` → `uploadLoop` (per target). `uploadLoop` returns `Unit` today and silently `break`s on each terminal condition. Functional-seam convention already present (`TrackUploader fun interface`, injected `cloudUploader`/`localUploader`, `wallProvider`).
  - `data/track/TrackModels.kt` — pure, Android-free model home (`RawFix`, `filterPoints`, mappers). Natural place for the new pure model.
  - `data/track/PointsPlural.kt` — existing pure RU pluralization helpers (`pointsWord`, `segmentsWord`) — pattern to mirror for any new pure helper / a sensible neighbour for a relative-time formatter, though the formatter is track-status-specific.
  - `AppContainer.kt` — `trackRepository by lazy { TrackRepository(...) }` at line ~205, already passes `wallProvider = { System.currentTimeMillis() }`. Owns other `MutableStateFlow`s (`trackRecordingState`).
  - `MainActivity.kt` — already derives the `track`/`count` flows per `selectedTeamId`/scope and wires `TrackCard`. Host derivation point.
  - `ui/track/TrackCard.kt` — stateless card; `TrackMetrics` uses monospace counts (`FontFamily.Monospace`). New status row goes below it.
- Related patterns found:
  - Reactive Room `Flow<DataClass>` with `COUNT(...)`/`SUM(...) AS` columns.
  - Injected functional seam + default no-op for unit-testability (`TrackUploader`).
  - Pure JVM-tested models in `data/track` (`TrackModels`, `TrackProfile`, `PointsPlural`).
  - Null-guarded keyed-`remember` flow + `collectAsState` in `MainActivity`.
- Dependencies identified:
  - `PostResult` (`Success`/`Offline`/`Forbidden`/`Error`/…) drives the outcome mapping.
  - `TrackScope(raceId, teamId)` already exists in `TrackDao.kt`.
  - `TrackRepositoryTest.kt` already exercises the upload loops with fake `TrackDao`/`TrackUploader`s — extend it.

## Development Approach
- **testing approach**: Regular (code first, then tests) — matches the repo's existing test-after style; pure model + repo reporting are JVM-unit-tested, Compose UI untested per repo convention.
- complete each task fully before moving to the next; small focused changes.
- **CRITICAL: every task with logic changes MUST include new/updated unit tests** (success + error/edge). Compose-only changes (`TrackCard`) are exempt per the documented repo convention ("Compose UI untested").
- **CRITICAL: all tests must pass before starting next task.**
- maintain backward compatibility: the new `TrackRepository` callback param defaults to a no-op so existing `TrackRepository(...)` construction and tests keep compiling; the new `TrackCard` param is nullable.
- **No Room schema change** — counts derive from existing flags; outcome lives in-memory (avoids a migration on the shipped DB).

## Testing Strategy
- **unit tests**:
  - Pure model: `UploadResultKind` mapping from `PostResult` (if a mapper helper is added), relative-time formatter boundaries.
  - `TrackRepositoryTest`: assert `onUploadOutcome` is invoked with the correct `UploadTarget` + `UploadResultKind` (Ok / Offline / Error / no-progress→Error), and **NOT** invoked when a target has nothing pending (empty first fetch → `null` → no report).
- **e2e tests**: project has no UI e2e harness (Compose, manual). N/A.
- Run: `./gradlew testDebugUnitTest` after each task; `./gradlew lintDebug` before finishing.

## Progress Tracking
- mark completed items `[x]` immediately when done.
- ➕ prefix for newly discovered tasks; ⚠️ prefix for blockers.
- keep this file in sync with actual work.

## Solution Overview
Two independent data sources, joined in the host:
1. **Counts (durable, reactive)** — a new `TrackDao.uploadCounts(teamId, raceId): Flow<UploadCounts>` over the existing `uploadedLocal`/`uploadedCloud` flags. This is the source of truth for "how much / where".
2. **Outcome (transient, in-memory)** — `TrackRepository` reports each flush's terminal result per target through an injected callback; `AppContainer` accumulates it in a `MutableStateFlow<Map<Pair<TrackScope, UploadTarget>, TargetUploadOutcome>>`. This is the source of truth for "when / what error", and is intentionally not persisted (refreshed within seconds on Launch B after a restart). New points **keep** the last outcome (read as «90/100 · 5 мин назад · ok» = "last attempt succeeded, more points since"); only a destructive «Очистить трек» resets it, via a second `onScopeCleared` seam fired from `deleteForTeam`.

`MainActivity` zips counts + outcomes for the selected scope into a `TrackUploadStatus` view-model and passes it (nullable) to `TrackCard`, which renders one adaptive collapsible row.

Key design decisions:
- In-memory outcome (no new table) — per the shipped-DB-needs-real-migrations constraint; the outcome is diagnostic and self-heals on next flush.
- `uploadLoop` returns `UploadResultKind?` where `null` = "no attempt made (nothing pending)" so an idle re-flush never overwrites a real "ошибка" with a misleading "ok".
- Reporting via an injected default-no-op lambda keeps the repo pure-testable and all existing call sites/tests compiling.

## Technical Details
- **`UploadCounts`**: `data class UploadCounts(val total: Int, val local: Int, val cloud: Int)`.
  Query: `SELECT COUNT(*) AS total, COALESCE(SUM(uploadedLocal),0) AS local, COALESCE(SUM(uploadedCloud),0) AS cloud FROM track_points WHERE teamId=:teamId AND raceId=:raceId` (SUM over 0/1 flags = uploaded count; COALESCE guards empty-table NULL).
- **Pure model** (in `TrackModels.kt`):
  - `enum class UploadTarget { Local, Cloud }`
  - `enum class UploadResultKind { Ok, Offline, Error }`
  - `data class TargetUploadOutcome(val kind: UploadResultKind, val atWallMs: Long)`
  - Mapping: `Ok` = loop drained to empty / clean `Success`; `Offline` = `PostResult.Offline`; `Error` = `Forbidden`/`Error`/no-forward-progress break.
- **`TrackRepository`**:
  - New ctor params: `private val onUploadOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> }` and `private val onScopeCleared: (TrackScope) -> Unit = {}`.
  - `uploadLoop` returns `UploadResultKind?`: first `fetch()` empty (nothing pending) → `null`; drained to empty after progress → `Ok`; `PostResult.Offline` → `Offline`; non-success or no-progress `Success` → `Error`.
  - `flushScope(raceId, teamId)`: capture each `uploadLoop` return; if non-null call `onUploadOutcome(TrackScope(raceId, teamId), Local/Cloud, kind)`.
  - `deleteForTeam` (only): call `onScopeCleared(TrackScope(raceId, teamId))`. `insertAll` does **not** clear — new points keep the last outcome.
- **`AppContainer`**:
  - `val trackUploadOutcomes = MutableStateFlow<Map<Pair<TrackScope, UploadTarget>, TargetUploadOutcome>>(emptyMap())`.
  - `trackRepository` lazy passes `onUploadOutcome = { scope, target, kind -> trackUploadOutcomes.update { it + ((scope to target) to TargetUploadOutcome(kind, System.currentTimeMillis())) } }` and `onScopeCleared = { scope -> trackUploadOutcomes.update { it - (scope to UploadTarget.Local) - (scope to UploadTarget.Cloud) } }`.
- **View-model** (declared in `ui/track/TrackCard.kt`, not the host — finding 2):
  - `data class TargetLine(val uploaded: Int, val total: Int, val outcome: TargetUploadOutcome?)`
  - `data class TrackUploadStatus(val total: Int, val local: TargetLine, val cloud: TargetLine) { val fullyUploaded get() = total > 0 && local.uploaded == total && cloud.uploaded == total }`
  - `MainActivity` derives it by combining the scope-keyed `uploadCounts` (`produceState`, reset to `null` on scope change — finding 3) with `container.trackUploadOutcomes` for `(scope, Local)`/`(scope, Cloud)`; passed to `TrackCard` (null / `total==0` → row hidden).
- **`TrackCard` UI** (below `TrackMetrics`, local `rememberSaveable expanded`):
  - Collapsed + `fullyUploaded` → `tertiary` check + «Загружено» (tap expands to confirm both servers).
  - Collapsed + pending → `onSurfaceVariant` cloud-off glyph + «Загрузка · осталось N» (N = max pending across targets) + chevron.
  - Expanded → two lines per server: «☁ Интернет 120 / 138  2 мин назад · ошибка сети» / «🖧 Локальный 138 / 138 ✓». Done target = green check only; pending target = `uploaded / total` (monospace) + relative time + outcome label (нет сети / ошибка).
  - Relative-time formatter `"N мин назад"` (`SimpleDateFormat`/arithmetic, no `java.time`; `< 1 мин` → «только что»).

## What Goes Where
- **Implementation Steps** (`[ ]`): DAO query, pure model, repo reporting, container wiring, host derivation, Compose row, tests, docs.
- **Post-Completion** (no checkboxes): manual on-device verification of the live/pending/error states against the two real servers.

## Implementation Steps

### Task 1: Add `UploadCounts` reactive query to `TrackDao`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/TrackDao.kt`

- [x] add `data class UploadCounts(val total: Int, val local: Int, val cloud: Int)` (top-level in the file, beside `TrackScope`)
- [x] add `@Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN uploadedLocal THEN 1 ELSE 0 END),0) AS local, COALESCE(SUM(CASE WHEN uploadedCloud THEN 1 ELSE 0 END),0) AS cloud FROM track_points WHERE teamId=:teamId AND raceId=:raceId") fun uploadCounts(teamId: Int, raceId: Int): Flow<UploadCounts>` — explicit `CASE` over the Boolean column (`SUM(boolean)` is fragile for codegen/type-mapping); `COALESCE(...,0)` guards the empty-scope `NULL` so the non-null `Int` columns always map
- [x] verify column-name aliases (`total`/`local`/`cloud`) match the data-class property names (Room maps by name)
- [x] implement `override fun uploadCounts(teamId, raceId): Flow<UploadCounts>` in `FakeTrackDao` (`TrackRepositoryTest.kt:319`) — derive over its in-memory `rows` filtered by `(teamId, raceId)` (counting `uploadedLocal`/`uploadedCloud`); **required or Task 3 won't compile** (adding an interface method breaks the only test impl)
- [x] build to confirm Room codegen accepts the query + tests still compile: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
- [x] no unit test for the production DAO query (trivial; Room compile-time verification + the `FakeTrackDao` impl exercise it) — an instrumented test could guard the SUM-over-boolean/COALESCE-on-empty semantics; note rationale in commit

### Task 2: Add pure upload-outcome model

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt`
- Modify (tests): `app/src/test/java/ru/kolco24/kolco24/data/track/TrackPointMappingTest.kt` (or new `UploadOutcomeTest.kt`)

- [x] add `enum class UploadTarget { Local, Cloud }`
- [x] add `enum class UploadResultKind { Ok, Offline, Error }`
- [x] add `data class TargetUploadOutcome(val kind: UploadResultKind, val atWallMs: Long)`
- [x] add a pure mapper `fun uploadResultKind(result: PostResult<*>): UploadResultKind` (`Success`→`Ok`, `Offline`→`Offline`, else→`Error`) so the repo and tests share one mapping
- [x] write tests for `uploadResultKind` covering `Success`, `Offline`, `Forbidden`/`Error` → `Error`
- [x] run tests — must pass before next task

### Task 3: Report per-target outcome from `TrackRepository`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackRepositoryTest.kt`

- [x] add ctor param `private val onUploadOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _, _, _ -> }` (default no-op, after the uploaders)
- [x] restructure `uploadLoop` to return `UploadResultKind?` using a local `progressed` flag — **note the two non-obvious cases**: empty first `fetch()` (nothing pending) → `null`; drained-to-empty after marking → `Ok`; a non-`Success` result → `uploadResultKind(result)` (`Offline`/`Error`); a `Success` with **no forward progress** (`accepted` empty/foreign) → hand-mapped `Error` (do **NOT** route through `uploadResultKind(Success)`, which returns `Ok`). Reference shape:
  ```
  var progressed = false
  while (true) {
    val batch = fetch(); if (batch.isEmpty()) return if (progressed) Ok else null
    val result = upload(batch.map { it.toDto() })
    if (result !is Success) return uploadResultKind(result)   // Offline / Error
    val toMark = result.data.accepted.filter { it in batchIds }
    if (toMark.isEmpty()) return Error                         // no progress
    mark(toMark); progressed = true
  }
  ```
- [x] in `flushScope`, capture each `uploadLoop` return and call `onUploadOutcome(TrackScope(raceId, teamId), UploadTarget.Local/Cloud, kind)` only when non-null
- [x] extend the test `repo(...)` helper (`TrackRepositoryTest.kt:41`) with a capturing `onOutcome: (TrackScope, UploadTarget, UploadResultKind) -> Unit = { _,_,_ -> }` param threaded into the `TrackRepository(...)` ctor so tests can record reported outcomes
- [x] write test: Offline uploader → outcome reported `Offline` for both targets (with pending points present)
- [x] write test: Success drain → outcome `Ok`; non-success/`Forbidden` → `Error`; no-forward-progress (`accepted` empty/foreign) → `Error`
- [x] write test: target with **no pending** points → `onUploadOutcome` NOT called for that target (null path)
- [x] run tests — must pass before next task

### Task 4: Own the outcome `StateFlow` in `AppContainer` + expose counts

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt` (add `uploadCounts` passthrough)

- [x] add `val trackUploadOutcomes = MutableStateFlow<Map<Pair<TrackScope, UploadTarget>, TargetUploadOutcome>>(emptyMap())`
- [x] wire `trackRepository` lazy to pass `onUploadOutcome = { scope, target, kind -> trackUploadOutcomes.update { it + ((scope to target) to TargetUploadOutcome(kind, System.currentTimeMillis())) } }`
- [x] add a second injected seam `onScopeCleared: (TrackScope) -> Unit = {}` on `TrackRepository`, called **only from `deleteForTeam`** (the destructive «Очистить трек» path) — NOT from `insertAll`; wire it in `AppContainer` to **remove both target entries** for that scope: `trackUploadOutcomes.update { it - (scope to UploadTarget.Local) - (scope to UploadTarget.Cloud) }`. Rationale (resolved design Q): new points must **keep** the last outcome — «90 / 100 · 5 мин назад · ok» reads honestly as "last attempt 5 min ago succeeded, 10 points arrived since". Only a destructive clear actually resets the scope, so only `deleteForTeam` clears the outcome. (Also avoids the live-upload-throttle conflict: intermediate batch inserts between throttled flushes won't hide the last real result.)
- [x] extend `TrackRepositoryTest`: `deleteForTeam` clears that scope's outcome entries (assert the cleared callback fires). **Do not** add an "insert clears outcome" test — inserts intentionally preserve the outcome
- [x] add `TrackRepository.uploadCounts(teamId, raceId): Flow<UploadCounts> = trackDao.uploadCounts(teamId, raceId)` (mirrors `observeTrack`/`countForTeam`)
- [x] build to confirm wiring compiles: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`

### Task 5: Derive `TrackUploadStatus` in `MainActivity` and pass to `TrackCard`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt` (declare the view-models)
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (derive)
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt` (intermediate layer — host calls `TeamScreen`, which calls `TrackCard`)

- [x] **(finding 2 — import direction)** declare `data class TargetLine(...)` + `data class TrackUploadStatus(...)` with `fullyUploaded` in `ui/track/TrackCard.kt` (next to the composable), **not** in `MainActivity.kt` — these are UI composition models; the host should depend on the UI package, never the reverse
- [x] **(finding 3 — stale flash on scope switch)** derive the scoped counts via `produceState<UploadCounts?>(null, scope) { value = null; trackRepo.uploadCounts(scope.teamId, scope.raceId).collect { value = it } }` so the value resets to `null` the instant the scope key changes (a plain keyed-`remember` + `collectAsState` can briefly emit the previous scope's counts because `UploadCounts` carries no team/race to filter against)
- [x] collect `container.trackUploadOutcomes` via `collectAsState`; combine with the scoped counts into `TrackUploadStatus?` for the current scope (null when no scope / counts still `null` / `total == 0`)
- [x] **(high finding — TeamScreen is the intermediate layer)** add `trackUploadStatus: TrackUploadStatus? = null` param to `TeamScreen` (`TeamScreen.kt:89`) and forward it into its `TrackCard(...)` call (`TeamScreen.kt:149`); `MainActivity` passes the derived status into `TeamScreen`, **not** directly into `TrackCard`
- [x] build: `./gradlew :app:compileDebugKotlin` (host untested per convention)

### Task 6: Render the collapsible status row in `TrackCard`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/PointsPlural.kt` (top-level `relativeTimeRu`)
- Modify (tests): `app/src/test/java/ru/kolco24/kolco24/data/track/PointsPluralTest.kt` (or matching test file)

- [x] add `uploadStatus: TrackUploadStatus? = null` param; render nothing when null
- [x] add a **top-level pure** formatter `relativeTimeRu(atWallMs: Long, nowMs: Long): String` in `PointsPlural.kt` («только что» for `<60s` / «N мин назад» / «N ч назад»; no `java.time`) — matches the `pointsWord`/`formatSkewMinutes` pure-helper convention; **must be top-level + unit-tested**, not a private fun in the Compose file
- [x] write mandatory boundary tests for `relativeTimeRu` (`<60s`→«только что», `120s`→«2 мин назад», `2h`→«2 ч назад», minute/hour rollover)
- [x] **(finding 5 — placement covers recording too)** render `UploadStatusRow` in a **common footer after** the `if (recording) { … } else { … }` block (the existing `TrackMetrics` only renders in the idle branch — `TrackCard.kt:111`), so the status row is visible during recording as well, when uploads are actively running
- [x] add a private `UploadStatusRow` composable, with `rememberSaveable expanded` boolean
- [x] **(finding 4 — self-updating time)** drive the displayed "now" from a ticker so «N мин назад» advances on its own: a `produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(30_000) } }` (or `LaunchedEffect`) inside `UploadStatusRow`, used as `nowMs` for `relativeTimeRu` — only matters while the row is composed/visible
- [x] collapsed + `fullyUploaded` → `tertiary` check + «Загружено» (tappable to expand)
- [x] **(medium finding — collapsed shows last-attempt, the core UX requirement)** collapsed + pending → the **worst pending target** summarized inline (not just «осталось N»): pick the target that is behind (`uploaded < total`), preferring an `Error`/`Offline` outcome, and render «{Сервер}: {uploaded}/{total} · {relative time} · {ok/нет сети/ошибка}» (e.g. «Интернет: 90/100 · 5 мин назад · ok» or «Интернет: нет сети · 2 мин назад») + chevron. When that target has **no** outcome yet (cleared/never attempted) fall back to «Загрузка · осталось N». Colour the glyph by outcome (`error`/`onSurfaceVariant`)
- [x] expanded → two per-server lines (Интернет/Локальный): done = green check; pending = `uploaded / total` (monospace) + relative time + outcome label (ok / нет сети / ошибка)
- [x] keep muted weight (diagnostics) — must not visually compete with the orange «Начать запись»
- [x] run `relativeTimeRu` tests (added above) — must pass
- [x] build + manual visual sanity (`UploadStatusRow` itself is Compose-untested per convention)

### Task 7: Verify acceptance criteria
- [x] counts reflect real `uploadedLocal`/`uploadedCloud` flags (reactive — change after a flush updates the row) — `TrackDao.uploadCounts` CASE WHEN over the flags, exposed as `Flow`, collected via `produceState`
- [x] fully-uploaded scope shows calm «Загружено»; pending scope shows time + outcome per target — `CollapsedDone`/`CollapsedPending`/`ServerLine` in `TrackCard`
- [x] both servers labelled correctly (Интернет = cloud, Локальный = LAN); per-target counts independent — `status.cloud`→«Интернет», `status.local`→«Локальный»
- [x] `null`/`total==0` hides the row entirely — `MainActivity` derivation guard + `TrackCard` render guard (`uploadStatus != null && uploadStatus.total > 0`)
- [x] run full unit suite: `./gradlew testDebugUnitTest` — BUILD SUCCESSFUL
- [x] run lint: `./gradlew lintDebug` — BUILD SUCCESSFUL

### Task 8: [Final] Update documentation
- [x] update `CLAUDE.md`: `TrackDao` (`uploadCounts`), `TrackRepository` (`onUploadOutcome`, `uploadLoop` returns `UploadResultKind?`), `AppContainer` (`trackUploadOutcomes`), `TrackCard` (status row), and the GPS-track section (upload-status feedback)
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual / external — no checkboxes.*

**Manual verification** (on device, against the two real servers):
- Record a short track, then with the LAN server reachable but internet off → Локальный «Загружено», Интернет pending «нет сети» + time.
- Reverse (internet on, LAN unreachable) → Интернет «Загружено», Локальный «ошибка»/«нет сети».
- Both reachable and drained → collapsed «Загружено»; expand confirms both `total / total ✓`.
- Cold-restart with pending points → counts still correct; outcome line blank until the first Launch B flush (expected, by design), then populates.
