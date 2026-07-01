# Photo-Mark Upload (Phase 2)

## Overview

Phase 1 shipped photo-as-fallback КП marking: a take can be confirmed by photo when NFC is
unavailable (tag torn off / no NFC hardware). Frames are captured, downscaled, stored on disk, and
scored locally — but **never uploaded**. Four `MarkDao` drain queries carry `AND method != 'photo'`
to keep them out of the upload path.

Phase 2 gets photo marks to the server. Two things travel:

1. **Take metadata** — rides the **existing** `POST /app/race/<race_id>/marks/` JSON (drop the
   `method != 'photo'` filter). The server gets the mark **row** that frames attach to.
2. **Binary frames** — a **new** endpoint, one request per frame, raw JPEG body.

The server photo endpoint 404s until the backend lands. That is a feature: `404` maps to
`PostResult.Error`, the upload flag stays `0`, and the drain self-heals on the next trigger —
identical to the existing marks/track "client-ready, backend-pending" pattern. This plan delivers
the client implementation **and** the documented wire contract.

## Context (from discovery)

- **Single-activity Compose app**, no ViewModel; Room is the single source of truth; dual-target
  (cloud HTTPS + LAN cleartext `192.168.1.5`) idempotent batch upload already exists for marks + track.
- Files/components involved:
  - `data/db/MarkEntity.kt` — take row; already has `photoPath: String?` (JSON list of relative frame paths), `method`, `uploadedLocal/Cloud`.
  - `data/db/MarkDao.kt` — 4 queries carry `AND method != 'photo'`; `attachPhotos`/`updatePhotoPath` column-scoped.
  - `data/db/AppDatabase.kt` — Room **v2**, `MIGRATION_1_2` live, `schemas/1.json`+`2.json` committed.
  - `data/MarkRepository.kt` — dual-target `flushScope` → per-target `uploadLoop`; `MarkUploader` fun-interface seam; `createPhotoMark`/`attachPhotos`.
  - `data/api/ApiClient.kt` — `internal post(url, bodyBytes, parse)` hardcodes `JSON_MEDIA_TYPE`; `uploadMarks` reuses it.
  - `data/api/dto/MarkDtos.kt` — `MarkEntity.toDto()` already maps `method="photo"`, empty `cp_code`/`cp_nfc_uid`, empty `present`.
  - `data/marks/PhotoPaths.kt` — pure codec `encodePhotoPaths`/`photoPaths`/`isSafeRelativePhotoPath`.
  - `data/marks/PhotoStorage.kt` — Android frame I/O adapter (`filesDir/marks/<markId>/<uuid>.jpg`).
  - `AppContainer.kt` — manual DI; builds cloud + `localApiClient`, repositories, `applicationScope`.
  - `docs/design/UPLOAD.md` — the marks/track contract (has a stub: "файл фото — отдельной задачей").
- Related patterns:
  - **Repo dual-target loop** — `Mutex.tryLock`-guarded, drains in `LIMIT` batches, marks only `accepted ∩ batch`, per-target flags, independent cloud/LAN.
  - **Signing** — `AppSignatureInterceptor` buffers the built request body and hashes it (`sha256_hex(body)`), so a raw-binary body is signed unchanged.
  - **Room-is-shipped** — a schema bump needs a real `Migration` + committed `schemas/<n>.json` or it crashes on upgrade.
  - **Pure/JVM-tested vs Android-adapter-untested** — keep new logic behind fun-interface seams so `MarkRepository` stays JVM-testable.
- Dependencies: no new libraries. CameraX/Coil already present from Phase 1.

## Development Approach

- **Testing approach**: Regular (code first, then tests) — matches this codebase's existing test style
  (pure logic + fakes for repositories, instrumented for DAO/migration).
- Complete each task fully before the next; small, focused changes.
- **Every task includes new/updated tests** (success + error/edge cases) as separate checklist items.
- **All tests pass before starting the next task.**
- Maintain backward compatibility: the migration is additive; legacy rows keep working.
- Update this plan file if scope changes during implementation.

## Testing Strategy

- **Unit (JVM, `app/src/test`)**: `MarkRepositoryUploadTest`, `ApiClientMarksTest`, `SigningTest`,
  `PhotoPathsTest`, `MarkDtoMappingTest`.
