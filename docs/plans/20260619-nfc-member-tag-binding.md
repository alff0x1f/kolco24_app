# NFC Member-Tag Binding on the «Команда» Tab

## Overview
Let a user bind a physical NFC bracelet (chip) to each member of their selected team, on the
«Команда» tab. The chip pool comes from the new `GET /app/race/<race_id>/member_tags/` endpoint
(`nfc_uid → participant number`). Because a team member carries only `name` + `number_in_team`
(no participant number), there is **no automatic join** — the user taps «Привязать» on a member,
physically scans a chip, the app validates the read UID against the `member_tags` pool, and stores
the binding **locally** keyed by the member slot `(teamId, numberInTeam)`.

Benefits: the «N / total с чипом» counter on the team hero card goes live, each member row shows its
bound chip (participant number + UID), and the data groundwork (real NFC reading + the `member_tags`
sync resource) is reusable by the future checkpoint-scan feature.

## Context (from discovery)
- **Files/components involved:**
  - Data: `data/api/ApiClient.kt`, `data/api/dto/` (new `MemberTagsResponse.kt`), `data/db/AppDatabase.kt`,
    new `data/db/MemberTagEntity.kt` + `MemberTagDao`, new `data/db/MemberChipBindingEntity.kt` +
    `MemberChipBindingDao`, new `data/MemberTagsRepository.kt`, new `data/MemberChipBindingRepository.kt`.
  - DI / app: `AppContainer.kt`, `Kolco24App.kt`.
  - NFC + UI: `MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `ui/team/TeamScreen.kt`,
    new `ui/team/BindChipSheet.kt`.
  - Schema/tests: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/4.json` (generated),
    `app/src/androidTest/.../data/db/MigrationTest.kt`, unit tests under `app/src/test/`.
- **Related patterns found (mirror these exactly):**
  - `data/LegendRepository.kt` — conditional fetch, `replaceAll` **then** ETag upsert as two separate
    transactions, ETag skipped when null, `RefreshResult` enum. Tested by `LegendRepositoryTest` with a
    `MockWebServer` + fake DAOs + `callLog` to assert write ordering.
  - `ApiClient.conditionalGet(url, etag, parse)` — add one `fetchMemberTags` delegate.
  - `AppDatabase` migrations are raw `CREATE TABLE/INDEX` whose SQL must byte-match Room's generated
    schema JSON (camelCase columns, index names like `index_teams_raceId`). KSP does **not** verify;
    only runtime / `MigrationTest` does.
  - `ui/teampicker/TeamSwitchSheet.kt` — `ModalBottomSheet` pattern to mirror for the bind sheet.
  - `MainActivity` overlay pattern — `rememberSaveable` flag + guarded `BackHandler`; writes that must
    outlive a closing overlay run on `container.applicationScope.launch` (see `selectTeam`).
- **Dependencies identified:** Room (KSP), kotlinx.serialization, OkHttp; Android NFC
  (`android.nfc.NfcAdapter`, reader mode). No new third-party libraries.

## Development Approach
- **Testing approach: Regular** (code first, then tests) — matches existing repo style.
- Complete each task fully before moving to the next; small, focused changes.
- **Every task with code changes MUST include new/updated tests** (success + error/edge cases),
  listed as separate checklist items.
- **All tests must pass before starting the next task.** Run `./gradlew testDebugUnitTest` (and
  `lintDebug`) after each code change.
- The Room migration is guarded by an **instrumented** test (`./gradlew connectedDebugAndroidTest`,
  needs an emulator/device). Treat it with the same rigor.
- Maintain backward compatibility: the migration is additive (existing tables untouched).
- Keep this plan in sync if scope changes.

## Testing Strategy
- **Unit tests** (`app/src/test/`, JVM): DTO deserialization (`MemberTagsResponseTest`),
  `MemberTagsRepository` (mirror `LegendRepositoryTest` with `MockWebServer` + fake DAOs + `callLog`),
  `MemberChipBindingRepository` (fake DAO), the pure UID hex+normalize helper, and any extracted
  bind-decision logic (`NotInPool` / `AlreadyBound` / new bind).
