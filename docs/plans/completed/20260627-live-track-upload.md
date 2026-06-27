# Live GPS Track Upload During Recording

## Overview
- Flush the local GPS track to the server **periodically while a recording is active**, ~once per 10 minutes, so race organizers see teams move in near-real-time during a multi-hour race.
- Today the upload transport is fully built and tested, but uploads fire **only on stop** (`TrackRecordingService.finishTeardown` → `uploadPending`) and **on team switch** (`Kolco24App` → `uploadAllPending`). The missing piece is a trigger that fires *during* an active recording.
- Integrates by piggybacking on the existing fix-batch insert path in `TrackRecordingService` — no new timer, job, or `WorkManager`. A throttle caps the upload rate to once per 10 min regardless of fix cadence.

## Context (from discovery)
- **Files/components involved:**
  - `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt` — main change (the `onPoints` batch handler at line 197; `finishTeardown` at line 266; pure helper pattern `nextSegmentId` at line 43).
  - `app/src/main/java/ru/kolco24/kolco24/data/track/TrackRepository.kt` — `uploadPending(raceId, teamId)` (unchanged; reference only).
  - `app/src/test/java/ru/kolco24/kolco24/SegmentIdTest.kt` — the pure-helper test pattern to mirror.
- **Related patterns found:**
  - Pure top-level helper + JVM unit test (`nextSegmentId` / `SegmentIdTest`) — the convention for any new decision logic in this file.
  - `onPoints = { fixes -> container.applicationScope.launch { trackRepository.insertAll(fixes, r, t, s) } }` (lines 197–200) — the seam to extend.
  - Monotonic `SystemClock.elapsedRealtime()` is the app-wide time source for intervals/windows (scan window, `TrustedClock` anchor).
- **Dependencies identified:**
  - `TrackRepository.uploadPending` — already mutex-guarded (`tryLock`), dual-target (cloud + LAN), idempotent (client-id upsert), offline-tolerant (breaks cleanly), partial-accept aware (`uploadLoop`). No changes needed.
  - **Backend precondition (non-blocking):** `POST /app/race/<id>/track/` may not be live yet (code comment "until the backend endpoint lands"). Safe to ship regardless — a 404/error maps to `PostResult.Error` and breaks cleanly; points stay pending, no crash, no data loss.

## Development Approach
- **Testing approach:** Regular (code first, then tests) — but the only new *logic* is one pure function, which gets a dedicated test; the service wiring stays untested per repo convention (Android adapter).
- Complete each task fully before moving to the next; small focused changes.
- **Every code task includes tests.** The pure `shouldLiveUpload` helper is unit-tested (success + boundary + first-batch cases). The service-thread wiring is not unit-tested (repo convention: `TrackRecordingService` is an Android adapter, covered indirectly by `TrackRepositoryTest`/`LocationEngineFactoryTest`).
- All tests must pass before the next task. Maintain backward compatibility (stop + team-switch triggers stay intact).

## Testing Strategy
- **Unit tests:** new `LiveUploadThrottleTest` for `shouldLiveUpload` (delta boundaries `now=599_999,last=0` / `now=600_000,last=0` / `now=600_001,last=0`, and `lastUploadElapsed == null` first-batch).
- **e2e tests:** none — this project has no UI e2e harness; manual on-device verification is listed under Post-Completion.

