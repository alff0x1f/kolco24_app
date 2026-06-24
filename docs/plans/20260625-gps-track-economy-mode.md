# GPS Track Recording — Battery-Saving (Economy) Mode

## Overview
Add a **second recording profile** for the GPS track, switchable from Settings via a Switch («Экономия батареи»). Today the track records on a single hard-coded «field-test» profile (HIGH_ACCURACY, 15 s interval) where the GPS radio runs effectively continuously. An economy profile at a **3-minute interval** lets the radio drop into duty-cycle (sleeps between fixes) → several-fold battery saving over a multi-hour race. For the anti-fraud proof-of-path use case a coordinate every ~3 min is sufficient.

**Problem it solves:** a full-accuracy 15 s track can drain the phone before the race ends; there is no way to trade track density for battery. This adds a user-selectable economy mode. It also fixes a field-tested bug — pressing «Стоп» dropped the last buffered batch of fixes (Task 1).

**Key benefit:** the user can flip economy on **mid-race** (e.g. battery low at hour 3) and the running recording adapts live — no service/session restart and no fix loss on the toggle: an **awaitable `flush()`** delivers Fused's buffered batch (enqueues it to `applicationScope`) before the engine restart. The same `flush()` fixes a **field-tested base-branch bug** where pressing «Стоп» dropped the buffered batch (up to `maxDelay` of fixes). The buffer is **delivered/enqueued** before stop (durability via `applicationScope`, not a synchronous write barrier); a bare `onDestroy()` without `ACTION_STOP` or a hard kill (battery death / force-stop / crash) can still lose the in-flight buffer — bounded by `maxDelay`, which is why Precise stays at 60 s.

**Integration:** mirrors existing patterns exactly — pure Android-free enum + parse (like `ThemeMode`/`CheckpointColor`), a `SharedPreferences`-backed reactive preference (a copy of `ThemePreference`), engines parameterized by a value object, a Switch row in `SettingsScreen` threaded like `themeMode`, and a `collectLatest` in `TrackRecordingService` like the existing point-count collector.

## Context (from discovery)
- **Branch:** `feat/gps-track-recording` (unpushed commits `4fc0eed` + `227267d`). Current Fused profile hard-coded: `PRIORITY_HIGH_ACCURACY, 15 s, minUpdate 15 s, maxUpdateDelay 60 s`, **no** displacement filter (all raw fixes). Legacy: `GPS_PROVIDER, 15 s, 0f` + `NETWORK_PROVIDER` fallback.
- **Files involved:**
  - `app/src/main/java/ru/kolco24/kolco24/data/track/FusedLocationEngine.kt` — hard-coded `LocationRequest`.
  - `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyLocationEngine.kt` — hard-coded `requestLocationUpdates`.
  - `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngineFactory.kt` — `create(context)`; pure `chooseEngineType(gmsAvailable)`.
  - `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt` — `onStartCommand` builds the engine (`LocationEngineFactory.create(this)` at line ~110) and runs a `collectLatest` on `countForTeam`; has `serviceScope`, `engine`, `countJob`.
  - `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` — manual DI, lazy `themePreference`.
  - `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt` — stateless; `ThemeRow`/`ThemeDialog`; params `themeMode`/`onThemeModeChange`. «Внешний вид» `Surface` card holds `ThemeRow`.
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — collects `container.themePreference.mode`, threads `themeMode`/`onThemeModeChange` through `Kolco24AppRoot` into `SettingsScreen`.
- **Patterns to copy:**
  - `data/ThemePreference.kt` + `app/src/test/.../data/ThemePreferenceTest.kt` (injected `load`/`save`, synchronous read, `MutableStateFlow`, `fromSharedPreferences`).
  - `ui/theme/ThemeMode.kt` (pure enum + `parseThemeMode`, forward-compatible default).
  - `SettingsScreen.kt` `ThemeRow` styling + the threading of `themeMode`/`onThemeModeChange` through `MainActivity`.
  - The existing `serviceScope.launch { ...collectLatest... }` in `TrackRecordingService.onStartCommand`.

