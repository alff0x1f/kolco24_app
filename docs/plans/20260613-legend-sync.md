# Legend sync — show the selected team's race legend

## Overview
- Replace the mocked `MOCK_CHECKPOINTS` in the «Легенда» tab with the real checkpoint
  list of the **selected team's race**, fetched from `GET /app/race/<race_id>/legend/`.
- Mirrors the existing races/teams sync almost 1:1: signed conditional `GET` (ETag/304),
  Room as the single source of truth, repository-driven refresh.
- The selected team already carries `raceId` (`SelectedTeamEntity.raceId`), so "legend of
  the selected team" resolves to the legend of that race id. `RaceEntity.isLegendVisible`
  (already persisted) disambiguates the "locked / before start" UI state.

## Context (from discovery)
- **Files/components involved:**
  - `data/api/ApiClient.kt` — `conditionalGet` plumbing; add `fetchLegend`.
  - `data/api/dto/RacesResponse.kt` (reference for DTO style) → new `LegendResponse.kt`.
  - `data/db/AppDatabase.kt` — Room v2; bump to v3, add entity + DAO + `MIGRATION_2_3`.
  - `data/db/TeamDao.kt`, `data/db/RaceEntity.kt` (reference) → new `CheckpointEntity.kt`,
    `CheckpointDao.kt`.
  - `data/TeamRepository.kt` (reference) → new `LegendRepository.kt`.
  - `AppContainer.kt` — construct/expose `LegendRepository`.
  - `Kolco24App.kt` — fire the reactive refresh in `applicationScope`.
  - `ui/legend/LegendScreen.kt` — make stateless; add locked / no-team states.
  - `MainActivity.kt` (~85–260) — hoist legend state, wire `onChooseTeam`.
  - `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/` — commit `3.json`.
  - `app/src/androidTest/.../MigrationTest.kt` — add 2→3 case.
- **Related patterns found:**
  - `TeamRepository.refreshTeams` — `replaceAllForRace` **then** ETag upsert as two
    separate transactions (self-heal on crash-between); ETag upsert skipped when null.
  - `TeamDao.replaceAllForRace` — `@Transaction` delete-by-race then insert.
  - `sync_meta` ETag partitioned by `(origin, resource)`; resource string per endpoint.
  - `MIGRATION_1_2` — raw `CREATE TABLE`/`CREATE INDEX` whose SQL must match the generated
    schema JSON **exactly** (camelCase columns); KSP does not verify it — only the
    instrumented `MigrationTest` does.
- **Dependencies identified:** Room (KSP), kotlinx.serialization, OkHttp, existing
  `FetchResult`/`RefreshResult` sealed types — all reused, nothing new added.

## Development Approach
- **Testing approach:** Regular (code first, then tests), matching the repo convention.
- Pure/unit-testable logic gets unit tests; trivial DAO/Room wiring is not unit-tested
  (per existing repo convention — see CLAUDE.md). The Room **migration is guarded by an
  instrumented test** (`connectedDebugAndroidTest`), the same as `MIGRATION_1_2`.
- Complete each task fully before the next; keep changes small and focused.
- **CRITICAL: every task that adds verifiable logic MUST add/extend tests in the same task.**
- **CRITICAL: all relevant tests pass before starting the next task.**
- **CRITICAL: keep this plan in sync if scope changes during implementation.**
- Maintain backward compatibility: the v2→v3 migration only adds a table (existing tables
  untouched, so races/teams/selected-team survive the upgrade).

## Testing Strategy
- **Unit tests** (`testDebugUnitTest`): a `LegendResponse` JSON round-trip / DTO→entity
  mapping test if non-trivial mapping logic lands in `LegendRepository`; otherwise none for
  pure wiring (repo convention).
- **Instrumented test** (`connectedDebugAndroidTest`): extend `MigrationTest` with a 2→3
  case — existing rows survive, `checkpoints` table exists and is empty, the
  `index_checkpoints_raceId` index exists. `runMigrationsAndValidate` also validates the
  generated schema against `3.json`.
- **No e2e harness** in this project (single-activity Compose app, no Playwright/Cypress).
  Manual on-device verification of the three Legend states is listed under Post-Completion.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Update the plan if implementation deviates from scope.

