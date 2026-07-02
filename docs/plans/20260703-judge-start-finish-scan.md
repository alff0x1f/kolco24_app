# Судейская отметка старта/финиша (judge start/finish scan)

## Overview

A new admin-panel feature: a judge at a start (or finish) checkpoint pikes participants' NFC
bracelets to record their start/finish times — an alternative source of timing for when
participants don't self-mark at the start/finish КП. Two separate admin sub-overlay pages
(«Старт» / «Финиш»), each fixed to its event type. The feature works fully offline (Room is the
source of truth) and uploads asynchronously every 60 seconds to **both** the cloud and LAN targets,
idempotently by client UUID. The server endpoint is **not yet implemented** — this plan documents
the contract it must satisfy.

Key benefits:
- Judge-driven timing fallback when self-marking fails or isn't used.
- Robust offline capture; the minute-tick drains to both targets independently.
- Reuses the shipped, battle-tested marks upload machinery (dual-target, `tryLock`, accepted-UUID).

## Context (from discovery)

Files/areas involved:
- **Data/db:** `data/db/AppDatabase.kt` (currently **v4**, migrations 1→4, `exportSchema=true`),
  `data/db/MarkEntity.kt` + `MarkDao.kt` (template for the new entity/DAO), committed schemas in
  `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/{1..4}.json`.
- **Repository:** `data/MarkRepository.kt` (dual-target upload loop, `Mutex.tryLock`, accepted-UUID
  marking) — the template for `JudgeScanRepository`.
- **API:** `data/api/ApiClient.kt` (`uploadMarks` shape, `post(...)` helper), `data/api/MarkDtos.kt`
  (snake_case DTOs + pure `toDto()`), signing is transparent via `AppSignatureInterceptor`.
- **Member resolution:** `data/MemberTagsRepository.kt` + `data/db/MemberTagEntity.kt`
  (`MemberTagEntity(raceId, nfcUid, number)` — has `number`, **no name**), `findByUid` / `observeForRace`.
- **Admin UI:** `ui/admin/AdminScreen.kt` (AdminHome rows), `ui/admin/CheckMemberChipScreen.kt`
  (host recipe: null-sentinel pool guard, `DisposableEffect` NFC hook, `Mutex`-serialized), pure
  model `ui/admin/MemberChipCheckModel.kt`.
- **NFC dispatch:** `MainActivity.kt` — `@Volatile` tag hooks + `onTagDiscovered` priority dispatch;
  `data/NfcUid.kt` (`normalizeNfcUid`); `data/nfc/MifareUltralightWriter.kt` (`readChipCode`).
- **Clock:** `data/time/TrustedClock.kt` (`sample(): TimeSample{wallMs, trustedMs, elapsedMs, bootCount}`,
  `ClockStatus`), `ui/common/ClockWarningBanner.kt` (`ScanClockBanner(status)` / `ClockWarningBanner`).
- **App entry / DI:** `Kolco24App.kt` (Launch B startup flush), `AppContainer.kt` (manual DI).

Related patterns found:
- **Idempotent-by-UUID dual-target upload:** `MarkRepository` + `MarkDao.unuploadedLocal/Cloud`,
  `markUploadedLocal/Cloud`, `pendingUploadScopes()`.
- **Pure-model + stateful-host split** for NFC admin screens (`*Model.kt` JVM-tested, `*Screen.kt`
  untested by convention).
- **Overlay pattern:** `rememberSaveable` flags after `Scaffold`, dismissed via `BackHandler`.

Dependencies identified: kotlinx.serialization (DTOs), Room (KSP), existing `ApiClient` cloud + LAN
instances, `container.applicationScope`, `trustedClock`, `scanFeedback`, `installId`.

## Development Approach

- **Testing approach:** Regular (code first, then tests) — per project convention pure models,
  mappers, and the repository upload loop are JVM-unit-tested; DAO + migration are instrumented;
  Compose hosts / NFC adapters / trivial wiring are untested by convention.
- Complete each task fully (including its tests) before the next.
- Small, focused changes; keep the plan in sync if scope shifts.
- **Every code task includes new/updated tests** for the testable-by-convention surface it adds.
  Tasks that only add untested-by-convention surface (Compose host, NFC hook, DI wiring, docs) say so
  explicitly and carry a manual-verification note instead.
