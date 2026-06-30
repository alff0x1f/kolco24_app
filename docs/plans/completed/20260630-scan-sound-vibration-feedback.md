# Scan Sound + Vibration Feedback (success / failure / neutral)

## Overview
Replace the stock Android NFC discovery beep with custom, app-controlled feedback that
distinguishes the outcome of every tap, using a short sound plus (for the two strong
outcomes) a matching vibration.

- **Problem:** the default Android NFC beep is generic — a КП chip, a bracelet, an
  unbound/unknown chip, and a crypto failure all sound identical. And once we suppress the
  platform beep app-wide (required, see below), every tap the app does **not** classify into
  a strong outcome would go completely silent, so the user loses even the "I read a tag"
  acknowledgement.
- **Solution:** suppress the platform beep app-wide (reader-mode flag) and play one of
  **three** outcomes — `Success` (crisp tick + short buzz), `Failure` (error tone + double
  buzz), or `Neutral` (a soft "tap registered" tick, no vibration) — at every scan point
  across the app, including the previously-silent "unknown tag / not actionable" paths.
- **Integration:** a single `ScanFeedbackPlayer` adapter owned by `AppContainer` (manual DI),
  driven by a pure `feedbackFor(ScanEvent)` mapper for the scan overlay and by direct
  `success()`/`failure()`/`neutral()` calls at the bind/check/provision/idle branch points.
  Reuses v1's mp3 assets.

**On "success" semantics:** Success means **"a valid, recognized chip was read"**, not "state
changed". An idempotent repeat tap of an already-credited member (which does not advance the
scan window — `ScanSession.kt`/`MainActivity.kt:1231`) still plays Success: from the user's
point of view "yes, your chip was read" is the right signal, and the brainstorm explicitly
chose to beep repeat member taps. This is a deliberate definition, called out to avoid the
"did something useful" ambiguity.

## Context (from discovery)
- **Files/components involved:**
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — `READER_FLAGS` (line ~357);
    the **idle `onTagDiscovered`** unknown-tag drop at line ~348
    (`if (code == null && uid !in boundUidsSnapshot) return`); threads the adapter down to
    overlays (same pattern as theme/economy).
  - `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanSession.kt` — `ScanEvent` model
    (`Kp`/`Member`/`UnboundChip`/`BadKp`); home of the new pure mapper.
  - `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt` — `process()` at line ~138
    (`val event = currentOnScanTag(...)` inside `scanMutex`) is the single scan-overlay
    chokepoint covering every tap path incl. idempotent re-taps.
  - `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` — manual DI; owns the new adapter
    (`AppContainer(private val context: Context)` is available for SoundPool/Vibrator).
  - `app/src/main/java/ru/kolco24/kolco24/ui/team/BindChipSheet.kt` — `decideBind` result;
    `AlreadyBound` is documented "warn + allow" (line ~72).
  - `app/src/main/java/ru/kolco24/kolco24/ui/admin/CheckChipScreen.kt` — `classifyChipCheck`;
    a `!dataReadyLatest.value → return` not-ready guard at line ~171.
  - `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt` — the bind **job**
    sets the terminal `ProvisionState.Success(...)` / `.Failed(...)` (lines ~268-288); an
    existing `LaunchedEffect(provisionState)` haptic at lines ~200-204; mid-swipe / busy taps
    are dropped at line ~230 (`pagerState.isScrollInProgress` / `isBusy`).
  - v1 assets: `/Users/alff0x1f/src/kolco24_app/app/src/main/res/raw/beep_ok3.mp3`,
    `beep_err.mp3`, `beep_scan.mp3`.
- **Related patterns found:**
  - Pure-model + Android-adapter seam (pure parts unit-tested, adapters untested by
    convention).
  - Overlay params threaded from `AppContainer` → `MainActivity` → overlays; no second
    `collectAsState`.
  - "Duplicate, don't couple" — small repeated call sites are copied, not abstracted.