## Solution Overview
- **Data flow:** `Kolco24App` (app scope) observes `teamRepository.selectedTeam` →
  on each non-null `raceId`, `legendRepo.refreshLegend(raceId)` does a conditional `GET`
  → on `200`, `CheckpointDao.replaceAllForRace` writes that race's rows, then the ETag is
  upserted into `sync_meta`. The UI never reads the network: `MainActivity` collects
  `legendRepo.checkpointsForRace(selectedRaceId)` from Room and renders it.
- **Three render states** (per `docs/design/README.md` screens 02 / 02b / 02c):
  - no team selected → **02c LegendNoTeam**;
  - team selected but `race.isLegendVisible == false` → **02b LegendLocked**;
  - otherwise → **02 Legend** list (current UI, real data).
- **Key decisions (from brainstorm):**
  - **Taken state** is not in the legend API (it comes from NFC marks, not built yet).
    `CheckpointEntity.taken` defaults to `false`; the score card + «Все/Не взятые» filter
    stay but read `0 / total`. No fake data, no local stub.
  - **Separate `LegendRepository`** (not folded into `TeamRepository`) — single
    responsibility, matches the file-per-resource feel.
  - **Locked-state count dropped:** the API returns `checkpoints: []` while hidden, so the
    design's "`N КП на дистанции`" pill has no real number — omit it rather than fake it.
  - **`type` ignored** for now (no per-type styling in the design).

## Technical Details
- **Endpoint:** `GET /app/race/<race_id>/legend/` →
  `{"race": Int, "checkpoints": [{"id": Int, "number": Int, "cost": Int, "type": String, "description": String}]}`.
  `type` ∈ `start|finish|test|kp`. ETag/304 supported. Hidden legend → `200` + `[]`.
- **`sync_meta` resource string:** `"race/$raceId/legend"` (parallels `"race/$raceId/teams"`).
- **`CheckpointEntity`:** table `checkpoints`, `@PrimaryKey id` (server CP id, stable),
  `@Index` on `raceId`, columns `number`, `cost`, `type`, `description`, `taken` (default
  `false`). Doubles as the model (no separate domain layer).