- **Instrumented (`app/src/androidTest`, needs emulator/device)**: `MigrationTest`, `MarkDaoTest`.
- No UI/e2e tests in this project — all upload logic is pure/DAO-level.
- Test commands: `./gradlew testDebugUnitTest` (JVM), `./gradlew connectedDebugAndroidTest`
  (instrumented), `./gradlew lintDebug` (must pass before merge).

## Progress Tracking

- Mark completed items `[x]` immediately.
- New tasks: ➕ prefix. Blockers: ⚠️ prefix.
- Keep the plan in sync with actual work.

## Solution Overview

- **Metadata via the existing tested path.** Dropping `method != 'photo'` lets standalone photo marks
  (`method="photo"`, `cp_code=""`, `cp_nfc_uid=""`, `present=[]`, `complete=true`) flow through
  `/marks/`. The server accepts them `verified=false` (empty `cp_code`) per the existing contract and
  stores the row so frames can attach. NFC-with-attached-photos marks already upload their metadata.
- **Frames via a new raw-binary endpoint**, one request per frame:
  `POST /app/race/<race_id>/mark/<mark_id>/photo/<frame_id>`, `Content-Type: image/jpeg`, body = raw
  downscaled JPEG bytes. `mark_id` = `MarkEntity.id`; `frame_id` = the `<uuid>` filename stem of the
  relative path (stable, unique, idempotency key). Server upserts by `(race, mark_id, frame_id)`.
- **Signing unchanged** — the interceptor hashes the JPEG bytes as the body. Same 6 headers, ±300s
  window, no admin bearer. Provenance via the `X-Install-Id` header + the linked mark row.
- **Per-mark flag tracking** — `photosUploadedLocal`/`photosUploadedCloud` columns. A mark's flag flips
  only when **all** its frames are accepted by that target.
- **Metadata-first ordering** — the frame drain is guarded on `uploadedX = 1` so the server always has
  the mark row before frames arrive.
- **Method-agnostic frame drain** — an NFC take can carry attached photos too, so the drain keys on
  "has frames + metadata landed", not `method='photo'`.
- **Dual-target, independent** — frames flush to cloud and LAN with separate flags/loops; a LAN failure
  never blocks cloud. Idempotent: re-sending an accepted frame is safe.

### Response codes (frame endpoint)

| Code | Meaning | Client | Drain scope |
|------|---------|--------|-------------|
| `200`/`201` | frame accepted (upsert) | mark's flag flips once all its frames accepted | continue |
| `404` | endpoint not deployed yet / mark row not landed | **self-heal** — leave `photosUploadedX=0`, retry next trigger | **stop target** (transient) |
| `403` | signature / time window | not auto-retried (POST); self-heal next trigger | **stop target** (transient) |
| `429` | rate limit | backoff, retry later | **stop target** (transient) |
| `5xx`/`Offline` | server / network | retry next trigger | **stop target** (transient) |
| `400`/`413` | unacceptable frame (unexpected for a ≤1600px JPEG) | log, mark stays pending (visible in status row) | **skip mark, continue** (hard per-frame) |
| — | offline / timeout | leave pending |

## Technical Details

- **New columns**: `photosUploadedLocal: Boolean = false`, `photosUploadedCloud: Boolean = false` on
  `MarkEntity` (SQLite `INTEGER NOT NULL DEFAULT 0`).
- **Migration**: `MIGRATION_2_3`, DB version 2→3, `schemas/3.json` committed.
- **`attachPhotos` correctness fix**: appending frames must **reset `photosUploaded*` to 0** (re-queue
  the frame drain) while still **not** resetting `uploaded*` (`photoPath` is not in the marks DTO).
- **Frame-drain query** (per target, method-agnostic):
  `SELECT * FROM marks WHERE raceId=:raceId AND teamId=:teamId AND uploadedCloud=1 AND photosUploadedCloud=0 AND photoPath IS NOT NULL LIMIT :limit`.
- **`pendingUploadScopes` widened** to include scopes with pending frames:
  `(uploadedLocal=0 OR uploadedCloud=0) OR (photoPath IS NOT NULL AND (photosUploadedLocal=0 OR photosUploadedCloud=0))`.
- **`uploadCounts` truthful**: a photo mark counts as uploaded for a target only when metadata **and**
  frames are done — `uploadedX AND (photoPath IS NULL OR photosUploadedX)`.