## Progress Tracking
- Mark completed items `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- Keep this file in sync if scope changes.

## Solution Overview
- **Approach (Option C — throttled piggyback):** in the existing `onPoints` batch handler, decide on the (serialized) location-callback thread whether enough time has elapsed since the last live upload; if so, after the batch insert, call `uploadPending(r, t)` inside the same `applicationScope.launch`.
- **Why this over a dedicated timer / WorkManager:**
  - Piggybacking on the GPS-delivery wake adds **no extra device wakeups** — the CPU is already awake processing the batch. A standalone timer would wake the device at unrelated moments.
  - Cadence self-aligns with the battery profile: Precise (~60 s batches) fires ~every 10 min; Economy (~180 s batches) fires on the batch crossing 600 s, ~every 12 min — one constant, no per-profile config.
  - The in-recording trigger **is** the retry mechanism: each 10-min tick re-attempts, and `uploadLoop` drains everything accumulated (in 500-point batches) when coverage returns. No backoff/`WorkManager` needed.
  - `WorkManager`'s 15-min minimum periodic interval is too coarse for live tracking, and recording is already a foreground service living the whole race.
- **Battery:** 10-min cadence + piggybacked wake makes the cellular radio-tail cost negligible on top of continuous GPS. User explicitly chose 10 min.

## Technical Details
- **New constant:** `const val LIVE_UPLOAD_MIN_INTERVAL_MS = 600_000L` (10 min), top-level in `TrackRecordingService.kt`.
- **New pure helper (nullable sentinel):** `fun shouldLiveUpload(nowElapsed: Long, lastUploadElapsed: Long?, minIntervalMs: Long): Boolean = lastUploadElapsed == null || nowElapsed - lastUploadElapsed >= minIntervalMs` (mirrors `nextSegmentId`). **`null` = "never uploaded this session" → always true**, so the first batch fires regardless of how long the device has been booted. A `0L` sentinel would be wrong: `elapsedRealtime()` is time-since-boot, so a recording started within 10 min of a reboot (`now < 600_000`) would *not* fire on the first batch — the nullable sentinel avoids that and is overflow-safe (no `now - Long.MIN_VALUE`).
- **New field:** `@Volatile var lastLiveUploadElapsed: Long? = null` on the service. `null` until the first live upload, so the **first batch of every session uploads immediately** (team-is-live signal + early connectivity validation), then settles to the 10-min cadence.
- **Wiring** in `onPoints` (inside `private fun startEngine`, current lines 197–200):
  ```kotlin
  onPoints = { fixes ->
      val now = SystemClock.elapsedRealtime()
      val doUpload = shouldLiveUpload(now, lastLiveUploadElapsed, LIVE_UPLOAD_MIN_INTERVAL_MS)
      if (doUpload) lastLiveUploadElapsed = now
      container.applicationScope.launch {
          container.trackRepository.insertAll(fixes, r, t, s)
          if (doUpload) container.trackRepository.uploadPending(r, t)
      }
  },
  ```
  Engine callbacks land on `Looper.getMainLooper()` (both `FusedLocationEngine`/`LegacyLocationEngine`), and `finishTeardown` runs on `mainHandler` — so the field's read, write, and reset all serialize on the main thread; `@Volatile` is harmless defensive overkill, consistent with the file's style. The upload runs after `insertAll` in `applicationScope`; `uploadPending`'s `tryLock` guards against overlap with the team-switch trigger.
- **Field lifetime — persists across `startEngine` restarts:** `lastLiveUploadElapsed` is a **service field**, not reset in `startEngine`. `startEngine`/`onPoints` are re-invoked on every Precise↔Economy profile soft-restart, so a mid-race profile toggle must **not** reset the 10-min throttle.
- **Reset — two sites, mirroring `segmentId`'s lifecycle (one per new *logical* session):**
  1. `finishTeardown()` (near `segmentId = null`, line 269): add `lastLiveUploadElapsed = null` — the normal stop path.
  2. `onStartCommand`, on the **fresh-start path**, reset it on the **same predicate `nextSegmentId` uses to mint** (`wasTearingDown || segmentId == null`, evaluated **before** the `segmentId = nextSegmentId(...)` assignment). **Why both:** on a rapid stop→start the old session's `teardown()`→`flushThen` callback returns early at `if (engine !== e) return@flushThen` (line 238) **before** reaching `finishTeardown()` — the new session's `startEngine` already replaced `engine`, so reset site #1 is skipped and the new session would inherit the stale throttle (its first batch would not upload immediately). `nextSegmentId` already treats `wasTearingDown` as a new logical session and mints a fresh `segmentId`; tying the throttle reset to the exact same condition keeps them in lockstep. **Do not reset on idempotent re-entry** (a duplicate start intent where `nextSegmentId` keeps the existing `segmentId`) — that is the same logical session and must keep its 10-min throttle.
- **Targets:** both cloud + LAN stay on (`uploadPending` does both independently). LAN fails fast (~3 s) in the forest — negligible — and catches up if a team passes the event Wi-Fi.

## What Goes Where
- **Implementation Steps** (`[ ]`): the constant, helper, field, wiring, reset, and the unit test — all in this repo.
- **Post-Completion** (no checkboxes): verifying the backend endpoint is live (the real gate on *seeing* live data), and on-device battery/real-coverage sanity checks.

## Implementation Steps

### Task 1: Add `shouldLiveUpload` pure helper + constant

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/LiveUploadThrottleTest.kt`

