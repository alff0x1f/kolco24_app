# Instant Team List (prefetch + non-blocking picker)

## Overview
Opening the team list after picking a competition currently feels laggy (2–3s). This plan removes
that perceived lag by **warming Room ahead of time** and making the team screen **never block**.

- **Problem:** `TeamPickerScreen`'s `LaunchedEffect` sets `load = Loading`; when `teams.isEmpty()`
  for a first-time-picked race it renders a blocking centered `CircularProgressIndicator` until the
  network round-trip finishes (~2–3s).
- **Insight:** once a race's teams are cached, the screen *already* shows instantly and refreshes in
  the background. So the fix is to (a) warm the cache before the user navigates and (b) show
  structure + a thin progress bar instead of a blocking spinner when the cache is cold.
- **Integration:** no new architecture — composes the existing `RaceRepository` /
  `TeamRepository` / `LegendRepository` and the existing overlay-flow wiring in `MainActivity`.

## Context (from discovery)
- **Files/components involved:**
  - `ui/teampicker/TeamPickerLogic.kt` — pure, fully unit-tested date/list logic (`splitRaces`,
    `raceStatusPill`, private `RaceEntity.effectiveEnd()`).
  - `MainActivity.kt` — `Kolco24AppRoot()`; holds the overlay flow (`teamFlowStep`, `pickerRaceId`),
    exposes `raceRepo`/`teamRepo`/`legendRepo` from `container` (lines 87–89), private `todayIso()`
    (line 287), `onRaceSelected` hook (lines 244–246), `onRefresh = teamRepo::refreshTeams` (260).
  - `Kolco24App.kt` — `onCreate` fires two fire-and-forget launches (races refresh; selectedTeam →
    legend refresh) using `container.applicationScope`.
  - `ui/teampicker/TeamPickerScreen.kt` — `PickerLoad` enum + `LaunchedEffect` refresh; the
    blocking-spinner branch is at ~lines 173–179.
  - `data/RaceRepository.kt` / `TeamRepository.kt` / `LegendRepository.kt` — Room-as-SoT, ETag-guarded
    `refresh*` returning `RefreshResult`.
- **Related patterns found:**
  - `container.applicationScope` (`SupervisorJob + Dispatchers.IO`) is the established home for
    fire-and-forget work that must outlive composition (already used when confirming a team).
  - All date logic is lexicographic ISO-string compare — **no `java.time`** (minSdk 24, no desugaring).
  - `refresh*` are idempotent (`replaceAllForRace`) and ETag/304-guarded.
- **Dependencies identified:** none new. `kotlinx.coroutines.flow.first` for reading Room once in
  `Kolco24App`.

## Development Approach
- **Testing approach:** Regular (code first, then tests) — matches the existing repo, where the only
  meaningful new logic is the pure `nearestRaceId` function and everything else is thin glue.
- Complete each task fully before moving to the next; small, focused changes.
- **Every task with new/changed logic includes tests.** Here the testable surface is `nearestRaceId`;
  the App/MainActivity changes are thin fire-and-forget glue that the project deliberately leaves
  untested (consistent with how `Kolco24App` launches and DAO wiring are treated), and the underlying
  `refreshTeams`/`refreshLegend` are already covered by `TeamRepositoryTest`/`LegendRepositoryTest`.
- **All tests must pass before starting the next task.**
- Run tests after each change; maintain backward compatibility (no Room schema change).

## Testing Strategy
- **Unit tests:** add `nearestRaceId` cases to `TeamPickerLogicTest`.
- **e2e tests:** project has none (single-activity Compose app, no Playwright/Cypress). UI behavior
  (thin progress bar, instant card) is verified manually — see Post-Completion.
- **Instrumented tests:** none needed — no Room schema change, so the `MigrationTest` guard is untouched.
- **Gates (CLAUDE.md):** `./gradlew lintDebug` and `./gradlew testDebugUnitTest` must pass.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Keep this plan in sync if scope changes.

## Solution Overview
Three coordinated changes, no new abstractions:
1. **`nearestRaceId(races, today)`** — a pure selector for "soonest-starting current race" (an ongoing
   race wins). Lives in `TeamPickerLogic.kt` next to the other tested date logic.
2. **Prefetch** — warm teams+legend for the nearest race at startup (always, ETag keeps it cheap) and
   the selected race's teams too; plus an on-tap prefetch the instant a race row is picked, so the
   network starts during the screen transition.
