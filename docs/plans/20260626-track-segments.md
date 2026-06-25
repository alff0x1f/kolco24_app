# Track Recording in Segments (start→stop = one segment)

## Overview

Today every GPS fix for a `(raceId, teamId)` lands in one flat `track_points`
table ordered by `elapsedRealtimeAt`. A stop→start gap is invisible: the two
recordings silently merge into one connected line — both in any on-device metric
and (once the upload endpoint lands) server-side, which would draw a straight
"teleport" line across the gap.

This change stamps every point with a **`segmentId`** UUID minted once per
recording session (per «Начать запись» tap). Stop→start produces two
`segmentId`s; the server groups by `(race_id, team_id, source_install_id,
segment_id)` and draws a separate polyline per segment, computing distance
per-segment. The wire contract for this is **already specified** in
`docs/design/UPLOAD.md` (`segment_id` is a per-**point** field, because one
opportunistic upload may carry points from several past sessions).

A second, smaller change rides along: the **«Длина» (length) metric is removed**
from `TrackCard`. It was the only consumer of the on-device length helper; the
server computes distance from `segment_id` itself, so the local length plumbing
becomes dead and is deleted.

## Context (from discovery)

Files/components involved:
- `data/db/TrackPointEntity.kt` — the Room row (local-only GPS point).
- `data/db/TrackDao.kt` — unchanged (segment rides per-row; no new query).
- `data/track/TrackModels.kt` — `RawFix`, `toTrackPoint` mapper, and the
  to-be-deleted `trackLengthMeters`/`haversineMeters`.
- `data/track/TrackRepository.kt` — `insertAll` owns the impure RawFix→entity map.
- `TrackRecordingService.kt` — recording lifecycle; owns the segment id minting.
- `data/api/dto/TrackDtos.kt` — `TrackPointDto` + `toDto()` wire mapper.
- `ui/track/TrackCard.kt`, `ui/team/TeamScreen.kt`, `MainActivity.kt` — length UI.
- `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/1.json` — committed schema.

Related patterns found:
- Client UUIDs (`id`) already drive idempotent merge/upsert — `segmentId` is the
  same philosophy at session granularity.
- Time fields (`trustedMs`/`bootCount`) and `id` are **injected at insert** by
  `TrackRepository`, not carried on the pure `RawFix`. `segmentId` follows suit.
- `startEngine` snapshots `raceId`/`teamId` into locals (`r`/`t`) so a later
  `onStartCommand` can't corrupt an in-flight engine's batch — `segmentId` gets
  the same local-snapshot treatment.
- Repo convention: pure logic is unit-tested; Android adapters and Compose are
  untested. DB is pinned at **v1 with zero migrations** (single dev install).

Dependencies identified:
- Schema change at v1 → regenerate `1.json`, reinstall/clear data on device.
  **No** version bump, **no** `MIGRATION_*` objects (documented policy).

## Development Approach

- **Testing approach**: Regular (code first, then tests) — matches repo
  convention; pure logic gets JVM tests, adapters/Compose stay untested.
- Complete each task fully (including tests) before the next.
- All tests must pass before starting the next task.
- Schema changed at v1 → the build will require a clean reinstall on device;
  unit tests use in-memory/fakes and are unaffected.

## Testing Strategy

- **Unit tests**: required per task where pure code changes.
  - `TrackPointMappingTest` — `toTrackPoint` carries the injected `segmentId`.
  - `TrackRepositoryTest` — `insertAll(..., segmentId)` stamps every row.
  - `TrackUploadTest` — `segment_id` serializes + round-trips entity→DTO.
- **Deletions**: `TrackMetricsTest` is deleted with the helpers it covers.
- **No e2e**: project has no UI e2e harness; Compose/service untested by
  convention (the service segment-minting is mechanical and adapter-level).

## Progress Tracking

- mark completed items `[x]` immediately when done.
- `➕` prefix for newly discovered tasks, `⚠️` for blockers.
- keep this file in sync with actual work.

## Solution Overview

`segmentId: String` (non-null) is added to `TrackPointEntity`. The recording
service mints one UUID per session in `onStartCommand`, holds it in a field,
snapshots it into each engine's `onPoints` lambda, and resets it on teardown.
`TrackRepository.insertAll` and the `RawFix.toTrackPoint` mapper gain a
`segmentId` param. `TrackPointDto` gains `@SerialName("segment_id")` and
`toDto()` passes it through. The Precise↔Economy profile soft-restart does **not**
re-mint (it never re-enters `onStartCommand`), so a battery-mode toggle mid-race
stays one segment.