## Development Approach
- **testing approach**: Regular (code first, then JVM tests in the same task) — per repo convention: pure models/preferences are JVM-unit-tested; Android adapters (engines, Service, Compose UI) are not.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task with testable code MUST include new/updated tests** (pure enum/parse, preference reactive behavior). For non-testable-by-convention parts (engines, Service, Compose) no test is written — explicitly noted per task.
- **CRITICAL: all tests must pass before starting the next task**
- run tests after each change
- maintain backward compatibility (default profile = `Precise` = current behavior; no DB migration — preference is `SharedPreferences`)

## Testing Strategy
- **unit tests (JVM, `testDebugUnitTest`)**: `TrackProfileTest` (enum params + `parseTrackProfile`), `TrackProfilePreferenceTest` (mirror `ThemePreferenceTest`).
- **no instrumented tests**: this feature touches no Room schema → **no migration, no `connectedDebugAndroidTest`**.
- **adapters untested by convention**: `FusedLocationEngine`/`LegacyLocationEngine`/`LocationEngineFactory.create`/`TrackRecordingService`/`SettingsScreen`/`MainActivity` — the engine *selection* stays covered by the existing `LocationEngineFactoryTest`.
- **lint**: `./gradlew lintDebug` must pass.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
A pure `TrackProfile` enum carries the three numbers per profile (`highAccuracy`, `intervalMs`, `maxDelayMs`); `Precise(15 s/60 s)` is the default and equals today's behavior, `Economy(180 s/180 s)` is the new battery mode. Both keep `PRIORITY_HIGH_ACCURACY` (real GPS — `BALANCED` only gives city-block cell/WiFi accuracy, useless in a forest; **all** the saving comes from the longer interval, not the priority) and **no** displacement filter (raw fixes, unchanged).

`TrackProfilePreference` (a copy of `ThemePreference`) persists the choice and exposes a `StateFlow<TrackProfile>`. The engines take a `TrackProfile` in their constructor and read it in `start()`; `LocationEngineFactory.create(context, profile)` passes it through. `TrackRecordingService` reads the current profile at start **and** observes the preference flow — on a live change it does a soft engine restart: **awaitable** `flush { stop(); create(this, newProfile); start(...) }` (the buffered batch is delivered before the restart → no fix loss). The same awaitable `flush()` is used in `teardown()` (the Stop path) to fix the field-tested point-loss bug. All engine-field mutations run on `Dispatchers.Main.immediate` so they serialize with the main-thread service lifecycle (`onStartCommand`/`teardown`/`onDestroy`). Settings shows a Switch; `MainActivity` maps `Economy`↔boolean.

### Key design decisions
- **Priority HIGH in both profiles** — forest races have no cell/WiFi; `BALANCED` would be useless. Battery is traded via interval (duty-cycle at 3 min), not priority.
- **No DB migration** — the choice is a `SharedPreferences` value, not per-point data.
- **Default `Precise`** — `parseTrackProfile(null) == Precise`, so existing installs and the first cold start are byte-for-byte the current behavior.
- **Live-apply via soft engine restart** — the core use case is enabling economy *mid-race*; apply-on-next-start would force a stop/restart and a track gap.
- **Awaitable flush before stop (fixes Stop-drops-points + live-switch loss)** — Fused holds up to `maxDelay` (60–180 s) of fixes in the GMS buffer before they reach Room; a bare `stop()` (on «Стоп» **or** on a live-switch restart) drops them — a bug confirmed in field testing. The engine seam gains `flush(onComplete: () -> Unit = {})`: Fused `client.flushLocations().addOnCompleteListener { onComplete() }` (the `Task` completes **after** the buffer is delivered to `onLocationResult`), Legacy default no-op (never batches). Both `teardown()` and the live restart route through a shared `flushThen(e, after)` helper (below) so the buffer is **delivered (enqueued for insert)** before stopping.
- **Flush helper invariants (`flushThen`) — three guards (from review):**
  1. **Exactly-once + timeout** — `after` runs once via an `AtomicBoolean`, fired by the flush callback **or** a `mainHandler.postDelayed(FLUSH_TIMEOUT_MS ≈ 4 s)` fallback. A never-completing `flushLocations()` (rare GMS hiccup) must **not** leave the «Стоп» service stuck foreground — the timeout stops it anyway.
  2. **Captured-engine identity** — the callback closes over a captured `val e = engine`, and before acting checks `if (engine !== e) return` (skip). A stale flush callback from a rapid double-toggle must not `stop()` a newer engine or start an obsolete profile.
  3. **Restart to the latest value** — on a live restart the helper re-reads `trackProfilePreference.profile.value` (not the captured `p`) so a toggle that arrived during the in-flight flush isn't lost.
