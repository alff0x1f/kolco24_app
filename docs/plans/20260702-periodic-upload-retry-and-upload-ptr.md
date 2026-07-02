# Periodic upload retry (foreground) + pull-to-refresh on «Загрузка данных»

## Overview
- **Problem:** upload attempts currently fire only at app launch / team switch (`Kolco24App` Launch B), on scan-overlay close (`closeScanOverlay`, `MainActivity.kt:1226` — marks only), and every 10 min *while track recording is active* (`TrackRecordingService`). An app sitting open without a recording never retries a failed upload — the «Загрузка данных» screen shows «12 минут назад сервер недоступен» with no recovery until restart.
- **Fix 1:** a 5-minute foreground-only retry loop in `MainActivity` that fires `trackRepository.uploadAllPending()` + `markRepository.uploadAllPending()` (covers marks metadata, photo frame drain, and track points, all scopes, both targets).
- **Fix 2:** pull-to-refresh on `UploadScreen` triggering the same pair of calls, spinner until the attempt completes. Feels natural to the user because the receipt lines («N мин назад · …») update live.
- No data-layer changes: both features are pure wiring over existing `uploadAllPending()`; the repos' `Mutex.tryLock` guard makes every overlap (service 10-min loop, Launch B, take-complete flush, PTR, timer) safe — a colliding attempt is skipped, and an empty tick makes no network calls.

## Context (from discovery)
- `MainActivity.kt:533` — existing 5 s `trustedClock.recomputeStatus()` ticker (plain `LaunchedEffect(Unit)`); the new loop sits next to it but must be lifecycle-gated (a plain `LaunchedEffect` keeps running while the Activity is backgrounded in the backstack).
- `MainActivity.kt:482` — `showUpload` flag; `MainActivity.kt:1445-1450` — `UploadScreen(...)` call site.
- `ui/upload/UploadScreen.kt` — stateless; `Column { TopAppBar; if (hasAny) Column(verticalScroll) { 3× UploadSection } else UploadEmptyState }`; 30 s `nowMs` `produceState` ticker already refreshes the relative-time labels.
- `ui/common/PullToRefresh.kt` — shared `RefreshableList(isRefreshing, onRefresh, content)` (OrangeCta indicator, host owns the flag), designed to wrap the scrollable body *below* the `TopAppBar`. Precedent: `LegendScreen.kt:104-114` wraps only the list branch (`LazyColumn(Modifier.fillMaxSize())`) and deliberately does **not** offer the gesture on its empty state.
- `MainActivity.kt:510-511` — local vals `markRepo`/`trackRepo` already exist (used by the `closeScanOverlay` flush); the new call sites should use them, not `container.…`.
- `relativeTimeRu` (`data/track/PointsPlural.kt:19`) clamps a negative delta to «только что» — a fresh outcome with a ≤30 s-stale `nowMs` renders correctly, no change needed.
- `lifecycle-runtime-ktx` 2.6.1 is an explicit dependency (`app/build.gradle.kts:128`) — `repeatOnLifecycle` (since 2.4.0) is available, no build change.

## Development Approach
- **Testing approach:** Regular. Per project convention (CLAUDE.md), Compose UI and trivial wiring are **untested** — no pure logic is introduced by this plan, so **no new unit tests**. The required deliverable per task is instead: `./gradlew lintDebug` and `./gradlew testDebugUnitTest` must pass (no regressions).
- Complete each task fully before moving to the next.
- Update this plan file when scope changes during implementation (`➕` new tasks, `⚠️` blockers, `[x]` immediately on completion).

## Testing Strategy
- No new unit tests (wiring only, untested-by-convention). Existing suites must stay green.
- Verification per task: `./gradlew lintDebug` + `./gradlew testDebugUnitTest`.
- Manual verification scenarios listed under Post-Completion.