- **Instrumented tests** (`app/src/androidTest/`): extend `MigrationTest` with `migrate3To4_*` cases
  (data survives, both new tables exist, column sets match, `nfcUid` index exists).
- **No UI e2e framework** in this project — Compose UI / NFC read is verified manually on hardware
  (see Post-Completion).
- Gate before merge: `./gradlew lintDebug` + `./gradlew testDebugUnitTest` pass; migration test run
  on a device.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- New tasks: `➕` prefix. Blockers: `⚠️` prefix.
- Update this plan if implementation deviates from scope.

## Solution Overview
1. **`member_tags` sync** — a new synced resource mirroring `LegendRepository`/`CheckpointEntity`
   **exactly**: DTO → `ApiClient` delegate → `MemberTagsRepository` → a **per-race** Room table
   `member_tags` (`raceId` column, `replaceAllForRace`), with a **per-race** ETag in `sync_meta`
   (resource key `"race/<raceId>/member_tags"`). The pool is *currently* identical across races, but
   the backend is expected to move to **per-race tag pools** (API.md: «задел под будущие пер-гоночные
   комплекты»); modelling it per-race now future-proofs the schema and matches the established legend
   pattern, so no migration is needed when the backend switches.
2. **Local bindings** — a Room table `member_chip_bindings`, composite PK `(teamId, numberInTeam)`,
   `@Index` on `nfcUid`, exposed by `MemberChipBindingRepository`. Local-only; never uploaded.
3. **Real NFC reading** — manifest permission + `NfcAdapter.enableReaderMode` lifecycle in
   `MainActivity`, app-wide armed, routed to a nullable `onTagScanned` hook; UID hex-encoded +
   normalized (`trim` + uppercase) to match the server's pool format.
4. **Bind UI** — `TeamScreen` shows per-member bound/unbound state and the live counter; a
   `BindChipSheet` (`ModalBottomSheet`) drives the capture → validate-against-pool → store flow,
   with `NotInPool` and `AlreadyBound` (warn + reassign) branches. `MainActivity` wires it all.

## Technical Details
- **`member_tags` JSON:** `{"member_tags":[{"number":101,"nfc_uid":"04A2B3C4D5E680"}, ...]}`.
  `nfc_uid` is already normalized server-side (trim + UPPERCASE). The app normalizes read UIDs the
  same way before comparing.
- **`MemberTagEntity`** (`member_tags`): `@Entity(primaryKeys = ["raceId","nfcUid"], indices=[Index("raceId")])`,
  columns `raceId: Int`, `nfcUid: String`, `number: Int`. Composite PK `(raceId, nfcUid)` because the
  pool is per-race and `member_tags` carries no server `id` (API.md: «Внутреннего id в выдаче нет —
  слот идентифицируется по `nfc_uid`»); the same `nfc_uid` may legitimately appear in two races' pools.
- **`MemberChipBindingEntity`** (`member_chip_bindings`): `@Entity(primaryKeys = ["teamId","numberInTeam"], indices=[Index("nfcUid")])`,
  columns `teamId: Int`, `numberInTeam: Int`, `nfcUid: String`, `participantNumber: Int`.
  `participantNumber` is resolved from the pool at bind time and stored so a row renders without a
  pool lookup.
- **Binding-key assumption (recorded):** the slot key is `(teamId, numberInTeam)` because a team
  member exposes no stable participant id — only `name` + `number_in_team`. This relies on
  `number_in_team` being **stable for a registered team** across `refreshTeams` (which does
  `replaceAllForRace`). For this event model a registered team's roster/slot order is fixed, so the
  assumption holds; if the backend ever reorders slots, a binding could re-associate to the new slot
  occupant. Document this assumption in CLAUDE.md (Task 14). No automatic mismatch detection is
  possible (the member carries no participant number to compare against the stored `participantNumber`).
- **Sync resource key:** per-race `"race/$raceId/member_tags"` in `sync_meta` (per `origin`),
  identical in shape to the legend key. Because the table is now per-race (`replaceAllForRace`), the
  two warm-ups touching different races in one session (startup warms the nearest race, Launch B the
  selected one) write disjoint rows and disjoint ETags — no clobber, and it stays correct once the
  backend serves a genuinely different pool per race.