- **All tests pass before starting the next task.**
- Backward compatibility: the Room migration is additive; every existing `ApiClient` call site is
  untouched (new method added, not changed).

## Testing Strategy

- **JVM unit tests** (`app/src/test/...`): `JudgeScanModelTest` (all `classifyJudgeScan` branches),
  `JudgeScanDtoTest` (`JudgeScanEntity.toDto()` field mapping incl. snake_case names + null
  `trustedMs`), `JudgeScanRepositoryTest` (accepted-UUID marking, offline/error re-queue, dual-target
  independence, `tryLock` skip) — mirror the existing `MarkRepository`/`MarkDto` tests.
- **Instrumented tests** (`app/src/androidTest/...`): `JudgeScanDaoTest` (unuploaded queries scoped by
  `raceId`, `markUploaded*`, `pendingUploadRaces`) and a `MIGRATION_4_5` test (schema-diff / open-and-
  query on an upgraded DB). Add both to the `connectedDebugAndroidTest` guard list in CLAUDE.md.
- **No e2e framework** in this project — manual on-device verification covers the Compose host + NFC.

## Progress Tracking

- Mark completed items `[x]` immediately.
- New tasks: ➕ prefix. Blockers: ⚠️ prefix.
- Update this file if implementation deviates.

## Solution Overview

Option A (locked in brainstorm): a **dedicated** `judge_scans` table + `JudgeScanRepository`, **not**
reuse of the marks table. Rationale: a judge station scans across all teams, so the natural scope is
`raceId` **only** (no team dimension); reusing marks would force a fake team/checkpoint and pollute
marks semantics. Rows are **write-once** (nothing mutates a scan after insert), so the DAO needs no
`updatedAt` version guard — a plain `UPDATE ... WHERE id = :id` marks it uploaded.

Data flow per pik:
1. NFC tag hits `onTagForJudgeScan` (binder thread → main via `mainHandler.post`).
2. Host captures `trustedClock.sample()` **first** (tap-accurate time), then `normalizeNfcUid(tag.id)`.
3. Resolve UID against the collected `member_tags` pool. Only on the not-in-pool branch does it
   `readChipCode(tag)` (off main thread) to tell a mis-tapped КП chip from an unknown card.
4. `classifyJudgeScan(...)` → `Recorded` writes a row on `applicationScope`; everything else is an
   error cue and **no row** (reject unknown / КП / pool-not-ready).
5. Every 60 s a MainActivity ticker calls `judgeScanRepo.uploadAllPending()`, which drains each
   pending race to LAN then cloud independently, idempotent by the server's `accepted[]` UUID echo.

Key design decisions (locked — do not re-litigate):
1. Dedicated `judge_scans` table, scoped by `raceId` only, write-once rows.
2. Every pik = one row (no client dedup; server dedupes).
3. Reject unknown/КП chips (only pooled UIDs are recorded) ⇒ the page **requires a synced pool** and
   warns when it hasn't synced.
4. No per-scan upload flush — rely on the 60 s tick alone.
5. Big live trusted-time clock at the top of each page + reuse `ScanClockBanner`/`ClockWarningBanner`.

## Technical Details

**Entity** `JudgeScanEntity` (`judge_scans`):
`id: String` (UUID PK), `raceId: Int`, `eventType: String` (`"start"`|`"finish"`),
`participantNumber: Int`, `nfcUid: String`, `takenAt: Long`, `trustedTakenAt: Long?`,
`elapsedRealtimeAt: Long`, `bootCount: Int?`, `sourceInstallId: String`,
`uploadedLocal: Boolean = false`, `uploadedCloud: Boolean = false`.
`bootCount` is **nullable** — it maps `TimeSample.bootCount: Int?` (the boot count can fail to read),
matching `MarkEntity.bootCount`. `elapsedRealtimeAt: Long` is non-null (`TimeSample.elapsedMs` is non-null).
Indices: `Index("raceId")` **only** — mirror `MarkEntity`, which indexes scoping columns, not the
low-cardinality boolean upload flags (the `raceId` index already serves the scoped `unuploaded*`
queries; indexing 2-value columns just adds write cost). No unique constraint (duplicates expected).