- **`ApiClient.uploadMarkPhoto(raceId, markId, frameId, bytes): PostResult<Unit>`** — generalize
  `post()` to take a `MediaType` (default `JSON_MEDIA_TYPE`); the frame call passes `image/jpeg` + a
  `{ Unit }` parser (empty `200` body).
- **Seams** (keep `MarkRepository` JVM-testable):
  - `PhotoFrameUploader` fun-interface `(raceId, markId, frameId, bytes) -> PostResult<Unit>` — two
    instances (cloud/local `ApiClient`), mirrors `MarkUploader`.
  - `PhotoFrameReader` fun-interface `(relPath) -> ByteArray?` — wired in `AppContainer` to
    `File(filesDir, relPath).readBytes()`; missing file → `null`.
  - Pure `frameIdOf(relPath): String` in `PhotoPaths.kt` (filename minus `.jpg`).
- **Frame drain in `MarkRepository.flushScope`** — runs **after** each target's metadata loop. Per
  target: fetch frame-pending marks (LIMIT), capture each mark's `updatedAt`; decode `photoPaths`; POST
  each frame. A non-`Success` POST splits **two ways** (this is the key control-flow rule):
  - **Transient / target-wide failure** (`Offline`, `403`, `404`, `429`, `401`, `409`, `5xx`, any other
    `Error`) → **stop the whole target** for this trigger and return `uploadResultKind(result)`, exactly
    like `uploadLoop` (MarkRepository.kt:349). No point issuing N doomed requests while the endpoint is
    down/404-pending.
    - **Hard per-frame failure** (`400 BadRequest`, `413` → `Error(413)`) → the frame is unacceptable and
    retrying won't help: **leave this mark pending, move on to the next mark** in the batch (don't let one
    poison frame block later good marks). A **null read** (missing/unreadable file) is treated the same —
    leave the mark pending, continue (proof photos: a visibly-pending mark beats silent remote loss).
  - When **all** of a mark's frames were accepted → `setPhotosUploadedXIfUnchanged(mark.id, updatedAt)`.
  - **Loop terminates on no-progress** (a pass that flips zero marks → stop, return `Error`), so a
    poison-only / missing-file-only batch stays visibly pending instead of spinning — same stuck-detection
    as `uploadLoop` (MarkRepository.kt:352).

  A tiny predicate `isHardFrameFailure(result) = result is BadRequest || (result is Error && result.code == 413)`
  draws the transient/hard line. The metadata result and frame result are then **combined into one**
  per-target outcome (`combineOutcome`, precedence **`Error` > `Offline` > `Ok` > `null`**) before a
  single `onUploadOutcome` call — a frame `Ok` must never mask a metadata `Error`/`Offline`.

### Known tradeoff (accepted — do not build around)

A **poison frame** (a genuine persistent `400`/`413` on one frame) or a **permanently-missing local
file** (killed mid-capture, then swept) keeps its mark's `photosUploaded*` at `0` forever — visible as
perpetually-pending in the upload status row, **not** silent. This is deliberate: for proof photos a
visibly-stuck mark is far better than a silently-"uploaded" mark the server never received. Acceptable
for a ≤1600px JPEG; log it rather than build per-frame skip-logic or a separate "dropped" state.
Documented, not engineered around.

## What Goes Where

- **Implementation Steps** (`[ ]`): schema/migration, DAO, DTO/mapper coverage, ApiClient binary POST,
  seams, repository frame drain, wiring, tests, docs.
- **Post-Completion** (no checkboxes): backend endpoint stand-up, on-device end-to-end verification
  once the server is live, memory update.

## Implementation Steps

### Task 1: Add `photosUploaded*` columns + MIGRATION_2_3

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/AppDatabase.kt`
- Create: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/3.json` (generated by the build)
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MigrationTest.kt`

- [x] Add `photosUploadedLocal: Boolean = false` and `photosUploadedCloud: Boolean = false` to `MarkEntity` (KDoc: per-mark frame-upload flags per target; default false; metadata-first ordering)
- [x] Bump `@Database(version = 3)` in `AppDatabase.kt`
- [x] Add `MIGRATION_2_3` (two `ALTER TABLE marks ADD COLUMN … INTEGER NOT NULL DEFAULT 0`) with KDoc; append to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`
- [x] Build to emit `schemas/3.json`; commit it
- [x] Extend `MigrationTest`: `MIGRATION_2_3` adds both columns with default 0 and preserves existing rows
- [x] Run `./gradlew assembleDebug` + `connectedDebugAndroidTest` (MigrationTest) — must pass before Task 2