Length removal is a straight deletion cascade: `TrackCard` drops the
`lengthMeters` param and «Длина» metric, `TeamScreen` and `MainActivity` drop the
plumbing, and `trackLengthMeters`/`haversineMeters`/`EARTH_RADIUS_M` +
`TrackMetricsTest` are deleted. `filterPoints`/`TrackPointLike`/`trackUsable`
**stay** (still feed the «Время» first/last span).

## Technical Details

The mint **decision** is extracted as a pure top-level helper so the stop→start /
idempotent-re-entry matrix is JVM-tested (repo convention: pure logic is always
unit-tested, like `decideBind`/`reduce`/`chooseEngineType`). The service holds
only the mutable field + engine plumbing (the untested adapter part):
```kotlin
// pure, in TrackRecordingService.kt (or a small sibling file), unit-tested:
fun nextSegmentId(current: String?, wasTearingDown: Boolean, mint: () -> String): String =
    if (wasTearingDown || current == null) mint() else current

private var segmentId: String? = null
// in onStartCommand, fresh-start path, after `isTearingDown = false`:
segmentId = nextSegmentId(segmentId, wasTearingDown) { UUID.randomUUID().toString() }
// in startEngine: snapshot into a local like r/t, pass into the onPoints lambda
val s = segmentId ?: UUID.randomUUID().toString().also { segmentId = it } // defensive
// onPoints: container.trackRepository.insertAll(fixes, r, t, s)
// in finishTeardown(): segmentId = null
```
- Mint only when `segmentId == null` → a duplicate start intent (idempotent
  re-entry) keeps the existing segment. A new session after a stop, or replacing
  an in-flight teardown (`wasTearingDown`), gets a fresh one.
- Profile restart calls `startEngine` with `segmentId` unchanged → same segment.

## What Goes Where

- **Implementation Steps** (`[ ]`): all code + test changes below.
- **Post-Completion** (no checkboxes): clean reinstall on device; server-side
  endpoint + `source_install_id` are separate future tasks.

## Implementation Steps

### Task 1: Add `segmentId` to the entity, mapper, and repository

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/db/TrackPointEntity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackPointMappingTest.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackRepositoryTest.kt`

- [x] add non-null `val segmentId: String` to `TrackPointEntity` (no index; keep
      it grouped near `id`/provenance fields; update the KDoc to mention the
      recording-session id)
- [x] `RawFix.toTrackPoint(...)`: add a `segmentId: String` param and set it on
      the returned entity (keep `RawFix` itself unchanged — pure geo value)
- [x] `TrackRepository.insertAll(...)`: add a `segmentId: String` param and thread
      it into every `toTrackPoint` call
- [x] `TrackPointMappingTest`: assert the passed `segmentId` lands on the mapped
      entity
- [x] `TrackRepositoryTest`: update the `insertAll` calls for the new param; add a
      case asserting every inserted row carries the passed `segmentId`
- [x] run `./gradlew testDebugUnitTest` — must pass before Task 2

### Task 2: Mint the segment id in the recording service

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/SegmentIdTest.kt`

- [x] add a pure top-level `nextSegmentId(current, wasTearingDown, mint)` helper
      (see Technical Details) — the testable mint decision
- [x] add `private var segmentId: String? = null` field
- [x] in `onStartCommand` fresh-start path: set
      `segmentId = nextSegmentId(segmentId, wasTearingDown) { UUID.randomUUID().toString() }`
- [x] in `startEngine`: snapshot `segmentId` into a local (alongside `r`/`t`,
      defensively minting if null) and pass it into the `insertAll` call inside
      the `onPoints` lambda
- [x] in `finishTeardown()`: reset `segmentId = null` so the next session mints a
      fresh one
- [x] verify the profile-switch path (`profileJob`/`flushThen`) does **not** touch
      `segmentId` — a mid-race Precise↔Economy toggle must stay one segment
- [x] write `SegmentIdTest` for `nextSegmentId`: fresh start (`null` → new id);
      idempotent re-entry (non-null, not tearing down → same id); teardown-in-flight
      replace (`wasTearingDown=true` → new id even when non-null)
- [x] run `./gradlew testDebugUnitTest` — must pass before Task 3
- [x] (the service field/engine plumbing stays untested — Android adapter, per
      convention; on-device smoke is in Post-Completion)