- **Dependencies identified:** none new — `SoundPool`, `Vibrator`/`VibratorManager`,
  `NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS` are all platform APIs.

## Development Approach
- **Testing approach:** Regular (code first, then test) — only the pure `feedbackFor` mapper
  is unit-tested, per the project's pure-vs-adapter convention. The `ScanFeedbackPlayer`
  adapter (SoundPool/Vibrator) is an Android adapter and is **untested by convention**.
- complete each task fully before moving to the next.
- make small, focused changes; keep the three-outcome scope (no "complete" flourish).
- **every task with pure-code changes includes its test** (here: the mapper).
- all tests + `lintDebug` must pass before starting the next task.
- run tests after each change; maintain backward compatibility.

## Testing Strategy
- **unit tests:** `ScanFeedbackTest` (JVM) over `feedbackFor(ScanEvent)` — all four event
  variants mapped to the correct `ScanFeedbackKind`.
- **adapter:** `ScanFeedbackPlayer` (SoundPool + Vibrator) is untested by convention (Android
  adapter, like the location engines / NfcA adapters).
- **e2e:** project has no UI e2e harness — N/A. Manual device verification is in
  Post-Completion.
- **lint:** `lintDebug` must pass — watch `NewApi` on `VibrationEffect`/`VibratorManager`
  (guard every call by `Build.VERSION.SDK_INT`).

## Progress Tracking
- mark completed items `[x]` immediately when done.
- add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- update this plan if scope changes during implementation.

## Solution Overview
- **Naming:** the pure mapper lives in `ScanFeedback.kt` (`ScanFeedbackKind` +
  `feedbackFor`); the Android adapter class is `ScanFeedbackPlayer`. "Adapter" always means
  `ScanFeedbackPlayer` below, never the mapper file.
- **Three outcomes:** `ScanFeedbackKind { Success, Failure, Neutral }`.

  | Outcome | Sound | Vibration | When |
  |---|---|---|---|
  | `Success` | `beep_ok3` | short pulse (`0,40`) | recognized КП/member; bind ok; check ok; provision written |
  | `Failure` | `beep_err` | double buzz (`0,60,80,60`) | `BadKp`, `UnboundChip`, `NotInPool`, check unknown/inconsistent/no-code, provision/server error |
  | `Neutral` | `beep_scan` | none | unknown idle tag; `AlreadyBound` (warn+allow); check tapped before data ready |

- **Pure mapper** `feedbackFor(event: ScanEvent): ScanFeedbackKind`
  (`Kp`/`Member` → Success; `UnboundChip`/`BadKp` → Failure) lives beside `ScanSession.kt`
  and is unit-tested. It only covers the four scan-overlay events; `Neutral` is produced
  exclusively by direct calls at the non-overlay paths below (so the mapper stays a clean
  2-way over `ScanEvent`).
- **Android adapter** `ScanFeedbackPlayer` owned by `AppContainer`: loads `beep_ok3` +
  `beep_err` + `beep_scan` into a `SoundPool` once at construction; resolves the system
  `Vibrator`. Exposes `success()`, `failure()`, `neutral()`, and `play(kind)`. Thread-safe →
  callable from the NFC binder thread and from coroutines.
- **Platform beep killed** app-wide via `FLAG_READER_NO_PLATFORM_SOUNDS` on `READER_FLAGS`.
- **Silent paths — decided now (Codex finding 5), not deferred:**
  - Unknown idle tag (`MainActivity` ~348) → **Neutral** (the headline new cue).
  - Check-chip tapped before legend data is ready (`CheckChipScreen` ~171) → **Neutral**.
  - Provisioning tap during a pager swipe / while a job is busy (`ProvisioningScreen` ~230) →
    **deliberately silent** — a transient gesture/concurrency conflict; a cue there would be
    noise. Documented, not an oversight.
  - Recognized chip with no team selected (routes to the team picker) → stays silent; the
    on-screen navigation is the feedback. Noted in the manual checklist.