- [x] add top-level `const val LIVE_UPLOAD_MIN_INTERVAL_MS = 600_000L` near `nextSegmentId` (line 43), with a KDoc noting the 10-min cadence and that it applies to both profiles
- [x] add top-level pure `fun shouldLiveUpload(nowElapsed: Long, lastUploadElapsed: Long?, minIntervalMs: Long): Boolean` with the **nullable** sentinel (`null` → true); mirror `nextSegmentId` style/KDoc
- [x] create `LiveUploadThrottleTest.kt` mirroring `SegmentIdTest.kt` structure/package
- [x] write tests (boundaries are on the **delta** `nowElapsed - lastUploadElapsed`, so hold `last` fixed and vary `now`): `now=599_999, last=0` → false, `now=600_000, last=0` → true, `now=600_001, last=0` → true
- [x] write test: `lastUploadElapsed = null` → true regardless of `now` (first-batch-fires)
- [x] write test: `lastUploadElapsed = null` with a **small** `now` (e.g. `5_000`, just-booted) → true (guards the reboot edge a `0L` sentinel would break)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 2: Wire throttled live upload into the recording service

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`

- [x] add `import android.os.SystemClock` (not currently imported in this file)
- [x] add `@Volatile var lastLiveUploadElapsed: Long? = null` field (near `segmentId`, line 80), with a KDoc noting it persists across `startEngine` restarts and is reset on each new logical session (the same lifecycle as `segmentId`)
- [x] in `onPoints` (inside `startEngine`, lines 197–200): compute `now`/`doUpload` and write `lastLiveUploadElapsed` before the `launch`; call `container.trackRepository.uploadPending(r, t)` after `insertAll` when `doUpload`
- [x] in `onStartCommand` fresh-start path: reset `lastLiveUploadElapsed = null` when `wasTearingDown || segmentId == null` (evaluate **before** the `segmentId = nextSegmentId(...)` line at 117), so a rapid stop→start whose old `finishTeardown` is skipped (the `engine !== e` early return at line 238) still re-uploads its first batch; do **not** reset on idempotent re-entry
- [x] in `finishTeardown()` (near line 269): reset `lastLiveUploadElapsed = null`
- [x] confirm no change needed in `TrackRepository.uploadPending` (already mutex-guarded/dual-target/offline-tolerant) — reference read only
- [x] (no new unit test — service is an Android adapter per repo convention; logic covered by Task 1's `LiveUploadThrottleTest`)
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: Verify acceptance criteria
- [x] confirm uploads now fire: immediately on first batch, then ≥10 min apart during recording; still on stop (`finishTeardown`) and team switch (`Kolco24App`)
- [x] confirm offline path is harmless: a non-Success `uploadPending` leaves points pending, no crash (relies on existing `uploadLoop` break-on-no-progress)
- [x] run full unit suite: `./gradlew testDebugUnitTest`
- [x] run `./gradlew lintDebug`
- [x] run `./gradlew assembleDebug`

### Task 4: [Final] Update documentation
- [x] update `CLAUDE.md` `TrackRecordingService` bullet to note the in-recording throttled live-upload trigger (`LIVE_UPLOAD_MIN_INTERVAL_MS` / `shouldLiveUpload`, first-batch-immediate, reset in `finishTeardown`)
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only*

**External system updates:**
- **Backend endpoint:** verify `POST /app/race/<id>/track/` is deployed and accepts the batch (`segment_id`, `trusted_ms`/`boot_count` nullable). Until then this client change is inert-but-safe: points accumulate and back-fill once the endpoint is live. This is the real gate on organizers *seeing* live positions — not a blocker for merging.

**Manual verification:**
- On-device: start a recording, confirm via server/logs that the first batch posts immediately and subsequent posts are ~10 min apart in Precise and ~12 min in Economy.
- Coverage drop: simulate offline mid-race (airplane mode), confirm points accumulate and the next in-coverage trigger back-fills the gap (in 500-point batches) with no duplicates.
- Battery sanity: a multi-hour Precise recording shows the upload adds negligible drain on top of GPS.