### Task 3: Carry `segment_id` on the upload wire

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/TrackDtos.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackUploadTest.kt`

- [x] add `@SerialName("segment_id") val segmentId: String` to `TrackPointDto`
      (place to match the `docs/design/UPLOAD.md` field order; update its KDoc)
- [x] `TrackPointEntity.toDto()`: pass `segmentId = segmentId` through
- [x] update the `TrackPointEntity(...)` fixtures in `TrackUploadTest` for the new
      constructor arg
- [x] add an assertion that `segment_id` serializes in the request JSON and
      round-trips through entity→DTO
- [x] run `./gradlew testDebugUnitTest` — must pass before Task 4

### Task 4: Remove the «Длина» metric and dead length plumbing

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/track/TrackCard.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackModels.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackRepositoryTest.kt`
- Delete: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackMetricsTest.kt`

- [x] `TrackCard`: drop the `lengthMeters` param; recording header becomes just
      «N точек» (remove `· ~Xм`); remove the «Длина» `Metric` from `TrackMetrics`;
      delete the private `formatLength`. Keep точек + время (span)
- [x] `TeamScreen`: drop the `trackLengthMeters` param and the `lengthMeters` arg
      passed to `TrackCard`
- [x] `MainActivity`: delete `val trackLength = trackLengthMeters(trackUsable)`,
      the `import ...trackLengthMeters`, and the `trackLengthMeters = trackLength`
      arg. **Keep** `trackUsable`/`filterPoints` (still feeds first/last time)
- [x] `TrackModels.kt`: delete `trackLengthMeters`, `haversineMeters`, and
      `EARTH_RADIUS_M`; keep `filterPoints`, `TrackPointLike`, `RawFix`, and the
      mapper (do **not** add `segmentId` to `TrackPointLike` — the segment-aware
      length fix was intentionally dropped). Also fix the `TrackPointLike` KDoc
      (`TrackModels.kt:40`) — drop its dangling `[trackLengthMeters]` reference
- [x] `TrackRepositoryTest`: delete `length_overObservedPoints_isCorrect()`
      (`:167-181`) — it calls the now-deleted `trackLengthMeters` and would break
      compilation (the `insertAll`-signature edits from Task 1 are unaffected)
- [x] delete `TrackMetricsTest.kt` (it only covers the removed helpers)
- [x] run `./gradlew testDebugUnitTest` — must pass before Task 5

### Task 5: Regenerate the v1 schema

**Files:**
- Modify: `app/schemas/ru.kolco24.kolco24.data.db.AppDatabase/1.json`

- [x] build so KSP regenerates the schema (`./gradlew assembleDebug` or
      `:app:kspDebugKotlin`), confirming `1.json`'s `track_points` now includes
      the `segmentId` column with no version bump and no new migrations
- [x] commit the regenerated `1.json` (committed in 4e55c7a alongside the entity change)
- [x] confirm no `MIGRATION_*` object or `.addMigrations(...)` was added

### Task 6: Verify acceptance criteria

- [x] stop→start mints two distinct `segmentId`s; profile toggle mid-record keeps
      one (verified by service code review — `nextSegmentId` mints on fresh-start
      in `onStartCommand`, reset to null in `finishTeardown`; `profileJob`/`flushThen`
      never touch `segmentId`, so a soft engine restart reuses it)
- [x] every persisted/uploaded point carries a non-null `segmentId` (`startEngine`
      snapshots `s = segmentId ?: mint`, threaded into every `insertAll`)
- [x] `TrackCard` shows точек + время, no «Длина»; no dead length code remains in
      main **or** test trees
      (`grep -rn "trackLengthMeters\|haversineMeters\|formatLength\|lengthMeters"
      app/src` returns nothing)
- [x] run full suite: `./gradlew testDebugUnitTest` (BUILD SUCCESSFUL)
- [x] run `./gradlew lintDebug` (BUILD SUCCESSFUL)

### Task 7: [Final] Update documentation

- [ ] update `CLAUDE.md` for the `segmentId` column + service minting + the
      removed length metric (the track bullets describe these in detail)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — informational only.*

**Manual verification:**
- Clean reinstall / clear app data on the dev device (schema changed at v1, an
  existing `user_version 1` with the old `track_points` shape would otherwise
  crash on open).
- On-device smoke: record → Стоп → Старт → Стоп produces points with two
  `segmentId`s; toggle Экономия батареи mid-record and confirm the segment id is
  unchanged across the soft engine restart.

**External system updates (out of scope here):**
- The `POST /app/race/<race_id>/track/` endpoint is still not implemented
  server-side; points stay `uploaded* = 0` until it lands. The server must group
  by `(race_id, team_id, source_install_id, segment_id)` per `docs/design/UPLOAD.md`.
- Per-batch `source_install_id` on `TrackUploadRequest` — specified in the wire
  contract but absent from the DTO; a separate future task, not part of this plan.
- No per-segment UI breakdown in `TrackCard` (YAGNI).