- **Trigger points:**
  - Scan overlay: `ScanScreen.process()` after the `currentOnScanTag(...)` result →
    `scanFeedback.play(feedbackFor(event))`.
  - Idle / bind / check / provision: direct `success()`/`failure()`/`neutral()` calls at
    their existing branch points (these paths don't share `ScanEvent`, so no shared mapper —
    "duplicate, don't couple").

## Technical Details
- **Assets:** copy `beep_ok3.mp3` (success, ~2.3 KB), `beep_err.mp3` (failure buzz), and
  `beep_scan.mp3` (neutral tick) into `app/src/main/res/raw/`.
- **SoundPool:** `AudioAttributes` `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION`,
  `maxStreams = 2`; `load()` all three clips at construction (ids cached). `play()` with
  `rate = 1f`, `priority = 1`, `loop = 0`.
  - **Async-load readiness (Codex finding 3):** `SoundPool.load()` is asynchronous, so a tap
    that lands before a clip finishes loading would vibrate without sound. Mitigation: the
    adapter is constructed eagerly in `AppContainer` during `Application.onCreate`, hundreds
    of ms before any NFC tap is possible, so clips are normally ready. Belt-and-suspenders:
    register an `OnLoadCompleteListener` that flips a `@Volatile var loaded` flag; `play()`
    still always fires the vibration so the tap is acknowledged even if the clip isn't ready
    yet. (A missed first-tap *sound* is acceptable; a missed *vibration* is not.)
- **VIBRATE permission (critical):** `android.permission.VIBRATE` must be declared in the
  manifest, else every `vibrate(...)` is a silent no-op that passes build/lint/test.
- **Vibrator (minSdk 24 — critical):**
  - acquire: API 31+ → `getSystemService(VibratorManager::class.java).defaultVibrator`;
    below → `@Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator`.
  - vibrate: API 26+ → `VibrationEffect.createWaveform(pattern, -1)`; API 24–25 →
    `@Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)`.
  - patterns (`longArrayOf`): success = `0, 40` (single short pulse);
    failure = `0, 60, 80, 60` (double buzz); neutral = no vibration (sound only).
  - guard every branch by `Build.VERSION.SDK_INT` so `lintDebug` `NewApi` passes.
- **Provisioning feedback placement (Codex finding 1):** play the cue **inside the bind job
  at the moment the terminal `ProvisionState` is assigned** (`ProvisioningScreen` ~268-288),
  NOT from `LaunchedEffect(provisionState)`. `provisioningState` is app-scoped and retains
  the terminal value, so a `LaunchedEffect` keyed on it re-fires on recompose/rotation and
  would replay the sound. Driving feedback from the job means exactly one cue per real
  attempt. Remove the existing `LongPress` haptic `LaunchedEffect` (its vibration is now the
  adapter's).
- **Threading:** `SoundPool.play` and `Vibrator.vibrate` are thread-safe; the scan-overlay
  call happens inside `scanMutex` on a coroutine, the others on Compose/coroutine threads.

## What Goes Where
- **Implementation Steps** (checkboxes): asset copy + permission + reader flag, pure mapper +
  test, adapter, DI + scan-overlay wiring, idle/bind/check/provision call sites, verification,
  docs.
- **Post-Completion** (no checkboxes): on-device manual verification (sound/vibration per
  outcome, confirm stock beep gone, silent-path spot checks), silent-mode behavior check.

## Implementation Steps

### Task 1: Add sound assets + VIBRATE permission + suppress the platform beep

**Files:**
- Create: `app/src/main/res/raw/beep_ok3.mp3` (copied from v1)
- Create: `app/src/main/res/raw/beep_err.mp3` (copied from v1)
- Create: `app/src/main/res/raw/beep_scan.mp3` (copied from v1)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] copy `beep_ok3.mp3`, `beep_err.mp3`, `beep_scan.mp3` from
      `/Users/alff0x1f/src/kolco24_app/app/src/main/res/raw/` into `app/src/main/res/raw/`