**DAO** `JudgeScanDao`:
- `@Insert suspend fun insert(scan: JudgeScanEntity)`
- `@Query observeRecent(raceId, eventType, limit): Flow<List<JudgeScanEntity>>` (UI recent list —
  scoped by event type so «Старт» and «Финиш» pages don't cross-show)
- `unuploadedLocal(raceId, limit)` / `unuploadedCloud(raceId, limit)` —
  `... WHERE raceId = :raceId AND uploadedLocal = 0 ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT :limit`
  (note the explicit `= :raceId` — a bare `WHERE raceId` reads truthy)
- `markUploadedLocal(ids: List<String>)` / `markUploadedCloud(ids)` — `UPDATE ... WHERE id IN (:ids)`
  (no `updatedAt` guard; write-once)
- `pendingUploadRaces(): List<Int>` — `SELECT DISTINCT raceId FROM judge_scans WHERE uploadedLocal = 0 OR uploadedCloud = 0`

**Migration** `MIGRATION_4_5` (crash-risk step): `CREATE TABLE judge_scans (...)` (nullable
`bootCount INTEGER`, no `NOT NULL`; `trustedTakenAt INTEGER` nullable) + the single
`CREATE INDEX index_judge_scans_raceId ON judge_scans (raceId)` — the index name and every column
type/nullability must match exactly what Room generates for the entity, or `runMigrationsAndValidate`
(Task 8) fails against `5.json`. Append to
`.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`;
bump `@Database(version = 5)`; commit `app/schemas/.../5.json`.

**DTOs** (`data/api/JudgeScanDtos.kt`):
```
JudgeScanUploadRequest(@SerialName("source_install_id") sourceInstallId, scans: List<JudgeScanDto>)
JudgeScanDto(id, @SerialName("event_type") eventType, @SerialName("participant_number") number,
             @SerialName("nfc_uid") nfcUid, @SerialName("wall_ms") wallMs,
             @SerialName("trusted_ms") trustedMs: Long?, @SerialName("elapsed_at") elapsedAt,
             @SerialName("boot_count") bootCount: Int?)
JudgeScanUploadResponse(accepted: List<String>)
```
Pure `fun JudgeScanEntity.toDto(): JudgeScanDto`.

**API** (`ApiClient`): `suspend fun uploadJudgeScans(raceId, sourceInstallId, scans): PostResult<JudgeScanUploadResponse>`
→ `post("$baseUrl/app/race/$raceId/judge_scans/", bytes) { json.decodeFromString(it) }`. Body
serialized to bytes once (signing hashes exactly what's sent). Called on both `apiClient` (cloud) and
`localApiClient` (LAN) instances.

**Server contract (NOT yet implemented):** `POST /app/race/<raceId>/judge_scans/`, signed request body
`{source_install_id, scans:[{id, event_type, participant_number, nfc_uid, wall_ms, trusted_ms,
elapsed_at, boot_count}]}`, response `{accepted:[<uuid>]}`. Partial-accept semantics like `/marks/`
(never a whole-batch 400 for one bad row). Idempotent upsert by `id`. Server owns dedup of repeat piks.

**Pure model** `JudgeScanModel.kt`:
```
sealed interface JudgeScanResult {
  data class Recorded(uid, number) ; data class UnknownChip(uid) ; object KpChip ; object PoolNotReady
}
fun classifyJudgeScan(uid, memberTag: MemberTagEntity?, hasKpCode: Boolean, poolReady: Boolean): JudgeScanResult
```
Branch order: `!poolReady` → `PoolNotReady`; `memberTag != null` → `Recorded`; `hasKpCode` → `KpChip`;
else `UnknownChip`. Only `Recorded` writes a row.

## What Goes Where

- **Implementation Steps** (`[ ]`): entity, DAO, migration, schema json, repository, DTOs, API method,
  DI wiring, pure model, Compose host, NFC hook, admin menu, ticker, tests, docs.
- **Post-Completion** (no checkboxes): the server endpoint implementation, on-device manual
  verification (offline capture → 60 s drain to both targets, reject flows, clock banner).

## Implementation Steps

### Task 1: `JudgeScanEntity` + `MIGRATION_4_5`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/JudgeScanEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt` (add `MIGRATION_4_5` only; entities/version bump in Task 2)

- [x] create `JudgeScanEntity` (fields + single `raceId` index per Technical Details; nullable `bootCount`)
- [x] add `JudgeScanEntity::class` to `@Database(entities = [...])`, bump `version = 4` → `5` (deferred to Task 2 per plan note below)
- [x] add `val MIGRATION_4_5 = object : Migration(4, 5) { CREATE TABLE judge_scans (...) + raceId index }`
      (nullable `bootCount`/`trustedTakenAt`, index name `index_judge_scans_raceId`)
- [x] append `MIGRATION_4_5` to `.addMigrations(...)`
- [x] NOTE: the `@Database(entities/version)` change + `judgeScanDao()` accessor land in Task 2 (they
      need `JudgeScanDao`/`JudgeScanEntity` to compile); schema `5.json` is generated at the end of Task 2
- [x] (tests for the migration live in Task 8 — instrumented `MIGRATION_4_5` test)

### Task 2: `JudgeScanDao` + wire into `AppDatabase` + commit `5.json`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/JudgeScanDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/5.json` (generated by a build)

- [x] `insert`, `observeRecent(raceId, eventType, limit)`
- [x] `unuploadedLocal` / `unuploadedCloud` (explicit `= :raceId`, `ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT`)
- [x] `markUploadedLocal(ids)` / `markUploadedCloud(ids)` (`WHERE id IN (:ids)`, no updatedAt guard)
- [x] `pendingUploadRaces()`
- [x] add `JudgeScanEntity::class` to `@Database(entities = [...])`, bump `version = 4` → `5`, add
      `abstract fun judgeScanDao(): JudgeScanDao`
- [x] ⚠️ CRASH-RISK GATE: build with `exportSchema` to generate `app/schemas/.../5.json`; **commit it**.
      A version bump without the committed schema **and** the appended `MIGRATION_4_5` crashes on upgrade.
- [x] confirm project builds (Room codegen) with the DAO + entity wired in
- [x] (DAO behavior is instrumented in Task 8; no JVM test here)

### Task 3: DTOs + `toDto()` mapper

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/JudgeScanDtos.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/JudgeScanDtoTest.kt`

- [x] define `JudgeScanUploadRequest`, `JudgeScanDto`, `JudgeScanUploadResponse` (snake_case `@SerialName`)
- [x] pure `JudgeScanEntity.toDto()` mapping every field
- [x] write test: `toDto()` maps all fields incl. null `trustedTakenAt` → `trusted_ms: null`
- [x] write test: JSON serialization emits the snake_case keys the contract specifies
- [x] run tests — must pass before next task

### Task 4: `ApiClient.uploadJudgeScans`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`

- [x] add `suspend fun uploadJudgeScans(raceId, sourceInstallId, scans): PostResult<JudgeScanUploadResponse>`
      mirroring `uploadMarks` (serialize once → `post(".../judge_scans/", bytes) { decode }`)
- [x] verify it compiles against both cloud + LAN instance construction (no per-call URL — instance selects target)
- [x] (network method is a thin trust-boundary-adjacent wrapper; the repository test in Task 5 exercises
      the loop via the `JudgeScanUploader` seam — no direct ApiClient test, matching `uploadMarks`)

### Task 5: `JudgeScanRepository` (dual-target upload loop)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/JudgeScanRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/JudgeScanRepositoryTest.kt`

- [x] `fun interface JudgeScanUploader { suspend fun upload(raceId, sourceInstallId, scans): PostResult<JudgeScanUploadResponse> }`
      with constructor `cloudUploader`/`localUploader` defaulting to `{ _,_,_ -> PostResult.Offline }`
- [x] `suspend fun record(raceId, eventType, participantNumber, nfcUid, sample: TimeSample): String` —
      mint UUID, insert row (map `sample` → time columns, `installId` → `sourceInstallId`)
- [x] `uploadMutex: Mutex`; `uploadPending(raceId)` and `uploadAllPending()` both `if (!tryLock()) return` … `finally unlock`
- [x] `flushRace(raceId)`: LAN first then cloud, **independent**; each runs the batch `uploadLoop`
      (fetch LIMIT=500 → upload → on non-Success return → mark `accepted ∩ batch` → repeat; stop if none accepted)
- [x] `uploadAllPending()` walks `dao.pendingUploadRaces()` and flushes each
- [x] write test: accepted-UUID subset marks only those rows uploaded (batch minus rejected stays pending)
- [x] write test: `PostResult.Offline`/error leaves rows pending (re-queue next tick)
- [x] write test: dual-target independence (LAN error doesn't block cloud drain and vice versa)
- [x] write test: `tryLock` — a second concurrent flush is skipped, not double-POSTed
- [x] run tests — must pass before next task

### Task 6: DI wiring in `AppContainer` + startup flush

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`

- [ ] add lazy `judgeScanRepository` wiring `cloudUploader = apiClient::uploadJudgeScans`,
      `localUploader = localApiClient::uploadJudgeScans`, `dao = database.judgeScanDao()`, `installId`
- [ ] add a `judgeScanRepository.uploadAllPending()` call to the Launch B startup flush sequence
- [ ] (manual-verification only — DI/wiring untested by convention; note in Post-Completion)

### Task 7: Pure `JudgeScanModel`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/JudgeScanModel.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/admin/JudgeScanModelTest.kt`

- [ ] `sealed interface JudgeScanResult { Recorded(uid, number); UnknownChip(uid); KpChip; PoolNotReady }`
- [ ] `classifyJudgeScan(uid, memberTag, hasKpCode, poolReady)` with branch order
      PoolNotReady → Recorded → KpChip → UnknownChip
- [ ] write test: pool-not-ready short-circuits even when a memberTag would match
- [ ] write test: pooled UID → Recorded(number); not-in-pool + code → KpChip; not-in-pool + no code → UnknownChip
- [ ] run tests — must pass before next task

### Task 8: `JudgeScanDao` + `MIGRATION_4_5` instrumented tests

**Files:**
- Create: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/JudgeScanDaoTest.kt`
- Create/Modify: the migration test file (alongside existing MIGRATION_1_2/2_3/3_4 tests)

- [ ] DAO test: `unuploadedLocal/Cloud` scoped by `raceId`, ordered by trusted-then-wall time
- [ ] DAO test: `markUploadedLocal/Cloud(ids)` flips only the given rows; `pendingUploadRaces()` distinct set
- [ ] migration test: upgrade a v4 DB across `MIGRATION_4_5`, assert `judge_scans` opens + accepts a row
- [ ] run instrumented tests: `./gradlew connectedDebugAndroidTest` — must pass before next task

### Task 9: `JudgeScanScreen` host + NFC hook + big clock

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/JudgeScanScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] resolve `raceId` for a **cross-team** station: unlike `CheckMemberChipScreen` (which derives
      raceId from the selected team), a judge scans all teams. Use the currently-active race (the same
      race whose `member_tags` pool + teams are loaded). During implementation confirm how MainActivity
      exposes the active raceId (selected-team's race, or a race-level selection); if none is selected,
      show a "select a race" guard and don't arm the hook.
- [ ] add `@Volatile var onTagForJudgeScan: ((Tag) -> Unit)? = null` + a new branch in `onTagDiscovered`
      (admin-hook priority band, above `onTagForMark`; hop to main via `mainHandler.post`)
- [ ] `JudgeScanScreen(session, raceId, eventType, onClose)`: null-sentinel pool collect
      (`observeForRace(raceId).collectAsState(initial = null)`, `poolReady = pool != null`), `rememberUpdatedState`
- [ ] `DisposableEffect(raceId)` arms/clears the hook; `Mutex`-serialized body captures
      `trustedClock.sample()` first, `normalizeNfcUid`, pool lookup; gate on `poolReady` **before**
      `readChipCode` (mirror `CheckMemberChipScreen`'s `dataReady` gate — avoids a wasted NfcA transceive
      when the pool isn't loaded), then `readChipCode` only on the not-in-pool branch
- [ ] `classifyJudgeScan(...)`; on `Recorded` → `applicationScope.launch { judgeScanRepo.record(...) }`,
      `scanFeedback.play(Success)`; else `play(Failure)`; append to capped `recent` list
      (`removeAt(lastIndex)`, **not** `removeLast()`)
- [ ] big live clock: `LaunchedEffect` `delay(1000)` reading `trustedClock.sample()` → prefer
      `trustedMs`, fall back to `wallMs` when `trustedMs == null`; format `SimpleDateFormat("HH:mm:ss", Locale.US)`
      in the **device-default timezone** (do NOT force UTC — the judge compares to a local wristwatch; the
      "UTC where needed" rule is for stored ISO strings, not human-facing local time); prominent large type at top
- [ ] `ScanClockBanner(clockStatus)` under the clock (derive `ClockStatus` from the clock, same source the
      scan overlay uses); a «Синхронизируйте гонку» plate when the pool has never synced (treat scans as `PoolNotReady`)
- [ ] (manual-verification only — Compose host + NFC adapter untested by convention)

### Task 10: Admin menu wiring + 60 s ticker

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/AdminScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add two `AdminActionRow` entries in `AdminHome` («Отметка старта» / «Отметка финиша»)
- [ ] host state in MainActivity: `showJudgeScan: String?` (null | "start" | "finish") via `rememberSaveable`;
      render `JudgeScanScreen` overlay after `Scaffold`, dismiss via `BackHandler`
- [ ] add a dedicated 60 s ticker in its **own coroutine** — the existing marks/track loop
      (`while(true){ … delay(UPLOAD_RETRY_INTERVAL_MS) }`, MainActivity ~568-573) never returns, so a
      second bare `while(true)` in the same coroutine is unreachable and the tick would never fire.
      Use a separate `LaunchedEffect { repeatOnLifecycle(STARTED) { while (true) {
      container.applicationScope.launch { judgeScanRepo.uploadAllPending() }; delay(60_000L) } } }`
      (or wrap each loop in its own `launch { }` inside one `repeatOnLifecycle` block)
- [ ] (manual-verification only — overlay wiring + ticker untested by convention)

### Task 11: Verify acceptance criteria

- [ ] «Старт» and «Финиш» each open a fixed-mode page; a pooled bracelet records, unknown/КП rejects (error cue, no row)
- [ ] pool-not-synced state warns and records nothing
- [ ] rows survive an app kill; the 60 s tick drains to LAN + cloud independently; repeat piks each create a row
- [ ] big clock ticks; `ScanClockBanner` shows on skew/no-sync
- [ ] run full JVM suite: `./gradlew testDebugUnitTest`
- [ ] run lint: `./gradlew lintDebug`
- [ ] run instrumented: `./gradlew connectedDebugAndroidTest`

### Task 12: [Final] Docs

**Files:**
- Modify: `docs/design/UPLOAD.md`, `docs/design/DATA-NOTES.md`, `docs/design/UI-NOTES.md`, `CLAUDE.md`

- [ ] `UPLOAD.md`: add the `judge_scans` upload contract (URL, request/response, partial-accept, idempotency)
- [ ] `DATA-NOTES.md`: `JudgeScanRepository` + `judge_scans` table/DAO notes (raceId-only scope, write-once)
- [ ] `UI-NOTES.md`: `JudgeScanScreen`/`JudgeScanModel` + the new `onTagForJudgeScan` hook priority
- [ ] `CLAUDE.md`: module-map lines (ui/admin, data), Room **v4 → v5**, add
      `JudgeScanDaoTest`/`MIGRATION_4_5` to the `connectedDebugAndroidTest` guard list
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — informational only.*

**External system updates:**
- **Server endpoint** `POST /app/race/<raceId>/judge_scans/` is **not yet implemented**. Until it
  exists both targets return non-Success and rows stay pending (correct, self-healing). The endpoint
  must: verify the app signature + admin Bearer, upsert idempotently by `id`, apply partial-accept
  (return `accepted[]`, never whole-batch 400), and dedupe repeat piks server-side.

**Manual verification (on device):**
- Airplane-mode capture, then restore network and confirm the 60 s tick drains to **both** LAN and
  cloud; kill the app mid-session and confirm rows persist and still upload.
- Reject flows: foreign card → `UnknownChip` error cue; КП chip → `KpChip`; pool-not-synced → warning.
- Clock: force skew / no-sync and confirm the banner; confirm the big clock reads trusted time.