- **Pool lookups are race-scoped:** the bind flow resolves a scanned UID against the **selected
  team's race** pool — `memberTagDao.findByUid(selectedRaceId, uid)` — never the whole table.
- **NFC reader flags:** `FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_NFC_F or FLAG_READER_NFC_V or FLAG_READER_SKIP_NDEF_CHECK`.
  UID = `tag.id` bytes → hex string → `trim().uppercase()`.
- **NFC availability:** `adapter == null` → no hardware; `adapter != null && !isEnabled` → disabled
  (sheet offers `Settings.ACTION_NFC_SETTINGS` deep-link); else available.
- **Duplicate policy:** scanned UID already bound to another `(teamId, numberInTeam)` → sheet shows
  `AlreadyBound(otherSlot)` with «Перепривязать» that deletes the old slot row then writes the new one.
- **Bind-sheet writes** run on `container.applicationScope` (outlive the closing sheet, per
  `selectTeam` convention); sheet UI state lives in composition.

## What Goes Where
- **Implementation Steps** (`[ ]`): all code, unit tests, the migration + its instrumented test,
  schema JSON, and doc updates.
- **Post-Completion** (no checkboxes): on-device NFC verification (real bracelet scans, NFC-off
  prompt, no-hardware device), which cannot be automated here.

## Implementation Steps

### Task 1: `member_tags` DTO + `ApiClient.fetchMemberTags`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/MemberTagsResponse.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/MemberTagsResponseTest.kt`

- [x] create `MemberTagsResponse(@SerialName("member_tags") val memberTags: List<MemberTagDto>)` and
      `MemberTagDto(val number: Int, @SerialName("nfc_uid") val nfcUid: String)` (`@Serializable`)
- [x] add `suspend fun fetchMemberTags(raceId: Int, etag: String?): FetchResult<MemberTagsResponse>`
      delegating to `conditionalGet("$baseUrl/app/race/$raceId/member_tags/", etag) { json.decodeFromString(it) }`
- [x] write tests for `MemberTagsResponse` parsing (success: list maps; `ignoreUnknownKeys`; empty list)
- [x] add an `ApiClientTest` case for `fetchMemberTags` (200 + ETag, 304, 403) mirroring the legend case
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 2: `MemberTagEntity` + `MemberTagDao`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MemberTagEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MemberTagDao.kt`

- [x] create `@Entity(tableName = "member_tags", primaryKeys = ["raceId","nfcUid"], indices = [Index("raceId")])`
      `MemberTagEntity(val raceId: Int, val nfcUid: String, val number: Int)` (mirrors `CheckpointEntity`'s
      per-race shape)
- [x] create `MemberTagDao` (mirror `CheckpointDao`): `observeForRace(raceId): Flow<List<MemberTagEntity>>`,
      `suspend findByUid(raceId: Int, nfcUid: String): MemberTagEntity?`,
      `@Transaction suspend replaceAllForRace(raceId, tags)` (`deleteForRace(raceId)` + `insertAll(tags)`,
      plus the `deleteForRace`/`insertAll` helpers)
- [x] (no separate unit test — trivial DAO, covered by repo + migration tests; note in commit)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: `MemberChipBindingEntity` + `MemberChipBindingDao`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MemberChipBindingEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/MemberChipBindingDao.kt`

- [x] create `@Entity(tableName = "member_chip_bindings", primaryKeys = ["teamId","numberInTeam"], indices = [Index("nfcUid")])`
      `MemberChipBindingEntity(teamId: Int, numberInTeam: Int, nfcUid: String, participantNumber: Int)`
- [x] create `MemberChipBindingDao`: `observeForTeam(teamId): Flow<List<MemberChipBindingEntity>>`,
      `suspend findByUid(nfcUid): MemberChipBindingEntity?`, `@Upsert suspend upsert(binding)`,
      `suspend deleteSlot(teamId, numberInTeam)` (also `deleteByUid` + atomic `@Transaction reassign`,
      pulled forward from Task 6)