### Task 2: Drop the `method != 'photo'` filter + frame-drain DAO queries

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MarkDaoTest.kt`

- [x] Remove `AND method != 'photo'` from `unuploadedLocal`, `unuploadedCloud`, and `pendingUploadScopes` — ⚠️ this puts photo rows (empty `cpUid`/`cpCode`/`present`) into the **shared** `/marks/` batch, so it depends on the server partial-accepting (returning `accepted[]` minus rejected rows, never a whole-batch `400` — else `uploadLoop` strands the batch's valid NFC marks too, per MarkDao.kt:128-131). Interim-safe because `/marks/` 404s until the backend lands; the contract requirement is pinned in Task 9 + Post-Completion. If the backend can't guarantee partial-accept, land this filter-drop **with** the backend rather than ahead of it.
- [x] Rewrite `uploadCounts` so a photo mark counts as uploaded for a target only when metadata AND frames done: `uploadedX AND (photoPath IS NULL OR photosUploadedX)` (keep no `method` filter)
- [x] Widen `pendingUploadScopes` to also return scopes with pending frames: `(uploadedLocal=0 OR uploadedCloud=0) OR (photoPath IS NOT NULL AND (photosUploadedLocal=0 OR photosUploadedCloud=0))`
- [x] Add `framePendingLocal(raceId, teamId, limit)` / `framePendingCloud(...)`: `… uploadedX=1 AND photosUploadedX=0 AND photoPath IS NOT NULL ORDER BY COALESCE(trustedTakenAt, takenAt), id LIMIT :limit`
- [x] Add **version-guarded** `setPhotosUploadedLocalIfUnchanged(id, updatedAt)` / `setPhotosUploadedCloudIfUnchanged(id, updatedAt)` (`UPDATE marks SET photosUploadedX = 1 WHERE id = :id AND updatedAt = :updatedAt`) — mirrors `markUploadedCloudIfUnchanged` so an `attachPhotos` that ran mid-drain (bumping `updatedAt`, resetting the flag to 0) can't be clobbered back to 1 with the newly-appended frame unsent (lost-update / stranded-frame race — see MarkDao.kt:162-180, MarkRepository.kt:304-327)
- [x] Update KDoc comments on the affected queries (Phase-1 filter rationale → Phase-2 behavior)
- [x] Extend `MarkDaoTest`: frame-pending filter (excludes `uploadedX=0`, `photosUploadedX=1`, and `photoPath IS NULL` rows), `setPhotosUploaded*IfUnchanged` (flips on matching `updatedAt`, no-ops on a stale one), a **zero-frame** `photoPath = "[]"` row is selected by `framePending` but terminates (flips without re-fetch loop), widened `pendingUploadScopes` (photo-frame-only scope returned), truthful `uploadCounts`
- [x] Run `connectedDebugAndroidTest` (MarkDaoTest) — skipped, no emulator/device available in this environment; `compileDebugAndroidTestKotlin` + `lintDebug` pass, and `testDebugUnitTest` (incl. updated `MarkRepositoryTest`/`MarkRepositoryUploadTest` fakes mirroring the new DAO contract) passes

### Task 3: Reset `photosUploaded*` on `attachPhotos`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/MarkDao.kt`
- Modify: `app/src/androidTest/java/ru/kolco24/kolco24/data/db/MarkDaoTest.kt`