- **DAO ordering:** `ORDER BY number, id` (matches the server's documented sort).
- **Migration SQL** (must match generated `3.json` exactly, camelCase columns):
  - `CREATE TABLE IF NOT EXISTS \`checkpoints\` (\`id\` INTEGER NOT NULL, \`raceId\` INTEGER NOT NULL, \`number\` INTEGER NOT NULL, \`cost\` INTEGER NOT NULL, \`type\` TEXT NOT NULL, \`description\` TEXT NOT NULL, \`taken\` INTEGER NOT NULL, PRIMARY KEY(\`id\`))`
  - `CREATE INDEX IF NOT EXISTS \`index_checkpoints_raceId\` ON \`checkpoints\` (\`raceId\`)`
  - (Confirm the exact column order/affinity against the generated `3.json` after the
    entity is added — let KSP generate it, then copy verbatim.)

## What Goes Where
- **Implementation Steps** (`[ ]`): DTO, entity, DAO, migration, schema JSON, API method,
  repository, DI wiring, reactive refresh, UI states, migration test.
- **Post-Completion** (no checkboxes): on-device manual verification of the three states and
  a real-server legend fetch.

## Implementation Steps

### Task 1: Add the legend DTO

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt`

- [x] create `LegendResponse(race: Int, checkpoints: List<CheckpointDto>)` `@Serializable`.
- [x] create `CheckpointDto(id, number, cost, type, description)` `@Serializable`; all
      `@SerialName` are already snake-case-free except none needed (fields are flat) — match
      the `RacesResponse.kt` style, rely on `Json { ignoreUnknownKeys = true }`.
- [x] no test for the DTO alone (covered by the repository mapping test in Task 6).

### Task 2: Add the CheckpointEntity

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointEntity.kt`

- [x] create `@Entity(tableName = "checkpoints", indices = [Index("raceId")])` with
      `@PrimaryKey id`, `raceId`, `number`, `cost`, `type`, `description`,
      `taken: Boolean = false`.
- [x] mirror the doc-comment style of `RaceEntity`/`TeamEntity` (entity doubles as model).
- [x] no standalone test (plain data class; validated via migration + mapping tests).

### Task 3: Add the CheckpointDao

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointDao.kt`

- [x] `observeCheckpointsForRace(raceId): Flow<List<CheckpointEntity>>`,
      `@Query("SELECT * FROM checkpoints WHERE raceId = :raceId ORDER BY number, id")`.
- [x] `@Insert(REPLACE) insertCheckpoints(...)` + `@Query` `deleteCheckpointsForRace`.
- [x] `@Transaction replaceAllForRace(raceId, checkpoints)` = delete-by-race then insert
      (mirror `TeamDao.replaceAllForRace`).
- [x] no unit test (trivial Room wiring, per repo convention).

### Task 4: Bump Room to v3 + MIGRATION_2_3 + schema JSON

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create (generated, commit): `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/3.json`

- [x] add `CheckpointEntity::class` to `entities`, bump `version = 3`, add
      `abstract fun checkpointDao(): CheckpointDao`.
- [x] add `MIGRATION_2_3` (one `CREATE TABLE checkpoints` + one
      `CREATE INDEX index_checkpoints_raceId`) and register it in `.addMigrations(...)`.
- [x] run `./gradlew assembleDebug` (or KSP) to generate `3.json`; **copy the generated
      table SQL verbatim** into `MIGRATION_2_3` so it matches exactly (camelCase columns).
- [x] commit `3.json`.
- [x] migration test added in Task 10 (kept separate so this task stays focused).

### Task 5: Add ApiClient.fetchLegend

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`

- [x] add `suspend fun fetchLegend(raceId: Int, etag: String?): FetchResult<LegendResponse>`
      delegating to `conditionalGet("$baseUrl/app/race/$raceId/legend/", etag) { json.decodeFromString<LegendResponse>(it) }`.
- [x] match the KDoc style of `fetchTeams`.
- [x] no unit test (no network in unit scope; conditionalGet already exercised by existing
      tests if any — keep parity with `fetchTeams`, which has none).

### Task 6: Add LegendRepository

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`
- Create (only if mapping warrants): `app/src/test/java/ru/kolco24/kolco24/data/LegendMappingTest.kt`

- [ ] `LegendRepository(apiClient, checkpointDao, syncMetaDao, origin)` with
      `legendResource(raceId) = "race/$raceId/legend"`.
- [ ] `checkpointsForRace(raceId): Flow<List<CheckpointEntity>>` delegating to the DAO.
- [ ] `refreshLegend(raceId): RefreshResult` mirroring `refreshTeams`: get stored ETag,
      `fetchLegend`, on `Success` → `replaceAllForRace(raceId, checkpoints.map { toEntity })`
      **then** `if (etag != null) syncMetaDao.upsert(...)`; map `NotModified`/`Forbidden`/
      `Error` to the same `RefreshResult` values.
- [ ] private `CheckpointDto.toEntity(raceId)` mapping (`taken = false`).
- [ ] write a unit test for the DTO→entity mapping **only if** the mapping is non-trivial
      (e.g. defaulting/derivation); if it's a flat 1:1 copy, skip per repo convention and
      note that here.

### Task 7: Wire LegendRepository into AppContainer

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] add `val legendRepository: LegendRepository by lazy { LegendRepository(apiClient, database.checkpointDao(), database.syncMetaDao(), origin = baseUrl) }`.
- [ ] no test (DI wiring).

### Task 8: Fire the reactive refresh in Kolco24App

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`

- [ ] in `onCreate`, in `applicationScope`, `collectLatest` on
      `container.teamRepository.selectedTeam`; on each non-null `raceId` call
      `container.legendRepository.refreshLegend(raceId)` and log the `RefreshResult`
      (`collectLatest` so a team switch cancels the in-flight fetch).
- [ ] keep the existing startup `refreshRaces()` launch as-is (separate `launch`).
- [ ] **cold start:** `selectedTeam` emits its persisted value immediately on subscribe, so a
      team chosen in a previous session triggers exactly one `refreshLegend` on launch — no
      separate "refresh on first selection" wiring needed.
- [ ] no test (fire-and-forget side effect; the repository logic is what's tested).

### Task 9: Make LegendScreen stateless + add the three states

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] change `LegendScreen` signature to
      `LegendScreen(checkpoints: List<CheckpointEntity>, legendVisible: Boolean, hasTeam: Boolean, onChooseTeam: () -> Unit, modifier: Modifier)`.
- [ ] delete `MOCK_CHECKPOINTS` and the local `Checkpoint` data class; use `CheckpointEntity`
      throughout (score/filter math reads `taken`/`cost` off the entity).
- [ ] **number formatting:** the mock's `number` was a zero-padded `String` ("01"); the entity's
      is `Int`. The row mono-label (README 02 line 125, format `<стоимость>-<номер>` e.g. `5-01`)
      must pad: `cp.number.toString().padStart(2, '0')` — otherwise `5-01` regresses to `5-1`.
- [ ] **retain the existing `taken`-based row styling** (dim + strike, README 02 line 128) as-is.
      It's simply never `true` this iteration; the future marks feature flips data, not UI — do
      not strip it as dead code.
- [ ] branch: `!hasTeam` → **LegendNoTeam** (map glyph, "Легенда пока недоступна", text per
      README 02c, "Выбрать команду" button → `onChooseTeam`); `hasTeam && !legendVisible` →
      **LegendLocked** (charcoal hero card, "ДО СТАРТА" badge, "Легенда откроется на старте";
      **no** "N КП" count); else → existing list UI fed from `checkpoints`.
- [ ] **02b scope (YAGNI):** implement LegendLocked as the hero card **only** — defer the
      README's skeleton `LockedLegendRow` placeholder list (no count and no real data to size
      them against; revisit if the locked state needs more substance later).