- [x] (no separate unit test — DAO behavior covered by repo + migration tests)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 4: Room v3→v4 migration + schema + register DAOs/entities

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`
- Generated: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/4.json`

- [ ] add `MemberTagEntity` + `MemberChipBindingEntity` to `@Database(entities=[...])`, bump
      `version = 4`, add `abstract fun memberTagDao()` + `memberChipBindingDao()`
- [ ] write `MIGRATION_3_4`: `CREATE TABLE member_tags` (composite PK `(raceId, nfcUid)`) +
      `CREATE INDEX index_member_tags_raceId` + `CREATE TABLE member_chip_bindings` (composite PK
      `(teamId, numberInTeam)`) + `CREATE INDEX index_member_chip_bindings_nfcUid`; register in
      `addMigrations(...)`
- [ ] build once (`./gradlew assembleDebug`) so KSP regenerates `4.json`; confirm the migration SQL
      byte-matches the generated table/column/index names (camelCase); **commit `4.json` to git**
      (exportSchema=true requires it tracked, like `{1,2,3}.json`)
- [ ] add `migrate3To4_keepsDataAndAddsTables` + `migrate3To4_indexesExist` to `MigrationTest`
      (seed a v3 race/team, run 1→2→3→4, assert survival, both tables present + insertable, both the
      `index_member_tags_raceId` and `index_member_chip_bindings_nfcUid` indexes exist)
- [ ] run `./gradlew lintDebug testDebugUnitTest`; run `./gradlew connectedDebugAndroidTest` (device) —
      migration test must pass before next task

### Task 5: `MemberTagsRepository`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/MemberTagsRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/MemberTagsRepositoryTest.kt`

- [ ] create `MemberTagsRepository(apiClient, memberTagDao, syncMetaDao, origin)` mirroring
      `LegendRepository`: `observeForRace(raceId)`, `suspend findByUid(raceId, uid)`,
      `suspend refreshMemberTags(raceId): RefreshResult` (per-race resource key
      `"race/$raceId/member_tags"`; `replaceAllForRace` **then** ETag upsert as two transactions,
      ETag skipped when null)
- [ ] add a private `MemberTagDto.toEntity(raceId)` mapping (`raceId`, `nfcUid`, `number`)
- [ ] write repo tests mirroring `LegendRepositoryTest`: success maps + stores ETag under
      `"race/$raceId/member_tags"`; write-before-ETag ordering via `callLog`; success-without-ETag
      skips ETag save; 304 leaves data; offline; 403; 500 → `HttpError`; empty list replaces that
      race's rows; two different `raceId`s use **different** ETag resource keys and write disjoint rows
      (mirror `LegendRepositoryTest.differentRaceIds_useDifferentSyncResources`)
- [ ] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 6: `MemberChipBindingRepository`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/MemberChipBindingRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/MemberChipBindingRepositoryTest.kt`

- [ ] create `MemberChipBindingRepository(bindingDao)`: `observeForTeam(teamId)`,
      `suspend findByUid(uid)`, `suspend bind(teamId, numberInTeam, nfcUid, participantNumber)`,
      `suspend unbind(teamId, numberInTeam)`. Implement the reassign (warn+allow) case as a single
      **atomic** `@Transaction` DAO method `reassign(...)` (`deleteByUid(uid)` then `upsert(binding)`)
      so a chip is never momentarily on two slots; the repo just calls it. The warn+allow *decision*
      stays in the sheet (`decideBind`); the repo only performs writes.
- [ ] write tests with a fake DAO: bind then observe; rebind same slot overwrites; unbind removes;
      `findByUid` returns the owning slot; reassign atomically moves a chip between slots (old slot
      gone, new slot present)
- [ ] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 7: Wire repositories into DI + startup warm-up

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`

- [ ] add lazy `memberTagsRepository` + `memberChipBindingRepository` to `AppContainer`
- [ ] in `Kolco24App` Launch A, add a concurrent child `launch { refreshMemberTags(nearest) }`
      alongside teams + legend (log result)
- [ ] in `Kolco24App` Launch B `supervisorScope`, add a `launch { refreshMemberTags(raceId) }`
      alongside teams + legend