- [x] **add `<uses-permission android:name="android.permission.VIBRATE"/>` to
      `AndroidManifest.xml`** — without it `Vibrator.vibrate(...)` is a silent no-op on every
      API level (no crash, no lint finding), so the vibration half ships dead
- [x] add `NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS` to the `READER_FLAGS` constant
      (`MainActivity.kt` companion, ~line 357)
- [x] build `./gradlew assembleDebug` — confirm resources compile (no test for asset/flag/
      manifest changes; this task has no pure code)

### Task 2: Pure feedback mapper + test

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanFeedback.kt`
  (pure mapper only — `enum ScanFeedbackKind` + `feedbackFor`)
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/scan/ScanFeedbackTest.kt`

- [x] declare `enum class ScanFeedbackKind { Success, Failure, Neutral }`
- [x] implement `fun feedbackFor(event: ScanEvent): ScanFeedbackKind` — `Kp`/`Member` →
      `Success`; `UnboundChip`/`BadKp` → `Failure` (exhaustive `when`, no `else`; `Neutral`
      is intentionally never returned here — it is a non-overlay outcome)
- [x] write `ScanFeedbackTest`: `Kp` → Success, `Member` → Success, `UnboundChip` →
      Failure, `BadKp` → Failure
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: ScanFeedbackPlayer Android adapter

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/ScanFeedbackPlayer.kt`
  (Android adapter — `SoundPool` + `Vibrator`; untested by convention)

- [x] create `ScanFeedbackPlayer(context)` building a `SoundPool`
      (`USAGE_ASSISTANCE_SONIFICATION`, `maxStreams = 2`) and loading `R.raw.beep_ok3` +
      `R.raw.beep_err` + `R.raw.beep_scan` once, caching the returned sound ids
- [x] register an `OnLoadCompleteListener` flipping a `@Volatile var loaded` readiness flag;
      `play()` always fires the vibration regardless, only the sound is gated on readiness
- [x] resolve the `Vibrator` with the `VibratorManager` (API 31+) / `VIBRATOR_SERVICE`
      (below) split, each branch guarded by `Build.VERSION.SDK_INT`
- [x] implement a private `vibrate(pattern: LongArray)` with the
      `VibrationEffect.createWaveform` (API 26+) / deprecated `vibrate(pattern, -1)`
      (API 24–25) split
- [x] implement `success()` (beep_ok3 + `0,40`), `failure()` (beep_err + `0,60,80,60`),
      `neutral()` (beep_scan, no vibration), and `fun play(kind: ScanFeedbackKind)`
      dispatching to the three
- [x] run `./gradlew lintDebug` — confirm no `NewApi` findings (no unit test: Android
      adapter, untested by convention)

### Task 4: Wire into AppContainer + scan overlay + idle unknown-tag

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`

- [x] construct a single `ScanFeedbackPlayer` eagerly in `AppContainer` and expose it (e.g.
      `val scanFeedback`) so its `SoundPool.load()` starts at `Application.onCreate`
- [x] add a `scanFeedback: ScanFeedbackPlayer` (or a minimal `(ScanFeedbackKind) -> Unit`)
      param to `ScanScreen`; pass `container.scanFeedback` from `MainActivity`
- [x] in `ScanScreen.process()`, after `val event = currentOnScanTag(input, sample)`
      (line ~138, inside `scanMutex`), call `scanFeedback.play(feedbackFor(event))` so every
      tap path — incl. idempotent re-taps — fires exactly one outcome
- [x] in `MainActivity.onTagDiscovered`, on the unknown-tag drop (`code == null &&
      uid !in boundUidsSnapshot`, ~line 348), call `scanFeedback.neutral()` before `return`