- [ ] in `MainActivity`, hoist: `selectedRace = races.find { it.id == selectedRaceId }`;
      `checkpoints` from `remember(selectedRaceId) { selectedRaceId?.let { legendRepo.checkpointsForRace(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())`;
      pass `legendVisible = selectedRace?.isLegendVisible == true`, `hasTeam = selectedRaceId != null`,
      `onChooseTeam = { pickerRaceId = null; teamFlowStep = TeamFlowStep.CompPicker }`
      (same callback the Команда/Отметки empty states use).
- [ ] add `val legendRepo = container.legendRepository` near `teamRepo`.
- [ ] no automated UI test (Compose UI not currently unit-tested in this repo); manual
      verification listed under Post-Completion.

### Task 10: Guard the migration with an instrumented test

**Files:**
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [ ] add `migrate2To3_keepsDataAndAddsCheckpointsTable`: create v2 (insert a race + a team
      row — **copy the exact v2 `teams` column set from `2.json`**; the existing 1→2 test only
      inserts into `races`, so a wrong column here fails test setup, not the migration),
      `runMigrationsAndValidate(dbName, 3, true, MIGRATION_1_2, MIGRATION_2_3)`, assert the
      race/team rows survive and `SELECT count(*) FROM checkpoints` is `0`.
- [ ] add `migrate2To3_indexExists`: assert `index_checkpoints_raceId` exists in
      `sqlite_master` (mirror `migrate1To2_indexExists`).
- [ ] run `./gradlew connectedDebugAndroidTest` (needs an emulator/device) — must pass.

### Task 11: Verify acceptance criteria
- [ ] no team selected → 02c shown; tapping "Выбрать команду" opens the team picker.
- [ ] team on a race with `isLegendVisible = false` → 02b locked card (no count).
- [ ] team on a race with a visible legend → real CP list, score `0 / total`,
      «Не взятые» count == total.
- [ ] switching teams to a different race swaps the legend (reactive refresh + Room flow).
- [ ] run `./gradlew lintDebug` — must pass (required before merge).
- [ ] run `./gradlew testDebugUnitTest` — must pass.
- [ ] run `./gradlew connectedDebugAndroidTest` — migration guard must pass.

### Task 12: [Final] Update documentation
- [ ] update `CLAUDE.md` Data-layer section: add the `checkpoints` table / Room v3 /
      `MIGRATION_2_3` / `LegendRepository` / `fetchLegend` notes, and the LegendScreen
      states, in the same voice as the existing teams-sync entry.
- [ ] move this plan to `docs/plans/completed/`.

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification:**
- On a device/emulator with a real (or local) backend, select a team whose race has a
  visible legend and confirm the CP list matches the server; toggle a race with
  `is_legend_visible: false` and confirm the locked state.
- Confirm ETag/304: a second app launch with unchanged data logs `NotModified` (no rewrite).
- Confirm offline behavior: with no network, the last-synced legend still renders from Room.

**Future work (out of scope here):**
- Wire `taken` to real NFC marks once the Отметки/scan feature lands (the score card and
  «Не взятые» filter become meaningful then).
- Per-`type` styling (start/finish/test/kp) if the design later calls for it.
- Optionally migrate the refresh loop to the `/app/race/<id>/sync/` manifest to batch
  teams+legend version checks into one request.