- [ ] (DI/warm-up wiring is integration-level; covered by existing repo tests — note in commit)
- [ ] run `./gradlew lintDebug testDebugUnitTest` — must pass before next task

### Task 8: NFC UID helper (pure)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/NfcUid.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/NfcUidTest.kt`

- [ ] create a single pure `fun normalizeNfcUid(raw: ByteArray): String` — hex-encode each byte
      (zero-padded, e.g. `0x04` → `"04"`) then `.uppercase()`. (No `String` overload — the byte→hex
      path is the only real logic; pool values arrive already normalized from the server.)
- [ ] write tests: bytes → uppercase hex (incl. leading-zero byte like `0x04`); multi-byte UID;
      empty array → empty string
- [ ] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 9: NFC reading infra in MainActivity + manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] manifest: add `<uses-permission android:name="android.permission.NFC"/>` and
      `<uses-feature android:name="android.hardware.nfc" android:required="false"/>`
- [ ] `MainActivity`: lazy `NfcAdapter`; implement `NfcAdapter.ReaderCallback`; `enableReaderMode` in
      `onResume` / `disableReaderMode` in `onPause` with the reader flags; on tag → `normalizeNfcUid(tag.id)`,
      post to main thread, invoke `onTagScanned?.invoke(uid)`
- [ ] expose `var onTagScanned: ((String) -> Unit)? = null` and an `nfcState`
      (`NoHardware` / `Disabled` / `Available`) readable by composables; recompute `Disabled/Available`
      on resume
- [ ] (NFC hardware path is device-only — manual verification; no unit test. The hex/normalize logic
      is already unit-tested in Task 8)
- [ ] run `./gradlew lintDebug` — must pass before next task

### Task 10: `TeamScreen` bound/unbound rows + live counter

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`

- [ ] add params `bindings: Map<Int, MemberChipBindingEntity>` (key = `numberInTeam`),
      `onBindMember: (TeamMemberItem) -> Unit`, `onUnbindMember: (TeamMemberItem) -> Unit`,
      `nfcAvailable: Boolean`
- [ ] replace hardcoded `boundCount = 0` in `TeamHeroCard` with `bindings.size`; thread it through so
      the «N / total с чипом» badge + `chipNotBoundText` footer reflect reality
- [ ] `MemberRow`: bound → green `CheckCircle` + mono «№{participantNumber} · {uid}», tap row →
      `onUnbindMember` (with a small confirm); unbound → existing «Чип не привязан» + «Привязать»
      button calling `onBindMember(member)` (disabled/explanatory when `!nfcAvailable`)
- [ ] (Compose visuals verified manually; keep logic — counter/lookups — trivial and pure)
- [ ] run `./gradlew lintDebug` — must pass before next task

### Task 11: `BindChipSheet`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/team/BindChipSheet.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/team/BindChipDecisionTest.kt`

- [ ] extract a pure decision function, e.g.
      `decideBind(uid, poolNumber: Int?, existing: MemberChipBindingEntity?, currentSlot): BindOutcome`
      returning `NotInPool` / `AlreadyBound(otherSlot)` / `ReadyToBind(participantNumber)` /
      `AlreadyOnThisSlot`
- [ ] build `BindChipSheet(member: TeamMemberItem, ...)` (`ModalBottomSheet` mirroring
      `TeamSwitchSheet`) rendering states `Waiting` (+ NFC-disabled prompt with
      `Settings.ACTION_NFC_SETTINGS`), `NotInPool`, `AlreadyBound(otherSlot)` + «Перепривязать»,
      `Success` (auto-dismiss). The sheet takes the resolved `member` so it can show the name; the
      host (Task 12) resolves it from `bindSlot`.
- [ ] write tests for `decideBind`: uid not in pool → `NotInPool`; uid in pool, unbound → `ReadyToBind`;
      uid bound to another slot → `AlreadyBound`; uid already on this slot → `AlreadyOnThisSlot`
- [ ] run `./gradlew testDebugUnitTest lintDebug` — must pass before next task