- [x] build `./gradlew assembleDebug` (wiring only; no pure logic added here)

### Task 5: Bind / check / provision call sites

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/BindChipSheet.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/CheckChipScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (thread `scanFeedback` to
  these overlays if not already available)

- [x] BindChipSheet: on the `decideBind` result, `ReadyToBind`/`AlreadyOnThisSlot` →
      `success()`; `NotInPool` → `failure()`; **`AlreadyBound` → `neutral()`** (warn+allow,
      the next tap/button can still finish the flow — Codex finding 4)
- [x] CheckChipScreen: on `classifyChipCheck`, `Ok` → `success()`;
      `UnknownChip`/`Inconsistent`/`NoCode` → `failure()`; the **data-not-ready early return**
      (`!dataReadyLatest.value`, ~line 171) → `neutral()` before `return@withLock`
- [x] ProvisioningScreen: play the cue **inside the bind job at each terminal
      `ProvisionState` assignment** (~lines 268-288) — `Success(...)` → `success()`, every
      `Failed(...)` → `failure()` — and **remove** the `LaunchedEffect(provisionState)`
      `LongPress` haptic (~lines 200-204) so there is no rotation replay and no double pulse
      (Codex finding 1). Leave the mid-swipe/busy drop (~line 230) silent by design.
- [x] build `./gradlew assembleDebug` (each branch reuses an existing classification; no new
      pure logic to unit-test — decisions like `decideBind`/`classifyChipCheck` are already
      tested)

### Task 6: Verify acceptance criteria
- [x] confirm three outcomes (Success/Failure/Neutral), no "complete" flourish
- [x] confirm `feedbackFor` is exhaustive over `ScanEvent` (compiler-checked `when`) and
      never returns `Neutral`
- [x] confirm provisioning feedback fires from the job, not `LaunchedEffect` (no replay on
      rotation), and the old haptic `LaunchedEffect` is gone
- [x] run full unit suite: `./gradlew testDebugUnitTest`
- [x] run `./gradlew lintDebug` (no `NewApi` regressions)
- [x] run `./gradlew assembleDebug`
- [x] (no project e2e harness — skip)

### Task 7: [Final] Docs + housekeeping
- [x] update `CLAUDE.md`: note the `ScanFeedback`/`feedbackFor` + `ScanFeedbackPlayer` seam,
      the three outcomes, the `FLAG_READER_NO_PLATFORM_SOUNDS` reader flag + the deliberate
      silent paths, and the `res/raw` beep assets
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual / external — informational only, no checkboxes.*

**Manual verification (on a real NFC device):**
- Tap a КП chip and a bound bracelet → success tick + short buzz; the stock Android beep is
  gone.
- Re-tap an already-credited bracelet within the window → still success tick (no window
  reset), confirming the "recognized chip" semantics.
- Tap an unbound bracelet in the scan overlay, or an unknown КП → error tone + double buzz.
- **Tap a completely unknown tag while idle** (not a КП, not a bound bracelet) → neutral tick,
  no vibration (the new cue replacing the lost platform beep).
- Bind: valid chip → success; not-in-pool → failure; **already-bound-elsewhere → neutral**
  (then confirm the reassign flow still completes).
- Check-chip: ok → success; unknown/inconsistent → failure; **tap before legend data loads →
  neutral**.
- Provision: written → success; server/write error → failure; **rotate the screen after a
  success and confirm the sound does NOT replay**; mid-swipe tap → silent.
- Recognized chip with no team selected → routes to the picker, stays silent (navigation is
  the feedback) — confirm this is acceptable.
- Confirm behaviour with the phone on **silent/vibrate** mode (sonification stream + vibrate)
  and at low/high media volume; adjust `AudioAttributes`/volume if the tick is inaudible
  outdoors.
- Confirm no perceptible latency added to the scan path (sound/vibrate are fire-and-forget).