- [x] Update `updatePhotoPath` to also set `photosUploadedLocal = 0, photosUploadedCloud = 0` (still NOT `uploaded*` — `photoPath` isn't in the DTO)
- [x] Update `attachPhotos` KDoc: appending frames re-queues the frame drain, metadata untouched
- [x] Extend `MarkDaoTest`: `attachPhotos` on a fully-frame-uploaded row resets `photosUploaded*` to 0 but leaves `uploaded*` intact
- [x] Run `connectedDebugAndroidTest` (MarkDaoTest) — skipped, no emulator/device available in this environment; `compileDebugAndroidTestKotlin` + `lintDebug` + `testDebugUnitTest` pass

### Task 4: `frameIdOf` pure helper

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/marks/PhotoPaths.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/marks/PhotoPathsTest.kt`

- [x] Add pure `frameIdOf(relPath: String): String` — the `<uuid>` filename stem (drop directory + `.jpg`)
- [x] Extend `PhotoPathsTest`: `frameIdOf` for a valid `marks/<markId>/<uuid>.jpg` path, and a defensive case (no extension / nested)
- [x] Run `./gradlew testDebugUnitTest` (PhotoPathsTest) — must pass before Task 5

### Task 5: `ApiClient.uploadMarkPhoto` (binary POST)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientMarksTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/SigningTest.kt`

- [x] Generalize the `internal post()` to accept a `mediaType: MediaType = JSON_MEDIA_TYPE`; body = `bodyBytes.toRequestBody(mediaType)` (existing callers default unchanged)
- [x] Add `IMAGE_JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()`
- [x] Add `uploadMarkPhoto(raceId, markId, frameId, bytes): PostResult<Unit>` → `post("$baseUrl/app/race/$raceId/mark/$markId/photo/$frameId", bytes, mediaType = IMAGE_JPEG_MEDIA_TYPE) { }` (Unit parser; note `404 → Error(404)` self-heal in KDoc)
- [x] Extend `ApiClientMarksTest` (MockWebServer): correct URL + method, `Content-Type: image/jpeg`, `200 → Success`, `404 → Error(404)`, `400 → BadRequest`, `429 → RateLimited`
- [x] Extend `SigningTest`: a binary-body request signs `sha256(jpeg)` as the body hash (canonical path includes `/mark/<id>/photo/<frame>`)
- [x] Run `./gradlew testDebugUnitTest` (ApiClientMarksTest, SigningTest) — must pass before Task 6

### Task 6: `PhotoFrameUploader` + `PhotoFrameReader` seams, repository frame drain

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MarkRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/MarkRepositoryUploadTest.kt`

- [x] Add `PhotoFrameUploader` fun-interface `(raceId, markId, frameId, bytes) -> PostResult<Unit>` and `PhotoFrameReader` fun-interface `(relPath) -> ByteArray?`; inject both into `MarkRepository`. **`PhotoFrameUploader` MUST default to `PostResult.Offline`** (never a success/no-op — a missed AppContainer wiring or bare fake must leave frames pending, not falsely mark them uploaded), mirroring `MarkUploader`'s `Offline` default (MarkRepository.kt:58). `PhotoFrameReader` defaults to `{ null }` (safe: a null read now keeps the mark pending — see below).
- [x] Add a private frame-drain loop (per target): fetch frame-pending marks (LIMIT), **capture each mark's `updatedAt`**, decode `photoPaths`, POST each frame. Split non-`Success` **two ways**: a **transient/target-wide failure** (`Offline`/`403`/`404`/`429`/`401`/`409`/`5xx`/other `Error`) **stops the whole target** and returns `uploadResultKind(result)` (no N doomed requests while the endpoint is down); a **hard per-frame failure** (`400`, or `413` → `Error(413)`) **or a null read** (missing file) leaves that mark pending and **continues to the next mark** (one poison frame must not block later good marks). Use `isHardFrameFailure(result) = result is BadRequest || (result is Error && result.code == 413)`. When **all** of a mark's frames were accepted call `setPhotosUploadedXIfUnchanged(mark.id, updatedAt)` (guard against an `attachPhotos` that raced the drain — stale `updatedAt` leaves the flag 0 so the next trigger re-drains, re-sending `f1`/`f2` idempotently and the new `f3`). **Loop terminates on no-progress** (a pass that flips zero marks → stop, return `Error`, so a poison/missing-only batch stays visibly pending instead of spinning — same stuck-detection as `uploadLoop`, MarkRepository.kt:352). Return `UploadResultKind?`.
- [x] **Combine the two per-target results into ONE `onUploadOutcome` call** (not two): `onUploadOutcome` is last-write-wins per `(scope, target)` (AppContainer.kt:212-216), so reporting metadata then frame separately would let a frame `Ok` mask a metadata `Error`/`Offline`. Add a pure `combineOutcome(metadata: UploadResultKind?, frame: UploadResultKind?): UploadResultKind?` with **deterministic** precedence **`Error` > `Offline` > `Ok` > `null`** (order fixed so the UI message can't depend on call order: `Error` and `Offline` are distinct status-row messages — `UploadResultKind { Ok, Offline, Error }`, TrackModels.kt:82). `Ok` only when both drains are clean; `null` only when neither attempted. Report the combined value once per target in `flushScope`. Keep cloud/LAN independent.
- [x] Extend `MarkRepositoryUploadTest` (fake uploader + fake reader): metadata-first ordering (no frame POST while `uploadedX=0`); all-frames-accepted flips `photosUploadedX`; **transient failure (`Offline`/`404`) stops the target after the first bad mark (later marks not attempted this trigger, retried next)**; **hard `400`/`413` on one mark leaves it pending but a later good mark still flips**; **missing-file frame keeps the mark pending (flag stays 0) and the drain terminates (no spin)**; dual-target independence (LAN offline, cloud still flips); `attachPhotos` re-queues frames; **an `attachPhotos` racing a mid-drain flip does not strand the new frame** (fake uploader bumps `updatedAt` between fetch and flip → guard no-ops → the drain's own re-fetch picks the mark back up and delivers the new frame); **combined outcome: metadata `Offline`/`Error` + frame `Ok` → final per-target outcome is NOT `Ok`**
- [x] Add a pure `combineOutcome` unit test covering **all 16 ordered `(metadata, frame)` combinations** of `{Error, Offline, Ok, null}` (assert the fixed `Error > Offline > Ok > null` precedence)
- [x] Run `./gradlew testDebugUnitTest` (MarkRepositoryUploadTest) — must pass before Task 7

### Task 7: Wire seams in AppContainer

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [x] Build `cloudPhotoUploader`/`localPhotoUploader` from the two `ApiClient` instances (`PhotoFrameUploader { r, m, f, b -> apiClient.uploadMarkPhoto(r, m, f, b) }`)
- [x] Build `photoFrameReader = PhotoFrameReader { rel -> runCatching { File(filesDir, rel).readBytes() }.getOrNull() }`
- [x] Pass all three into the `MarkRepository` constructor
- [x] Confirm no new `collectAsState`/scope introduced; frame drain rides the existing `uploadPending`/`uploadAllPending` triggers (Launch B + service live-upload)
- [x] Build `./gradlew assembleDebug` + `lintDebug` — must pass before Task 8

### Task 8: `MarkDtoMappingTest` coverage for photo marks

**Files:**
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/dto/MarkDtoMappingTest.kt`

- [x] Add/confirm a test: a `method="photo"` mark maps to a DTO with `method="photo"`, empty `cp_code`/`cp_nfc_uid`, empty `present`, `complete=true`, correct times/location (guards the metadata now flowing through `/marks/`)
- [x] Run `./gradlew testDebugUnitTest` (MarkDtoMappingTest) — must pass before Task 9

### Task 9: Document the contract in UPLOAD.md

**Files:**
- Modify: `docs/design/UPLOAD.md`

- [x] Replace the "сам файл фото — отдельной задачей (multipart-эндпоинт)" stub with a full frame-endpoint section: endpoint shape, `image/jpeg` body, signing note (body hash = `sha256(jpeg)`), idempotency by `(race, mark_id, frame_id)`
- [x] Add a response-code table row for the frame endpoint incl. the `404 → self-heal` semantics
- [x] Note that `method="photo"` metadata now flows through `/marks/` (filter dropped; `verified=false` on empty `cp_code`)
- [x] **Pin the `/marks/` partial-accept requirement**: the endpoint MUST return `accepted[]` excluding any rejected rows and MUST NOT whole-batch `400` on a photo row (a photo row now shares the batch with valid NFC marks; a whole-request `400` would strand them). State the interim-safety reasoning (filter-drop is a no-op while `/marks/` 404s)
- [x] No tests (docs only)

### Task 10: Verify acceptance criteria

- [x] Verify all Overview requirements: metadata via `/marks/` (filter dropped), frames via the new endpoint, per-mark dual-target flags, metadata-first ordering, `attachPhotos` re-queue, 404 self-heal — confirmed in code: `MarkDao.kt:130` drops the Phase-1 filter; `ApiClient.uploadMarkPhoto` (`ApiClient.kt:232`) posts to the frame endpoint with `404 → Error(404)` self-heal; `photosUploadedLocal/Cloud` columns + `framePendingLocal/Cloud` + `setPhotosUploaded*IfUnchanged` (`MarkDao.kt:160-217`) back per-mark dual-target flags; `MarkRepository.flushScope` runs `uploadLoop` before `frameDrainLoop` per target (metadata-first, `MarkRepository.kt:297-329`); `updatePhotoPath` resets `photosUploaded*` to 0 on `attachPhotos`
- [x] Verify edge cases: missing-file keeps mark pending, mid-mark failure retry, poison-frame stays visibly pending, combined outcome (frame `Ok` can't mask metadata `Error`), NFC-with-attached-photos frames also upload — confirmed via `MarkRepositoryUploadTest` coverage (missing-file/null-read pending, hard-failure skip-and-continue, transient-failure target stop, `combineOutcome` 16-combination precedence test) and `frameDrainLoop`/`isHardFrameFailure`/`combineOutcome` (`MarkRepository.kt:415-505`); the frame drain is method-agnostic (keys on `photoPath IS NOT NULL`, not `method='photo'`), so NFC-with-attached-photos marks drain identically
- [x] **Backend-contract gate before the filter-drop ships**: the `method != 'photo'` removal (Task 2) puts photo rows into the shared `/marks/` batch, so do **not** merge/release it until the `/marks/` backend is verified to **partial-accept** (returns `accepted[]` minus rejected rows, never a whole-batch `400` on a photo row — else valid NFC marks in the same batch strand). While `/marks/` still 404s the drop is inert, but the gate is: confirm the deployed contract before shipping. If the backend can't guarantee partial-accept, split Task 2's filter-drop into a follow-up that lands with the backend. — this is an external backend-verification gate, not a client-code action; pinned in `docs/design/UPLOAD.md` (Task 9) and in Post-Completion below. No backend exists yet to verify against, so this stays a documented, not-yet-satisfiable gate — not automatable from this repo.
- [x] Run full JVM suite: `./gradlew testDebugUnitTest` — passes
- [x] Run instrumented suite: `./gradlew connectedDebugAndroidTest` — skipped, no emulator/device available in this environment (consistent with Tasks 2/3)
- [x] Run `./gradlew lintDebug` — passes

### Task 11: [Final] Update docs & memory

**Files:**
- Modify: `CLAUDE.md`

- [ ] Update `MarkDao` bullets (Phase-1 `method != 'photo'` filter dropped → Phase-2 behavior; new frame-drain queries + `setPhotosUploaded*`; widened `pendingUploadScopes`; truthful `uploadCounts`)
- [ ] Update Room references: v2 → v3, add `MIGRATION_2_3` + `schemas/3.json`; update the `connectedDebugAndroidTest` guard line to mention the new migration/DAO guards
- [ ] Add `ApiClient.uploadMarkPhoto`, the `MarkRepository` frame loop, and the `PhotoFrameReader`/`PhotoFrameUploader` seams to the data-layer bullets
- [ ] Update the `photo-mark-fallback-plan` memory: Phase 2 implemented
- [ ] Move this plan to `docs/plans/completed/` (`mkdir -p docs/plans/completed`)

## Post-Completion

*Items requiring manual intervention or external systems — informational only.*

**Backend (external, blocks true end-to-end):**
- Stand up `POST /app/race/<race_id>/mark/<mark_id>/photo/<frame_id>` (raw `image/jpeg` body,
  signature validated identically to `/marks/`, upsert by `(race, mark_id, frame_id)`, `200/201` on
  accept). Until then the client leaves `photosUploaded*=0` and self-heals — no client change needed
  when it lands.
- Ensure `/marks/` accepts `method="photo"` rows (empty `cp_code` → `verified=false`, empty `present`)
  and persists the row so frames can attach. **Must partial-accept** — return `accepted[]` minus any
  rejected rows, never a whole-batch `400`: photo rows now share the batch with valid NFC marks, and a
  whole-request `400` would strand them (the client marks nothing unless the response is `Success`).
- Decide LAN-server photo handling (same endpoint on `192.168.1.5`) or leave it 404 — the client's
  LAN frame flag simply stays `0` until it answers.

**Manual verification (once backend is live):**
- On-device: capture a photo mark offline, go online, confirm metadata then frames upload to both
  targets and the status row reads "uploaded".
- Confirm an NFC take with attached photos uploads its frames.
- Confirm a mid-upload kill resumes cleanly (idempotent re-send).
