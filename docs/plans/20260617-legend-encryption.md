# Legend encryption rework (per-CP locked checkpoints + offline crypto unlock)

## Overview

The server changed how the race legend is served — **per-checkpoint encryption** was added (spec:
`docs/design/API.md`, `GET /app/race/<id>/legend/`). This rework adapts the app to the new contract:

- The legend is **always** returned; the race-level `is_legend_visible` flag is **gone** (removed
  from `/app/races/` too).
- **Locked** checkpoints (`is_legend_locked`) arrive with an `enc:{iv,ct}` envelope **instead of**
  `cost`/`description` — the plaintext never leaves the server.
- A new **`tags[]`** array maps each NFC tag's `bid → point` (which CP it belongs to), plus an
  unlock envelope for tags that open locked CPs.
- Unlocking is **offline crypto**: read the 16-byte NFC `code` → HKDF-SHA256 → AES-256-GCM unseal
  the tag bundle → per-CP `content_key` → unseal the CP's `enc` → reveal `{cost, description}`.

**Benefit:** the app keeps working against the new server contract, locked CPs render correctly (
masked), and a fully-tested crypto engine + refresh-safe unlock path is in place — ready for NFC to
call later.

**Scope (decided in brainstorm):** data + display + a unit-tested **pure crypto engine**. **NO NFC
wiring** — `ScanScreen` stays mock; the unlock path's only caller this task is the crypto unit test.
The `sync` manifest endpoint is **out of scope** (keep current per-resource ETag refresh).

## Context (from discovery)

Package root is `ru.kolco24.kolco24` (NOT `com.kolco24`). Files/components involved:

- DTOs: `data/api/dto/LegendResponse.kt`, `data/api/dto/RacesResponse.kt`
- Room: `data/db/CheckpointEntity.kt`, `data/db/CheckpointDao.kt`, `data/db/RaceEntity.kt`,
  `AppDatabase` + migrations, `schemas/.../{1,2,3}.json`
- Repos: `data/LegendRepository.kt`, `data/RaceRepository.kt`
- UI: `ui/legend/LegendScreen.kt`, `MainActivity.kt`
- Tests: `app/src/test/...` (JVM), `app/src/androidTest/...` (`MigrationTest`)

Related patterns found:

- `LegendRepository.refreshLegend` already follows "data write first, ETag upsert second, self-heal
  on crash"; `CheckpointDao.replaceAllForRace` = delete-then-insert in a `@Transaction`.
- `TeamRepository` / `CheckpointDao` are the templates for the new `TagDao` / `TagEntity`.
- Each `MIGRATION_*` is raw `CREATE TABLE`/`CREATE INDEX` SQL that must match Room's generated
  schema JSON exactly (camelCase columns, `index_<table>_<col>`). KSP does NOT verify this —
  mismatch surfaces only at runtime. `MigrationTest` guards each migration.
- Base64 must be **okio `ByteString`** (already on classpath via OkHttp) — `java.util.Base64` needs
  API 26 (> minSdk 24), `android.util.Base64` is stubbed/unavailable in JVM unit tests.

Dependencies identified: `kotlinx.serialization` Json (injected via `AppContainer`),
`javax.crypto` (Mac/Cipher, JVM), okio (Base64), `androidx.room:room-testing` +
`MigrationTestHelper` (already wired).

## Development Approach

- **Testing approach: Regular** (code first, then tests) — except `LegendCryptoTest`, which is gated
  on a server-generated fixture (see Prerequisite + Task 5).
- complete each task fully before moving to the next; small, focused changes.
- **Every task MUST include new/updated tests** for its code changes (success + error scenarios),
  listed as separate checklist items.
- **All tests must pass before starting the next task.** Exception: the crypto unit test is blocked
  on the server vector — see the partial-implementation note in Task 5.
- run tests after each change; keep this plan file in sync with actual work.

## Testing Strategy

- **unit tests (JVM, `./gradlew testDebugUnitTest`)**: required every task — DTO parsing, crypto
  primitives + full unlock (against the server vector), repository `unlock` (fakes), entity mapping.
- **instrumented (`./gradlew connectedDebugAndroidTest`, needs emulator/device)**: `MigrationTest`
  3→4 case (guards the Room migration) **and** `CheckpointDaoTest` preserve-on-resync (real Room
  `@Transaction` — Robolectric isn't on the classpath, so this can't be a JVM test).
- no UI e2e harness in this project (single-activity Compose, no Playwright/Cypress) — Compose UI is
  validated by manual run + lint.