## Solution Overview
- **Timer:** `LaunchedEffect(Unit) { lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { fire; delay(5 min) } } }`. `repeatOnLifecycle` cancels the block on background and restarts it on return to foreground — so returning to the app fires an immediate attempt (a feature, not a bug). The attempts themselves are launched on `container.applicationScope` (fire-and-forget, «writes outlive overlays» idiom) so backgrounding mid-batch doesn't cancel an in-flight upload.
- **PTR:** `UploadScreen` stays stateless — gains `refreshing: Boolean` + `onRefresh: () -> Unit`; **only the `hasAny` branch** is wrapped in `RefreshableList` (mirrors `LegendScreen` — the empty state has nothing to upload by construction, so the gesture isn't offered there). The host owns a transient `uploadRefreshing` flag (`remember`, not `rememberSaveable` — a spinner is not state worth restoring) and clears it after both `uploadAllPending()` calls complete.
- **No snackbars for PTR outcome** — unlike the tabs' `refreshErrorMessage`, the outcome *is* this screen's content (receipt lines update via the existing outcome flows).
- **Expected spinner blip on collision:** if another trigger (5-min loop, Launch B, service flush) already holds a repo's mutex, the PTR's `uploadAllPending()` returns near-instantly having done nothing — the spinner blips and the receipt lines don't change. Not a bug: the real work is already in flight elsewhere.

## Technical Details
- `UPLOAD_RETRY_INTERVAL_MS = 300_000L` — top-level constant in `MainActivity.kt` (mirrors `LIVE_UPLOAD_MIN_INTERVAL_MS` placement in `TrackRecordingService.kt`).
- Imports for the loop: `androidx.lifecycle.Lifecycle`, `androidx.lifecycle.repeatOnLifecycle`, `androidx.compose.ui.platform.LocalLifecycleOwner` (the ui-platform variant matches lifecycle 2.6.1; the `androidx.lifecycle.compose` home arrived in 2.8).
- PTR host body — clear the flag even if a repo throws:
  ```kotlin
  if (!uploadRefreshing) {
      uploadRefreshing = true
      container.applicationScope.launch {
          try {
              coroutineScope {
                  launch { trackRepo.uploadAllPending() }
                  launch { markRepo.uploadAllPending() }
              }
          } finally {
              uploadRefreshing = false
          }
      }
  }
  ```
- The `hasAny` inner scroll `Column` must get `Modifier.fillMaxSize().verticalScroll(...)` — `RefreshableList` is a `PullToRefreshBox` that only expands to its content's height; without `fillMaxSize` short content would confine the gesture area/indicator to the content bounds (working precedent: `LegendScreen.kt:107-113`).

## What Goes Where
- **Implementation Steps:** code changes in `MainActivity.kt`, `ui/upload/UploadScreen.kt`; CLAUDE.md doc updates.
- **Post-Completion:** manual on-device verification (no emulator-checkable behavior worth an instrumented test).

## Implementation Steps

### Task 1: 5-minute foreground retry loop in MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add top-level `const val UPLOAD_RETRY_INTERVAL_MS = 300_000L` with a short comment (foreground retry cadence; recording already has the service's 10-min loop)
- [x] next to the 5 s clock ticker (`MainActivity.kt:533`), add the `LaunchedEffect(Unit)` + `repeatOnLifecycle(Lifecycle.State.STARTED)` loop: each tick launches `trackRepo.uploadAllPending()` and `markRepo.uploadAllPending()` on `container.applicationScope` (two separate `launch`es; `trackRepo`/`markRepo` are the existing local vals at `:510-511`), then `delay(UPLOAD_RETRY_INTERVAL_MS)`
- [x] resolve `lifecycleOwner` via `LocalLifecycleOwner.current`; add the three imports
- [x] comment: attempt-before-delay is deliberate (return-to-foreground = immediate retry); overlap with Launch B / service loop / take-flush is safe via the repos' `tryLock`
- [x] run `./gradlew lintDebug` and `./gradlew testDebugUnitTest` — must pass before task 2

### Task 2: UploadScreen gains pull-to-refresh (stateless params + RefreshableList)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/upload/UploadScreen.kt`

- [ ] add `refreshing: Boolean` and `onRefresh: () -> Unit` params to `UploadScreen` (before `onBack`)
- [ ] wrap **only the `hasAny` branch** in `RefreshableList(isRefreshing = refreshing, onRefresh = onRefresh)` from `ui/common/PullToRefresh.kt`; the `UploadEmptyState` branch stays as-is (no gesture — nothing to upload by construction, mirrors `LegendScreen.kt:104-114`)
- [ ] give the inner scroll `Column` `Modifier.fillMaxSize().verticalScroll(rememberScrollState())` so the `PullToRefreshBox` fills the viewport below the `TopAppBar` even with short content
- [ ] update the file's KDoc header (screen now hosts PTR on the content branch; host owns the flag; outcome surfaces via the receipt lines, no snackbar)
- [ ] run `./gradlew lintDebug` and `./gradlew testDebugUnitTest` — must pass before task 3

### Task 3: Host wiring — uploadRefreshing in MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] add `var uploadRefreshing by remember { mutableStateOf(false) }` near `showUpload` (`MainActivity.kt:482`); `remember`, not `rememberSaveable` — transient spinner state
- [ ] at the `UploadScreen` call site (`MainActivity.kt:1446`), pass `refreshing = uploadRefreshing` and the `onRefresh` lambda from Technical Details (guard on `!uploadRefreshing`, `applicationScope.launch` + `coroutineScope` of two child launches, `finally { uploadRefreshing = false }`)
- [ ] run `./gradlew lintDebug` and `./gradlew testDebugUnitTest` — must pass before task 4

### Task 4: Verify acceptance criteria
- [ ] loop is lifecycle-gated (block body cancelled when the Activity leaves STARTED; restarts and fires immediately on return)
- [ ] PTR spinner spins until both `uploadAllPending()` calls complete; a near-instant blip when the mutex skips (another trigger already draining) is expected behavior, not a bug
- [ ] no data-layer or DB changes crept in (no migration concerns)
- [ ] full check: `./gradlew lintDebug testDebugUnitTest`

### Task 5: [Final] Update documentation
- [ ] CLAUDE.md — MainActivity bullet (Cross-file wiring): add the 5-min `repeatOnLifecycle(STARTED)` retry loop next to the existing 5 s clock-ticker mention, and the `uploadRefreshing` PTR host wiring
- [ ] CLAUDE.md — `ui/upload/UploadScreen.kt` bullet: note the hoisted `refreshing`/`onRefresh` + `RefreshableList` wrap
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual on-device verification:*
- with the server unreachable, take a mark → open «Загрузка данных» → see the failure receipt; restore connectivity → within ≤5 min the counters/labels flip to uploaded without any user action
- swipe down on «Загрузка данных» with data present → spinner runs for the attempt duration, labels show «только что · …» afterwards (near-instant blip = mutex skip, expected); empty state deliberately offers no gesture
- background the app mid-outage → verify no upload attempts happen while backgrounded (e.g. via server logs / Charles), and an attempt fires immediately on return
- start a track recording → confirm no visible conflict between the service's 10-min loop and the 5-min activity loop (skipped attempts are silent)