- **Durability is via `applicationScope`, not a write barrier (weakened from review):** flush guarantees the buffer is **delivered to `onLocationResult` and enqueued** as `applicationScope.launch { insertAll }` before stop — it does **not** await the Room write. The write completes shortly after on `applicationScope` (which outlives `stopSelf`, idempotent by id). So the honest guarantee is "delivered/enqueued before stop", and durability rests on `applicationScope` surviving teardown — **not** "the row is on disk before stop". (A true write barrier — track pending insert jobs and `join` them before final teardown — is possible but omitted as over-engineering; if field-testing shows loss on an immediate swipe-away after Stop, add it.)
- **`onDestroy()` (no `ACTION_STOP`) also flushes, else the guarantee is narrowed (from review):** a system-initiated `onDestroy()` (task removed / system reclaim) with the process still alive would otherwise drop the buffer via the bare `engine?.stop()`. Route it through a **best-effort** `flushThen` too — but `onDestroy` may be followed by process death before the async flush lands, so the guarantee is: **lossless on «Стоп» and live-switch; a bare `onDestroy()` or a hard kill may lose up to `maxDelay`** (best-effort flush attempted, not guaranteed).
- **Main-thread engine mutations** — `serviceScope` is `Dispatchers.Default`; the `engine` field is mutated by `onStartCommand`/`teardown`/`onDestroy` on the **main** thread, and the flush callbacks fire on the main thread. The live-apply collector must therefore mutate the engine on `Dispatchers.Main.immediate` to serialize with those and the identity guard, avoiding a data race on the plain `var engine`.
- **Boolean Switch in `SettingsScreen`, enum elsewhere** — the screen stays a simple boolean; the `Boolean ↔ TrackProfile` mapping lives in `MainActivity`. The model stays an enum so a 3rd profile can be added later without reshaping the UI contract.

## Technical Details
- `TrackProfile`:
  ```kotlin
  enum class TrackProfile(val highAccuracy: Boolean, val intervalMs: Long, val maxDelayMs: Long) {
      Precise(highAccuracy = true, intervalMs = 15_000L, maxDelayMs = 60_000L),
      Economy(highAccuracy = true, intervalMs = 180_000L, maxDelayMs = 180_000L),
  }
  fun parseTrackProfile(raw: String?): TrackProfile =
      TrackProfile.entries.firstOrNull { it.name == raw } ?: TrackProfile.Precise
  ```
- `TrackProfilePreference`: prefs file `"kolco24.settings"` (same as theme), key `"track_profile"`, synchronous read at construction, `setProfile(p)` updates the flow + `save(p.name)`.
- Fused `start()`: `Priority = if (profile.highAccuracy) PRIORITY_HIGH_ACCURACY else PRIORITY_BALANCED_POWER_ACCURACY`; `LocationRequest.Builder(priority, profile.intervalMs).setMinUpdateIntervalMillis(profile.intervalMs).setMaxUpdateDelayMillis(profile.maxDelayMs).build()` — **no** `setMinUpdateDistanceMeters` (raw, unchanged).
- Legacy `start()`: `requestLocationUpdates(provider, profile.intervalMs, 0f, listener, looper)` (`maxDelay` has no Legacy equivalent — note in KDoc).
- New service fields: `private val mainHandler = Handler(Looper.getMainLooper())`, `private var activeProfile: TrackProfile = TrackProfile.Precise`, `private var profileJob: Job? = null`, `private const val FLUSH_TIMEOUT_MS = 4_000L`.
- **Engine seam gains awaitable `flush(onComplete)`:** `fun flush(onComplete: () -> Unit = {}) { onComplete() }` on `LocationEngine` (default = immediate completion for non-batching engines); `FusedLocationEngine` overrides with `client.flushLocations().addOnCompleteListener { onComplete() }` (the GMS `Task` completes after the buffer is delivered to `onLocationResult`).
- **Shared `flushThen` helper (exactly-once + timeout):**
  ```kotlin
  private fun flushThen(e: LocationEngine, after: () -> Unit) {
      val done = AtomicBoolean(false)
      val complete = Runnable { if (done.compareAndSet(false, true)) after() }
      mainHandler.postDelayed(complete, FLUSH_TIMEOUT_MS)        // stuck-flush fallback
      e.flush { mainHandler.removeCallbacks(complete); complete.run() }
  }
  ```