- **Gate before merge:** `./gradlew lintDebug` + `./gradlew testDebugUnitTest` green;
  `connectedDebugAndroidTest` for the migration.

## Progress Tracking

- mark completed items `[x]` immediately when done.
- add newly discovered tasks with ➕ prefix; document blockers with ⚠️ prefix.
- update this plan if implementation deviates from scope.

## Prerequisite (blocking for Task 5 only)

⚠️ **`LegendCryptoTest` cannot be trusted without a server-generated test vector.** The crypto can
be *written* (Tasks 2–3) without it, but not *verified*. Needed fixture, generated from
`src/apps/mobile/crypto.py` + `legend_crypto.py` on the server:

- a 16-byte `code` (hex),
- the tag envelope `{iv, ct}` (Base64) for that `code`,
- the `bid` for that `code`,
- the wrap-key (hex) for that `code`,
- at least one CP `enc:{iv,ct}` it opens + the expected `{cost, description}` plaintext,
- ideally a CP opened via the `content_key` indirection so the full chain is covered.

Tasks 1–4 are unblocked. Task 5's crypto assertions stay red (or skipped with a TODO) until this
fixture lands.

## Solution Overview

1. **Data model** — DTOs gain `tags` + nullable/`enc` checkpoint fields; `CheckpointEntity` gains
   `locked`/`encIv`/`encCt` and nullable `cost`/`description`; new `TagEntity`/`TagDao`; Room v3→v4
   migration recreating `checkpoints` + `races` and adding `tags`.
2. **Crypto engine** — pure `LegendCrypto` (HKDF + AES-GCM + bid + bundle/content-key indirection),
   JVM-testable, okio Base64.
3. **Repository** — `refreshLegend` persists both arrays (preserve-on-resync); new
   `LegendRepository.unlock(raceId, code)` decrypts + persists revealed CPs.
4. **UI + races cleanup** — masked locked rows in `LegendScreen`, score over known costs, remove
   `is_legend_visible` everywhere.
5. **Tests** — crypto vector, DTO parse, preserve-on-resync, migration.

Key design decisions (settled): **option A** revealed-content persistence (single table,
`replaceAllForRace` preserves prior non-null `cost`/`description` for still-`locked` ids — a 200
refresh must not re-lock an unlocked CP); `taken` is **not** touched by `unlock` (the unbuilt marks
feature owns it); masked rows replace the dead `LegendLocked` "before start" card (no banner).

## Technical Details

**Crypto, matched to the doc exactly:**

- `bid = sha256(code).hex().take(16)`.
- `wrap_key = HKDF-SHA256(code, salt=None→32 zero bytes, info="kp-wrap-v1" (US_ASCII), len=32)`.
- AES-256-GCM: `Cipher("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv)`, `updateAAD(aad)`,
  `doFinal(ct)`; `iv`=12 bytes, `ct`=`ciphertext||tag(16)`, both Base64.
- Tag bundle: `open(wrap_key, tag.iv, tag.ct, aad=bid.toByteArray(US_ASCII))` → JSON
  `{ "<cpId>": "<b64 content_key>" }`.
- Per CP: `open(content_key, enc.iv, enc.ct, aad=cpId.toString().toByteArray(US_ASCII))` → JSON
  `{cost, description}`.

**v3→v4 migration (raw SQL):** SQLite-24 can't relax `NOT NULL` or `DROP COLUMN`, so recreate
`checkpoints` (copy rows, `locked=0`, enc null, nullable `cost`/`description`) and `races` (drop
`is_legend_visible`), and `CREATE TABLE tags` + `index_tags_raceId` + `index_tags_point`. SQL must
match `schemas/.../4.json` byte-for-byte (camelCase columns).

## What Goes Where

- **Implementation Steps** (`[ ]`): all code, tests, migration, schema JSON within this repo.
- **Post-Completion** (no checkboxes): obtaining the server crypto vector, real NFC scan wiring, the
  deferred `sync` endpoint, manual on-device legend run.

## Implementation Steps

### Task 1: Legend/tags DTOs + races DTO cleanup

**Files:**

- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/LegendResponse.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/RacesResponse.kt`
- Create/Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/LegendResponseTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/RacesResponseTest.kt` (drop the
  `assertTrue(race.isLegendVisible)` assertion at ~:46 — otherwise the DTO change won't compile)

- [ ] `LegendResponse`: add `tags: List<TagDto> = emptyList()`.
- [ ] `CheckpointDto`: make `cost: Int? = null`, `description: String? = null`; add
  `enc: EncDto? = null`. Add `EncDto(iv: String, ct: String)`.
- [ ] Add
  `TagDto(bid, point, @SerialName("check_method") checkMethod, iv: String? = null, ct: String? = null)`.
- [ ] `RacesResponse`: delete the `@SerialName("is_legend_visible") isLegendVisible` field.
- [ ] update `RacesResponseTest` — remove the `isLegendVisible` assertion so the existing test still
  compiles.
- [ ] write test: parse the doc's sample legend JSON (mixed open + locked CP, two tag shapes) via a
  `Json { ignoreUnknownKeys = true }`; assert locked CP has `cost==null`/`enc!=null`, open CP has
  `enc==null`, open-CP tag has `iv==null`.
- [ ] write test: parse a `/app/races/` sample WITHOUT `is_legend_visible`; assert it succeeds.
- [ ] run tests — must pass before next task.

### Task 2: Checkpoint entity (nullable + enc/locked) and TagEntity/TagDao

**Files:**

- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/CheckpointDao.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TagEntity.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/db/TagDao.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/RaceEntity.kt`
- Create: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/CheckpointDaoTest.kt` (
  preserve-on-resync, real Room)

- [ ] `CheckpointEntity`: `cost: Int?`, `description: String?`; add `locked: Boolean`,
  `encIv: String?`, `encCt: String?` (keep `taken: Boolean = false`).
- [ ] `CheckpointDao`: add `@Query` update `reveal(id, cost, description)` (sets the two columns);
  revise `replaceAllForRace` to **preserve-on-resync** (option A) — within the `@Transaction`,
  capture existing non-null `cost`/`description` for ids that remain `locked`, re-apply after
  insert (e.g. read prior revealed rows for `raceId`, then after insert re-issue `reveal(...)` for
  any incoming locked id whose prior `cost` was non-null).
- [ ] `RaceEntity`: delete the `isLegendVisible` column.
- [ ] Create `TagEntity` (table `tags`, `@PrimaryKey bid`, `@Index("raceId")`, `@Index("point")`):
  `point: Int`, `checkMethod: String`, `iv: String?`, `ct: String?`.
- [ ] Create `TagDao` mirroring `CheckpointDao`: `observeTagsForRace(raceId)`,
  `getByBid(bid): TagEntity?`, `@Transaction replaceAllForRace(raceId, tags)`.
- [ ] write **instrumented** test `CheckpointDaoTest` against **real Room** (`androidTest`,
  consistent with `MigrationTest` — Robolectric is NOT on the classpath, so a JVM Room test is not
  an option, and the JVM `FakeCheckpointDao` can't exercise a `@Transaction` body): reveal a CP,
  re-run `replaceAllForRace` with the same locked payload, assert revealed `cost`/`description`
  survive; also assert a NOT-locked incoming row overwrites cleanly.
- [ ] run instrumented tests (needs device/emulator) — must pass before next task.

### Task 3: Room v3→v4 migration + schema JSON + database wiring

**Files:**

- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt` (version 4, register
  `TagEntity`/`TagDao`, add `MIGRATION_3_4`)
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/4.json` (generated by KSP on build)
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` (expose `tagDao` to
  `LegendRepository` if needed)

- [ ] Bump `AppDatabase` to `version = 4`, add `TagEntity` to `entities`, expose `tagDao()`.
- [ ] Write `MIGRATION_3_4` raw SQL: recreate `checkpoints` (new nullable `cost`/`description` +
  `locked`/`encIv`/`encCt`, copy old rows with `locked=0`, enc null), recreate `races` (drop
  `is_legend_visible`), `CREATE TABLE tags` + `CREATE INDEX index_tags_raceId` + `index_tags_point`.
  Register in the migrations list.
- [ ] Build (`./gradlew assembleDebug`) to generate `4.json` (generated from the `@Entity` defs,
  independent of the migration SQL).
- [ ] **Authoritative validation is the instrumented `MigrationTest`, not the build** — KSP/
  `assembleDebug` does NOT verify hand-written SQL against `4.json`; only `runMigrationsAndValidate`
  in the 3→4 test catches column-name/order/`NOT NULL`/index mismatches. Hand-compare against
  `4.json` as a first pass, then rely on the test.
- [ ] write/extend `MigrationTest` 3→4 case (`androidTest`): seed a v3 DB with a **race row (
  name/date/regStatus + `is_legend_visible`)** and a checkpoint row, migrate, assert: `races` lost
  the column **and the race's name/date/regStatus survived the recreate** (not just the dropped
  column), `checkpoints` gained `locked`/`encIv`/`encCt` with old rows intact, `tags` exists.
- [ ] run `./gradlew connectedDebugAndroidTest` (requires device/emulator) — this is the real gate
  for this task; migration test must pass.

### Task 4: Crypto engine — LegendCrypto

**Files:**

- Create: `app/src/main/java/ru/kolco24/kolco24/data/crypto/LegendCrypto.kt`

- [ ] `bid(code: ByteArray): String` = `MessageDigest("SHA-256")` → hex → `.take(16)`.
- [ ] `deriveWrapKey(code: ByteArray): ByteArray` = hand-rolled HKDF-SHA256 over
  `Mac("HmacSHA256")` (extract with 32 zero-byte salt, expand with `info="kp-wrap-v1"` US_ASCII, len
  32).
- [ ] `open(key: ByteArray, ivB64: String, ctB64: String, aad: ByteArray): ByteArray` =
  `Cipher("AES/GCM/NoPadding")` + `GCMParameterSpec(128, iv)` + `updateAAD` + `doFinal`; Base64 via
  okio `ByteString.decodeBase64()`.
- [ ] `unlock(code, tag, encById): UnlockResult` per doc: `tag.iv==null` → identity-only; else open
  bundle (aad=bid), then per cpId open `enc` (aad=str(cpId)) → `Revealed(cpId, cost, description)`
  map. Catch `AEADBadTagException`/serialization → typed `Failed` (never throw). **Keep the engine
  pure:** it consumes a minimal `Map<Int, EncBlob>` (id → `{iv, ct}`) and a small `tag` value type,
  NOT `CheckpointEntity`/DTOs — the repository builds those maps in Task 6.
- [ ] define result types (`UnlockResult` with `Revealed`/`IdentityOnly`/`Failed` cases) + the
  `EncBlob`/`tag` value types in this file; JSON parsing via an injected/passed `Json`.
- [ ] write tests for `bid` shape + `open` round-trip on locally-sealed data (internal sanity). *
  *Note:** a self-sealed round-trip proves the AES-GCM wiring only — HKDF (`salt=None`→32 zero
  bytes), `bid`, and AAD interop with the server are UNVERIFIED until Task 5's vector; Task 4 is
  not "crypto verified".
- [ ] run tests — must pass before next task.

### Task 5: Crypto vector test (gated on server fixture)

**Files:**

- Create: `app/src/test/java/ru/kolco24/kolco24/data/crypto/LegendCryptoTest.kt`
- Create: test fixture (constants or `app/src/test/resources/legend_vector.json`)

- [ ] ⚠️ obtain the server-generated vector (see Prerequisite). Until then, write the test against
  placeholder constants with a TODO and `@Ignore`/skip the vector assertions.
- [ ] assert `bid(code)` → expected 16-hex.
- [ ] assert `deriveWrapKey(code)` → expected 32 bytes (hex).
- [ ] assert `open(...)` on the tag envelope → expected bundle JSON.
- [ ] assert full `unlock(code, tag, checkpoints)` → exact `cost` + `description` for each opened
  CP (incl. a content-key-indirection CP).
- [ ] assert tampered `ct` → `Failed`; open-CP tag (`iv==null`) → `IdentityOnly`.
- [ ] `[x] write tests ... (vector assertions @Ignore until server fixture lands)` — remove the
  TODO/`@Ignore` and verify green once the fixture is provided.
- [ ] run tests.

### Task 6: LegendRepository — persist tags + unlock path

**Files:**

- Modify: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/RaceRepository.kt`
- Create/Modify: `app/src/test/java/ru/kolco24/kolco24/data/LegendRepositoryTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/RaceRepositoryTest.kt` (drop
  `isLegendVisible = true` from the fixture at ~:188 — otherwise the entity change won't compile)

- [ ] `refreshLegend`: map + persist both arrays on `200` — `checkpointDao.replaceAllForRace(...)`
  then `tagDao.replaceAllForRace(...)` then ETag upsert. `CheckpointDto.toEntity`:
  `locked = (enc != null)`, `encIv/encCt = enc?.iv/ct`, cost/description pass through.
  `TagDto.toEntity`: 1:1.
- [ ] add `LegendRepository.unlock(raceId, code: ByteArray): UnlockOutcome`: `bid` →
  `tagDao.getByBid`; null → `Unknown`; `tag.iv==null` → `IdentityOnly(point)`; else build the
  `id→EncBlob` map from the race's checkpoints, call `LegendCrypto.unlock(...)`, **map
  its `UnlockResult` → `UnlockOutcome`** (`Revealed`→persist + `Revealed(point, ids)`, `Failed`→
  `Failed`), persisting each revealed CP via `checkpointDao.reveal(id, cost, description)` (`locked`
  stays true). Do NOT touch `taken`. (Define `UnlockOutcome` here; the `UnlockResult`→
  `UnlockOutcome` translation is the repo's job — the engine stays persistence-free.)
- [ ] `RaceRepository`: drop `isLegendVisible` from the DTO→entity map.
- [ ] update `RaceRepositoryTest` — remove `isLegendVisible` from the fixture/assertions so the
  existing test compiles.
- [ ] write test: `unlock` against the fed server vector returns `Revealed` and persists `cost`/
  `description` (`FakeCheckpointDao`/`FakeTagDao`); `Unknown` for an unmatched bid; `IdentityOnly`
  for an iv-null tag. (Vector-dependent parts share the Task 5 fixture / `@Ignore` gate.)
- [ ] run tests — must pass before next task.

### Task 7: Legend UI masked rows + races cleanup in UI

**Files:**

- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] `LegendScreen`: drop the `legendVisible` param and delete `LegendLocked`; `when` becomes
  `!hasTeam → LegendNoTeam` else `LegendList`. Update the KDoc.
- [ ] `CheckpointRow`: branch on `cp.cost == null` → masked (Lock glyph in label slot, mono
  `?-${number.padStart(2,'0')}`, subtitle «Откроется на КП», muted `onSurfaceVariant`, no check
  icon); else current open/revealed row.
- [ ] `LegendList`/`ScoreCard`: `cost` is now nullable, so **both** score sums need `mapNotNull` —
  `totalScore = checkpoints.mapNotNull { it.cost }.sum()` and
  `takenScore = checkpoints.filter { it.taken }.mapNotNull { it.cost }.sum()` (line ~105 breaks
  otherwise). If any locked-unrevealed CP remains, show a quiet «+N закрытых КП» hint under the
  progress bar. Filter/`totalCount` unchanged.
- [ ] `MainActivity`: remove the `legendVisible = selectedRace?.isLegendVisible == true` argument.
- [ ] grep-sweep `isLegendVisible` / `legendVisible` across `app/src/main` — confirm zero
  stragglers.
- [ ] (no automated UI test harness) verify via build + a manual run that locked rows mask and open
  rows render; lint clean.
- [ ] run `./gradlew lintDebug` — must pass.

### Task 8: Verify acceptance criteria

- [ ] verify all Overview requirements: new legend/tags parsed; locked CPs masked; crypto engine
  present + (vector-gated) verified; `is_legend_visible` fully removed; `sync` untouched.
- [ ] verify edge cases: unmatched bid → `Unknown`; tampered ct → `Failed`; open-CP tag →
  identity-only; refresh after reveal keeps the CP revealed.
- [ ] run full unit suite: `./gradlew testDebugUnitTest`.
- [ ] run `./gradlew lintDebug`.
- [ ] run `./gradlew connectedDebugAndroidTest` (migration) if a device/emulator is available.

### Task 9: [Final] Update documentation

- [ ] update `CLAUDE.md`: new legend contract (always-returned, per-CP `enc`, `tags`),
  `CheckpointEntity` nullable/`locked`/enc + `TagEntity`/`TagDao`, Room **v4** + `MIGRATION_3_4`,
  `LegendCrypto`, `LegendRepository.unlock`, removal of `is_legend_visible`/`LegendLocked`.
- [ ] note any new pattern discovered (e.g. okio Base64 for crypto, preserve-on-resync DAO).
- [ ] move this plan to `docs/plans/completed/`.

## Post-Completion

*Items requiring manual intervention or external systems — informational only*

**Blocking external artifact:**

- **Server crypto vector** (Prerequisite) — generated from `src/apps/mobile/crypto.py` +
  `legend_crypto.py`. Without it, `LegendCryptoTest`'s vector assertions stay `@Ignore`d and the
  engine ships unverified.

**Deferred / future work:**

- **Real NFC scanning** — wire `ScanScreen` to read the 16-byte `code` and call
  `LegendRepository.unlock(...)`; surface `Revealed`/`Unknown`/`Failed` in the scan UI. The marks
  feature owns flipping `taken`/scoring `point`.
- **`sync` manifest endpoint** (`/app/race/<id>/sync/`) — cheap-poll optimization, explicitly out of
  scope here; can be a separate task wiring `Kolco24App`'s refresh loop to compare `versions.*`
  before fetching.

**Manual verification:**

- on-device legend run: locked rows mask, open rows render, score sums known costs + shows «+N
  закрытых КП».
- upgrade test from a real v3 install to confirm `MIGRATION_3_4` preserves races/teams/checkpoints.