### Task 12: MainActivity wiring (sheet + bindings + NFC routing)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] derive `bindings` with the existing null-guarded keyed-`remember` flow pattern (cf.
      MainActivity lines 127-129):
      `remember(selectedTeamId) { selectedTeamId?.let { bindingRepo.observeForTeam(it) } ?: flowOf(emptyList()) }`
      `.collectAsState(initial = emptyList())`, mapped to `Map<Int, MemberChipBindingEntity>` keyed by
      `numberInTeam`; pass `bindings` + `nfcAvailable` into `TeamScreen`
- [ ] add `var bindSlot by rememberSaveable { mutableStateOf<Int?>(null) }` (keep `Int?` — saveable
      without a custom Saver, consistent with the existing `confirmTeamId`/enum approach). `onBindMember`
      sets `bindSlot = member.numberInTeam`. Render `BindChipSheet` only when `bindSlot != null` **and**
      the member resolves: `val bindMember = teamForTab?.members?.find { it.numberInTeam == bindSlot }`
- [ ] register/clear the NFC hook with a `DisposableEffect(bindSlot)` (NOT scattered imperative
      set/clear): on enter set `(context as MainActivity).onTagScanned = { uid -> ... }`; in `onDispose`
      set it back to `null`. This guarantees the hook is cleared on **every** exit path (dismiss,
      BackHandler, success auto-dismiss, team switch, recomposition). The callback runs on the main
      thread (Task 9 posts there); it does the **race-scoped** pool lookup
      (`memberTagDao.findByUid(selectedRaceId, uid)`) + `decideBind` + writes via
      `container.applicationScope` (outlives the closing sheet)
- [ ] `onUnbindMember` deletes the slot via `container.applicationScope.launch { bindingRepo.unbind(selectedTeamId, member.numberInTeam) }`
- [ ] add `BackHandler(enabled = bindSlot != null && !showScan && teamFlowStep == TeamFlowStep.None && confirmTeamId == null)`
      clearing `bindSlot`, layered consistently with the existing overlay guards
- [ ] run `./gradlew lintDebug testDebugUnitTest` — must pass before next task

### Task 13: Verify acceptance criteria
- [ ] verify all Overview requirements: pool syncs; «Привязать» reads a chip, validates against pool,
      stores binding; counter + member rows reflect bindings; unbind works; reassign warns + moves
- [ ] verify edge cases: chip not in pool; chip already bound elsewhere; NFC off; no-NFC device;
      team switch shows that team's bindings
- [ ] run full unit suite: `./gradlew testDebugUnitTest`
- [ ] run lint: `./gradlew lintDebug`
- [ ] run migration suite on device: `./gradlew connectedDebugAndroidTest`

### Task 14: [Final] Update documentation
- [ ] update `CLAUDE.md`: new `MemberTagsRepository` / `MemberChipBindingRepository`, `member_tags`
      (per-race table, per-race `"race/<raceId>/member_tags"` ETag key, modelled per-race ahead of the
      backend's future per-race pools) + `member_chip_bindings` tables, Room v4 +
      `MIGRATION_3_4`, NFC reader-mode infra in `MainActivity`, `BindChipSheet`, and `TeamScreen`'s
      now-live binding UI (replace the «NFC chip binding is out of scope» / «boundCount = 0» notes).
      Record the **binding-key assumption**: bindings are keyed by `(teamId, numberInTeam)` and rely on
      `number_in_team` being stable for a registered team
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only.*

**Manual verification (on hardware — NFC cannot be automated here):**
- Bind a real bracelet to a member: tap «Привязать», scan → row shows «№ · UID», counter increments.
- Scan a chip **not** in the pool → `NotInPool` message; nothing stored.
- Scan a chip already bound to another member → `AlreadyBound` + «Перепривязать» moves it.
- Turn NFC off → sheet shows the prompt and the `Settings.ACTION_NFC_SETTINGS` deep-link works.
- Install on a device without NFC hardware → «Привязать» shows the disabled/explanatory state; app
  still runs.
- Switch teams → the «Команда» tab shows only the newly selected team's bindings; UIDs normalize
  (uppercase, no whitespace) to match server pool entries.

**External / backend:**
- Bindings are **local-only** this iteration (never uploaded). If the backend later accepts client
  chip assignments, a separate plan handles the upload/sync direction.