- `startEngine(profile)` is **fully synchronous** and starts the **captured local**, not the field (review minor #1 — preserves the existing NPE-safe pattern from `onStartCommand` where a concurrent `engine = null` can't NPE `start()`): `val e = LocationEngineFactory.create(this, profile); engine = e; e.start(onPoints, onError)`.
- **`profileJob` lifecycle = `countJob` lifecycle (critical):** `profileJob` must be **(re)launched inside `onStartCommand`** next to `countJob`, not once. `teardown()` cancels `serviceScope`; a later start recreates it (line ~85), so a once-launched `profileJob` dies on the new scope and live-apply silently stops after the first stop→start. `onStartCommand` sets `activeProfile = container.trackProfilePreference.profile.value; startEngine(activeProfile)`, then:
  ```kotlin
  profileJob?.cancel()
  profileJob = serviceScope.launch(Dispatchers.Main.immediate) {
      container.trackProfilePreference.profile.collectLatest { p ->   // NO drop(1)
          if (p == activeProfile) return@collectLatest                // already on it → skip
          val e = engine ?: run { activeProfile = p; startEngine(p); return@collectLatest }
          flushThen(e) {
              if (engine !== e) return@flushThen                      // newer engine took over
              e.stop()
              val latest = container.trackProfilePreference.profile.value  // restart to LATEST
              activeProfile = latest
              startEngine(latest)
          }
      }
  }
  ```
  - **No `.drop(1)`** (review #2): reading `profile.value` for the initial start then `.drop(1)` could drop a real change that lands between the read and the subscription. Instead track `activeProfile` and skip emissions equal to it — the first emission (even if it changed in the gap) is handled correctly.
  - **Captured-engine identity** (review #1): the `flushThen` callback guards `if (engine !== e)` so a stale callback from a rapid double-toggle can't stop a newer engine.
  - **Restart to `latest`** (review #1): re-read `.value` so a toggle during the in-flight flush isn't lost.
  - **`activeProfile` lags the in-flight target** (re-review important #1): it is updated only **inside** the flush callback (line 105), not at emission time. So between an emission and its flush completing, the `if (p == activeProfile)` skip still reflects the *last-completed* profile — a rapid double-toggle can spawn redundant in-flight `flushThen`s. This is **not** a terminal desync: each callback restarts to the **latest** `.value` and the identity guard makes stale callbacks no-ops, so the final state always converges. Convergence comes from restart-to-latest, **not** the skip guard. (Optimistically setting `activeProfile = p` at emission time is rejected — it would falsely claim the new profile if the flush later early-returns on `engine !== e`.)
  - `Dispatchers.Main.immediate` serializes `engine` writes with the main-thread lifecycle + flush callbacks.
- **`teardown()` flushes before stop:**
  ```kotlin
  private fun teardown() {
      val e = engine ?: return finishTeardown()
      flushThen(e) { if (engine === e) { e.stop(); engine = null }; finishTeardown() }
  }
  ```
  `finishTeardown()` = `engine?.stop(); engine = null; mainHandler.removeCallbacksAndMessages(null); countJob?.cancel(); profileJob?.cancel(); serviceScope.cancel(); stopForeground; stopSelf; state = Idle`. **Critical (re-review #1):** it must (a) **null `engine`** defensively (covers the case where teardown's own identity guard didn't, so a later stale timeout's `engine !== e` is guaranteed true) and (b) **`mainHandler.removeCallbacksAndMessages(null)`** to drop any *other* `flushThen` instance's pending timeout `Runnable` (e.g. a live-switch flush that was in flight when «Стоп» fired) — the per-instance `AtomicBoolean` does **not** cover a leaked Runnable from a different `flushThen` call. Both must happen **before** `serviceScope.cancel()`/`stopSelf()`. It runs on the main thread (flush/timeout callback), **not** inside a `serviceScope` coroutine, so `serviceScope.cancel()` is safe.
- **`onDestroy()` (no `ACTION_STOP`) routes through the same flush** (review #4) — best-effort: `val e = engine; if (e != null && state is Recording) flushThen(e) { if (engine === e) e.stop(); finishTeardown() } else finishTeardown()` (reuse `finishTeardown()`, which nulls `engine` + clears the handler). `onDestroy` may precede process death before the async flush lands, so this is best-effort and the guarantee is narrowed accordingly (lossless on «Стоп»/live-switch; bare `onDestroy`/hard-kill may lose up to `maxDelay`).
- **Two stop sites:** `startEngine` does **not** pre-stop; callers do — the `onStartCommand` defensive `engine?.stop(); engine = null` (lines ~99–100) stays; the live-restart and teardown stop inside their `flushThen` callbacks. Cancel `profileJob` in `finishTeardown()`/`onDestroy()` alongside `countJob`.

## What Goes Where
- **Implementation Steps** (`[ ]`): all code, tests, docs in this repo.
- **Post-Completion** (no checkboxes): on-device battery measurement of economy vs precise; verifying the mid-race live switch on real hardware.

## Implementation Steps

### Task 1: Fix point loss on Stop — awaitable `flush()` before teardown (base-branch bug)

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngine.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/FusedLocationEngine.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyLocationEngine.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`

> **Why first:** field-tested bug, independent of profiles — pressing «Стоп» calls `engine.stop()` (`removeLocationUpdates`), which discards Fused's buffered batch (up to `maxDelay` = 60 s of fixes) before it reaches Room. Must flush the buffer **and wait for delivery** before stopping. The `flush()` seam + `flushThen` helper added here are reused by the economy live-apply (Task 7).

- [ ] add `fun flush(onComplete: () -> Unit = {}) { onComplete() }` to the `LocationEngine` interface — default body completes immediately (engines that never batch); KDoc: forces delivery of any buffered batch, then invokes `onComplete`
- [ ] `FusedLocationEngine`: override `flush(onComplete)` → `client.flushLocations().addOnCompleteListener { onComplete() }` (the GMS `Task` completes **after** the buffered locations are delivered to `onLocationResult`, so the points are enqueued for insert when `onComplete` runs)
- [ ] `LegacyLocationEngine`: keep the default no-op `flush` (it never batches → `onComplete()` immediately)
- [ ] add the `mainHandler` field + `FLUSH_TIMEOUT_MS` + the shared `flushThen(e, after)` helper (see Technical Details) — **exactly-once** completion (`AtomicBoolean`) fired by the flush callback **or** a `postDelayed(FLUSH_TIMEOUT_MS ≈ 4 s)` fallback so a never-completing `flushLocations()` can't leave the «Стоп» service stuck foreground (review #5)
- [ ] `teardown()`: `val e = engine ?: return finishTeardown(); flushThen(e) { if (engine === e) { e.stop(); engine = null }; finishTeardown() }` — capture `e` and **identity-guard** (`engine === e`) so a rapid stop→start can't stop a newer engine (review #1)
- [ ] `finishTeardown()` (re-review #1 — critical): `engine?.stop(); engine = null; mainHandler.removeCallbacksAndMessages(null); countJob?.cancel(); profileJob?.cancel(); serviceScope.cancel(); stopForeground; stopSelf; state = Idle`. The defensive `engine = null` + `removeCallbacksAndMessages(null)` (drop any *other* `flushThen` instance's pending timeout `Runnable`, e.g. an in-flight live-switch) must run **before** `serviceScope.cancel()`/`stopSelf()` — the per-instance `AtomicBoolean` does not cover a leaked cross-instance Runnable. Runs on the main thread, not inside a `serviceScope` coroutine, so `serviceScope.cancel()` is safe
- [ ] **wording is "enqueued", not "persisted"** (review #3): flush delivers the buffer to `onLocationResult` → `applicationScope.launch { insertAll }` (applicationScope outlives `stopSelf`, idempotent by id) — the row reaches disk shortly after on `applicationScope`, not synchronously before stop. Durability rests on `applicationScope` surviving teardown; **no** write barrier (omitted as over-engineering)
- [ ] `onDestroy()` (no `ACTION_STOP`): route through best-effort `flushThen { if (engine === e) e.stop(); finishTeardown() }` (review #4) — reuse `finishTeardown()` so the engine-null + handler-clear cleanup applies; `onDestroy` may precede process death, so best-effort only; reflect this in the narrowed guarantee
- [ ] (no test — Service/engine adapters, untested per convention; verified by the on-device repro in Post-Completion + the logic review in Task 10)
- [ ] run `./gradlew compileDebugKotlin` + `./gradlew lintDebug` — must pass before Task 2

### Task 2: Pure `TrackProfile` enum + parser

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/track/TrackProfile.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/track/TrackProfileTest.kt`

- [ ] create `TrackProfile.kt` with the enum (`Precise`/`Economy`, fields `highAccuracy`/`intervalMs`/`maxDelayMs`) — pure, Android-free, no Compose/Android imports (mirrors `ui/theme/ThemeMode.kt`/`CheckpointColor.kt`); include the KDoc header noting "pure, Android-free, JVM-unit-tested" per the existing pure-model convention
- [ ] add top-level `parseTrackProfile(raw: String?): TrackProfile` — `entries.firstOrNull { it.name == raw } ?: Precise` (null/unknown → default, forward-compatible)
- [ ] write tests `TrackProfileTest`: `parseTrackProfile(null) == Precise`, `parseTrackProfile("Economy") == Economy`, `parseTrackProfile("Precise") == Precise`, `parseTrackProfile("garbage") == Precise`
- [ ] write tests: assert the param values per profile (`Economy.intervalMs == 180_000L`, `Economy.maxDelayMs == 180_000L`, `Precise.intervalMs == 15_000L`, `Precise.maxDelayMs == 60_000L`, both `highAccuracy`)
- [ ] run `./gradlew testDebugUnitTest` — must pass before Task 3

### Task 3: `TrackProfilePreference` (persisted, reactive)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/TrackProfilePreference.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/TrackProfilePreferenceTest.kt`

- [ ] create `TrackProfilePreference(load: () -> String?, save: (String) -> Unit)` — copy `data/ThemePreference.kt`: `private val _profile = MutableStateFlow(parseTrackProfile(load()))`, `val profile: StateFlow<TrackProfile>`, `fun setProfile(p)` → `_profile.value = p; save(p.name)`
- [ ] add `companion object fun fromSharedPreferences(context): TrackProfilePreference` — prefs file `"kolco24.settings"`, key `"track_profile"`, `getString(..., null)` / `edit().putString(...).apply()`
- [ ] write tests `TrackProfilePreferenceTest` (mirror `ThemePreferenceTest`, `FakeStore`): default `Precise` when store empty; pre-seeded `"Economy"` read on init; pre-seeded unknown → `Precise`; `setProfile` persists enum name AND emits new value; persisted value reloaded by a fresh instance
- [ ] run `./gradlew testDebugUnitTest` — must pass before Task 4

### Task 4: Parameterize both engines by `TrackProfile`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/FusedLocationEngine.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LegacyLocationEngine.kt`

- [ ] `FusedLocationEngine(context, profile: TrackProfile)`: in `start()` derive `priority = if (profile.highAccuracy) PRIORITY_HIGH_ACCURACY else PRIORITY_BALANCED_POWER_ACCURACY`; build `LocationRequest.Builder(priority, profile.intervalMs).setMinUpdateIntervalMillis(profile.intervalMs).setMaxUpdateDelayMillis(profile.maxDelayMs).build()`; keep **no** `setMinUpdateDistanceMeters` (raw); `flush()` already added in Task 1; update KDoc (profiles, interval/maxDelay from `profile`, duty-cycle rationale at 3 min)
- [ ] `LegacyLocationEngine(context, profile: TrackProfile)`: in `start()` use `requestLocationUpdates(provider, profile.intervalMs, 0f, listener, looper)`; update KDoc (no `maxDelay` equivalent; still raw)
- [ ] (no tests — real Android adapters, untested per convention; engine *choice* stays covered by `LocationEngineFactoryTest`)
- [ ] `./gradlew compileDebugKotlin` — compiles before Task 5 (factory still calls old `create`, fixed in Task 5; if it breaks compile, do Task 5 together)

### Task 5: `LocationEngineFactory.create(context, profile)`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/track/LocationEngineFactory.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/track/LocationEngineFactoryTest.kt` (verify only — should need no change)

- [ ] change `create(context)` → `create(context, profile: TrackProfile)`; pass `profile` into `FusedLocationEngine(context, profile)` / `LegacyLocationEngine(context, profile)`
- [ ] keep `chooseEngineType(gmsAvailable)` pure and unchanged
- [ ] confirm `LocationEngineFactoryTest` still compiles/passes (it tests `chooseEngineType`, not `create`) — adjust only if it referenced `create`
- [ ] run `./gradlew testDebugUnitTest` — must pass before Task 6

### Task 6: Wire `trackProfilePreference` into `AppContainer`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] add lazy `val trackProfilePreference: TrackProfilePreference by lazy { TrackProfilePreference.fromSharedPreferences(context) }` (mirror `themePreference`)
- [ ] (no test — DI wiring, trivial, per convention)
- [ ] run `./gradlew compileDebugKotlin` — compiles before Task 7

### Task 7: Read profile at start + live-apply in `TrackRecordingService`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/TrackRecordingService.kt`

- [ ] extract private `fun startEngine(profile: TrackProfile)`: `val e = LocationEngineFactory.create(this, profile); engine = e; e.start(onPoints, onError)` — start the **captured local `e`**, not the `engine` field (review minor #1 — preserves the NPE-safe pattern from `onStartCommand`); move the existing `onPoints`/`onError` lambdas in; fully synchronous; must **not** pre-stop (callers own the stop)
- [ ] add the `activeProfile` field; in `onStartCommand` keep the defensive `engine?.stop(); engine = null` (lines ~99–100), then `activeProfile = container.trackProfilePreference.profile.value; startEngine(activeProfile)`
- [ ] **(re)launch `profileJob` inside `onStartCommand`** next to `countJob` (line ~103) — **critical: not a one-time launch** (teardown cancels `serviceScope`; a later start recreates it). Use the exact pattern from Technical Details: `collectLatest` **without `.drop(1)`**, skip when `p == activeProfile`, else `flushThen(e) { if (engine !== e) return@flushThen; e.stop(); val latest = …value; activeProfile = latest; startEngine(latest) }`
  - **review #2:** no `.drop(1)` — track `activeProfile` instead, so a change landing between the initial `.value` read and the subscription isn't dropped
  - **review #1:** capture `e` + identity-guard (`engine !== e`) and restart to the **latest** `.value` so a stale/rapid double-toggle can't stop a newer engine or settle on an obsolete profile
- [ ] cancel `profileJob` in `finishTeardown()` and `onDestroy()` alongside `countJob`
- [ ] (no test — Service adapter, untested per convention; note it. The `profileJob` re-launch + identity/flush ordering is the real logic risk, verified by review in Task 10)
- [ ] run `./gradlew compileDebugKotlin` + `./gradlew lintDebug` — must pass before Task 8

### Task 8: Switch row in `SettingsScreen`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`

- [ ] add params `economyMode: Boolean, onEconomyModeChange: (Boolean) -> Unit` to `SettingsScreen` (place near `themeMode`/`onThemeModeChange`)
- [ ] add a «Запись трека» (or reuse «Внешний вид»-style) `Surface` card with a private `EconomyModeRow(checked, onCheckedChange)` — styled like `ThemeRow`/`ChangeTeamRow` (icon avatar, title «Экономия батареи», subtitle by state: «Координата раз в 3 мин» when on / «Точная запись, 15 с» when off) with a trailing `androidx.compose.material3.Switch` instead of `ChevronRight`
- [ ] (no test — Compose UI, untested per convention)
- [ ] run `./gradlew compileDebugKotlin` — compiles before Task 9

### Task 9: Thread the profile through `MainActivity`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] collect `val trackProfile by container.trackProfilePreference.profile.collectAsState()` near the existing theme `collectAsState` (thread through `Kolco24AppRoot` params like `themeMode`/`onThemeModeChange`)
- [ ] pass to `SettingsScreen`: `economyMode = (trackProfile == TrackProfile.Economy)`, `onEconomyModeChange = { container.trackProfilePreference.setProfile(if (it) TrackProfile.Economy else TrackProfile.Precise) }`
- [ ] (no test — Compose/MainActivity, untested per convention)
- [ ] run `./gradlew compileDebugKotlin` + `./gradlew lintDebug` — must pass before Task 10

### Task 10: Verify acceptance criteria
- [ ] verify: default install records on `Precise` (15 s) unchanged; toggling Switch to economy persists and survives process death; mid-recording toggle live-restarts the engine without a track gap (logic review — on-device check is Post-Completion)
- [ ] **logic review — Stop fix (critical, Service untested):** `teardown()` uses `flushThen(e) { if (engine === e) { e.stop(); engine = null }; finishTeardown() }` — identity-guarded, exactly-once, with the `FLUSH_TIMEOUT_MS` fallback so a stuck flush can't hang the foreground service (reviews #1, #5); buffer is **enqueued** to `applicationScope` before stop (review #3 wording)
- [ ] **logic review — leaked-timeout cleanup (re-review #1, critical):** `finishTeardown()` nulls `engine` **and** `mainHandler.removeCallbacksAndMessages(null)` **before** `serviceScope.cancel()`/`stopSelf()`, so a live-switch `flushThen` timeout that was pending when «Стоп» fired can't `startEngine` a zombie after teardown
- [ ] **logic review — live-apply (critical):** `profileJob` is cancel-then-relaunched inside `onStartCommand` (not once); runs on `Dispatchers.Main.immediate`; uses `activeProfile` + `collectLatest` **without `.drop(1)`** (review #2); restart body is `flushThen(e) { if (engine !== e) return; e.stop(); restart to latest .value }` (review #1); `profileJob` cancelled in `finishTeardown()`/`onDestroy()`
- [ ] **logic review — onDestroy (review #4):** a bare `onDestroy()` (no `ACTION_STOP`) routes through best-effort `flushThen` reusing `finishTeardown()`
- [ ] verify the guarantee wording is honest: «Стоп» + live-switch are lossless (buffer enqueued before stop via `flushThen`); a bare `onDestroy()` or a **hard kill** may lose up to `maxDelay` — and Precise stays 60 s precisely to bound that. Durability is via `applicationScope` survival, not a synchronous write barrier
- [ ] verify edge cases: unknown stored value → `Precise`; profile change while **not** recording just persists (applied at next start); rapid double-toggle settles on the **latest** profile via restart-to-latest (the `activeProfile`-skip only suppresses repeats of the *last-completed* profile, so redundant in-flight flushes are possible but the **final** state converges — re-review #1); a change landing between the initial `.value` read and subscription is **not** dropped (no `.drop(1)`)
- [ ] run full suite: `./gradlew testDebugUnitTest`
- [ ] run `./gradlew lintDebug`
- [ ] (no `connectedDebugAndroidTest` — no Room migration in this feature)

### Task 11: [Final] Update documentation
- [ ] update `CLAUDE.md`: awaitable `flush(onComplete)` on the `LocationEngine` seam + the `flushThen` helper (exactly-once + `FLUSH_TIMEOUT_MS` fallback + captured-engine identity guard) used by `teardown()`/`onDestroy()`/live-restart (Stop no longer drops the buffered batch; buffer enqueued to `applicationScope` before stop; bare `onDestroy`/hard-kill still bounded by `maxDelay`); new `data/track/TrackProfile.kt` + `data/TrackProfilePreference.kt`; engines parameterized by `TrackProfile`; `LocationEngineFactory.create(ctx, profile)`; live-apply `collectLatest` in `TrackRecordingService`; Switch in `SettingsScreen`; profile wiring in `MainActivity`; lazy `trackProfilePreference` in `AppContainer`
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Require external action — no checkboxes, informational only*

**Manual verification on device:**
- **Stop-bug regression (the field-tested repro):** record a short Precise track, press «Стоп», dump via `scripts/dump-track.sh`, confirm the last ≤60 s of fixes are **present** (previously dropped). This is the Task 1 fix.
- battery draw of `Economy` (3 min) vs `Precise` (15 s) over a multi-hour recording with screen off — confirm the expected several-fold GPS saving and that 3 min fixes actually duty-cycle the radio on the target hardware (device/firmware-dependent).
- mid-race live switch: start recording on `Precise`, flip economy in Settings, confirm the running track continues with **no gap / no lost points** (awaitable flush) and the new interval takes effect (logcat / point cadence).
- non-GMS device (Legacy engine): economy interval honored; note Doze throttling caveat (no `maxDelay` equivalent on Legacy).

**Follow-up (separate task, already parked):**
- altitude/`<ele>` capture — Room v11→v12 migration + `RawFix`/entity/DTO/`dump-track.sh` changes; energy-free but schema work, intentionally out of scope here.