3. **Non-blocking cold UI** — the team screen opens with the `CompContextCard` visible and a slim
   `LinearProgressIndicator` under the top bar while loading; rows pop in when Room emits. Existing
   empty/offline/forbidden/retry/stale-cache branches are unchanged and take over when loading
   finishes with no data.

Key decision (settled in brainstorm): the on-tap prefetch and the screen's own `onRefresh` may both
fire `refreshTeams(raceId)` for a cold race. We **accept the rare duplicate GET** (idempotent,
harmless) rather than add single-flight/dedupe complexity. The screen's `onRefresh` stays the
**authoritative driver of UI state** (`RefreshResult` → progress/snackbar/retry).

## Technical Details
- **`nearestRaceId`:**
  ```kotlin
  fun nearestRaceId(races: List<RaceEntity>, today: String): Int? =
      races.filter { it.effectiveEnd() >= today }.minByOrNull { it.date }?.id
  ```
  Reuses the existing private `RaceEntity.effectiveEnd()`. Returns `null` when no race is current
  (offline/empty table → prefetch no-ops).
- **`todayIso()`** moves from `MainActivity` (private) into a shared top-level helper
  `data/DateUtils.kt`, keeping the `SimpleDateFormat("yyyy-MM-dd", Locale.US)` impl. `MainActivity`
  and `Kolco24App` both call it.
- **Prefetch trigger points:** `Kolco24App.onCreate` (startup, nearest + selected) and
  `MainActivity.onRaceSelected` (on tap), both via `container.applicationScope` so they outlive any
  closing overlay.
- **Cold UI:** replace the blocking-spinner `when` branch so the `LazyColumn` renders unconditionally;
  add a `LinearProgressIndicator(Modifier.fillMaxWidth())` directly under the `TopAppBar` gated on
  `load == PickerLoad.Loading` (shows on every refresh — doubly signals background work).

## What Goes Where
- **Implementation Steps** (checkboxes): all code + unit tests in this codebase.
- **Post-Completion** (no checkboxes): manual UX verification of the perceived-lag fix (timing/visual).

## Implementation Steps

### Task 1: Add `nearestRaceId` pure selector

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerLogic.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerLogicTest.kt`

- [x] add `fun nearestRaceId(races: List<RaceEntity>, today: String): Int?` reusing the existing
      private `RaceEntity.effectiveEnd()`; filter `effectiveEnd() >= today`, `minByOrNull { it.date }`,
      map to `id` (KDoc: "soonest-starting current race; ongoing race wins; null when none current").
- [x] write test: ongoing race wins (`date <= today <= dateEnd`) over a later upcoming race.
- [x] write test: two overlapping ongoing races → earliest **start** date is chosen (pins the
      start-date tie-break, since the filter is `effectiveEnd`-based but selection is `date`-based).
- [x] write test: among multiple future races, the soonest start is chosen.
- [x] write test: all-archived list → `null`; empty list → `null`.
- [x] write test: same-start-date sanity (deterministic pick, no crash).
- [x] run `./gradlew testDebugUnitTest` — must pass before Task 2.

### Task 2: Extract shared `todayIso()` helper

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/DateUtils.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] create `data/DateUtils.kt` in `package ru.kolco24.kolco24.data` with top-level
      `fun todayIso(): String` using `SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())`
      (move impl verbatim).
- [ ] remove the private `todayIso()` from `MainActivity.kt`; add
      `import ru.kolco24.kolco24.data.todayIso`; drop now-unused `SimpleDateFormat`/`Date`/`Locale`
      imports if no longer referenced there. (`Kolco24App` is in `ru.kolco24.kolco24` and imports the
      same.)
- [ ] build check: `./gradlew assembleDebug` compiles (no behavior change; pure refactor).
- [ ] (no new unit test — pure move of a trivial date formatter; covered indirectly by build.)

### Task 3: Startup prefetch in `Kolco24App`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`

- [ ] in Launch A: after `refreshRaces()`, read `raceRepository.races.first()`
      (`kotlinx.coroutines.flow.first`), compute `nearestRaceId(races, todayIso())`; if non-null,
      `launch { teamRepository.refreshTeams(nearest) }` and
      `launch { legendRepository.refreshLegend(nearest) }` as concurrent child launches; `Log.i` results.
- [ ] in Launch B (existing `selectedTeam.collectLatest` legend refresh): add a child
      `launch { teamRepository.refreshTeams(raceId) }` alongside the legend refresh so the selected
      race's roster also refreshes on cold start.
- [ ] confirm offline/empty path: `nearest == null` → `return@launch` (no-op), no crash.
- [ ] build check: `./gradlew assembleDebug` compiles.
- [ ] (no new unit test — fire-and-forget App glue, per project convention; `refreshTeams`/
      `refreshLegend` already covered by `TeamRepositoryTest`/`LegendRepositoryTest`.)

### Task 4: On-tap prefetch in `MainActivity.onRaceSelected`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] in `onRaceSelected = { raceId -> ... }` (around line 244), before setting `pickerRaceId` /
      `teamFlowStep`, fire `container.applicationScope.launch { teamRepo.refreshTeams(raceId) }` and
      `container.applicationScope.launch { legendRepo.refreshLegend(raceId) }`.
- [ ] use `container.applicationScope` (NOT the composition `scope`) so the work outlives the closing
      comp picker; keep `onRefresh = teamRepo::refreshTeams` on `TeamPickerScreen` unchanged.
- [ ] build check: `./gradlew assembleDebug` compiles.
- [ ] (no new unit test — composition glue; duplicate GET with the screen's own refresh is accepted
      and harmless per design.)

### Task 5: Non-blocking cold state in `TeamPickerScreen`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/teampicker/TeamPickerScreen.kt`

- [ ] remove the `teams.isEmpty() && load == PickerLoad.Loading -> Box { CircularProgressIndicator }`
      branch (~lines 173–179) so the normal `LazyColumn` (with `CompContextCard`) renders immediately
      in the cold-loading case.
- [ ] **gate the "Пока никто не зарегистрирован" empty text** (the `if (teams.isEmpty())` item branch
      ~lines 191–199) on `load != PickerLoad.Loading`, so a cold open (empty `teams` + `Loading`) shows
      **only** the comp card + progress bar — never a false "nobody registered" message. When
      `teams.isEmpty() && load == Loading`, render nothing in that slot (rows pop in when Room emits).
- [ ] add a slim `LinearProgressIndicator(Modifier.fillMaxWidth())` shown when
      `load == PickerLoad.Loading`, placed by wrapping the `Scaffold` `topBar` `TopAppBar` in a
      `Column` so the bar sits flush under the app bar and does **not** scroll away inside the
      `LazyColumn`.
- [ ] verify the empty/`Offline`/`HttpError`/`Forbidden`/retry placeholder branches and the
      `staleCache`/`forbidden` snackbar `LaunchedEffect`s are unchanged and still trigger when loading
      finishes with no data.
- [ ] remove the now-unused `CircularProgressIndicator` import if nothing else uses it.
- [ ] build check: `./gradlew assembleDebug` compiles.

### Task 6: Verify acceptance criteria
- [ ] `nearestRaceId` picks soonest-starting current race; ongoing wins; null when none current.
- [ ] startup warms nearest race + selected race; on-tap warms the tapped race.
- [ ] team screen opens with `CompContextCard` + thin progress bar (no centered spinner) when cold.
- [ ] run full suite: `./gradlew testDebugUnitTest`.
- [ ] run lint: `./gradlew lintDebug`.

### Task 7: [Final] Documentation
- [ ] update `CLAUDE.md` — note the startup nearest-race prefetch (`Kolco24App`), the on-tap prefetch
      in `MainActivity.onRaceSelected`, the shared `data/DateUtils.todayIso()`, the new
      `nearestRaceId` helper, and the team picker's non-blocking thin-progress cold state.
- [ ] move this plan to `docs/plans/completed/` (create dir if needed).

## Post-Completion
*Manual / informational — no checkboxes.*

**Manual verification** (the perceived-lag fix is timing/visual):
- Cold start online → open the nearest race's teams: list appears instantly, thin bar flickers.
- Tap a non-nearest current race: window opens instantly with the comp card + thin progress bar,
  rows pop in (no full-screen spinner).
- Airplane mode, never-visited race: comp card shows, bar runs, then the existing offline
  placeholder/retry takes over.
- Re-open a previously visited race: instant from cache, brief background refresh.
- Cold open of a race with **no** registered teams: during loading shows only the comp card + bar
  (no false "Пока никто не зарегистрирован"); the empty message appears only after load finishes.
