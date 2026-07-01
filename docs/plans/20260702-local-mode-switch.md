# Local data mode switch (race-day local server pin)

## Overview

At the start line there is often no internet: people install the app on site and need
races/teams/legend/member-tags from the **local server** (LAN, `192.168.1.5`). Race-day team
edits land only on the local server, so cloud and local diverge — and stale cloud data must
**not** overwrite fresher local data until the admin drives back to civilization and syncs
local → cloud.

This is the **explicit-choice alternative** to the earlier lease-handoff plan
(`20260702-local-refresh-lease.md`, never implemented; its commit was dropped from the branch,
so this file is the only live plan). The lease/pin core is identical; what changes is who
decides the source:

- **User decides, not a probe**: a switch «Локальный сервер (Wi-Fi гонки)» in Settings enters
  local mode (LAN refresh + pin); turning it off exits (unpin + cloud refresh). No smart
  «Обновить» button, no mandatory LAN probe on every manual refresh —
  `SmartRefreshPlan`/`decideSmartRefresh` (5 branches) are dropped entirely, replaced by a tiny
  pure `applySyncResponse` (local → renew, cloud → clear, error → no-op).
- **Admin decides the duration**: the server's lease (admin sets it ≈ time to reach
  civilization + sync) wins whenever present — preferably a relative `lease_ttl_seconds`
  (clock-skew-immune), with the already-designed absolute `lease_expires_at` as fallback; the
  client's 12 h default only covers today's stubbed `null`. No app update needed when the
  backend ships the real lease.
- **Everyday refresh is pull-to-refresh** — already wired on «Команда» (and «Легенда» / the
  races picker) via `RefreshableList`; the change is **re-routing** the existing gesture
  through `sourceFor(raceId)`: pinned → LAN, else cloud.
- The pin is a **lease, not a latch**: released by expiry, by server handback
  (`data_source:"cloud"`), or by the switch — never by connectivity loss (leaving Wi-Fi for
  5 minutes must not fall back to a stale cloud roster). Every successful local manifest probe
  renews it.
- **Uploads are untouched** — marks/track/photo POSTs keep flushing to both targets; this plan
  is GET-only.

## Context (from discovery)

- `GET /app/race/<id>/sync/` (`SyncView`) is **already live** on the backend: returns
  `{race, data_source, lease_expires_at, versions{...}}`. The lease is stubbed: `data_source`
  comes from env `MOBILE_DATA_SOURCE` (a local deployment sets `"local"`), `lease_expires_at`
  is always `null` for now.
- `localApiClient` in `AppContainer.kt` is the same `ApiClient` class as the cloud one (all
  fetch methods available), 3 s timeouts, no `ServerTimeInterceptor`; LAN origin =
  `BuildConfig.LOCAL_API_BASE_URL`.
- ETags are already per-origin: `sync_meta` PK is `(origin, resource)` — cloud and LAN ETags
  never collide. No schema change needed.
- `RefreshResult` is a sealed interface in `data/RaceRepository.kt` (~line 19:
  `Updated`/`NotModified`/`Offline`/`Forbidden`/`HttpError`); adding `Skipped` makes the
  compiler surface every exhaustive `when` (notably `refreshErrorMessage` in
  `ui/common/PullToRefresh.kt`).
- `RefreshableList` + `refreshErrorMessage` (`ui/common/PullToRefresh.kt`) already standardize
  the PTR gesture, and it is **already live** on «Команда» (`TeamScreen.kt` wraps its
  `LazyColumn`, params `isRefreshing`/`onRefresh` hoisted), «Легенда», and the races picker.
  `MainActivity` wires them through a generic `pullRefresh(setSpinner, refresh: suspend (Int)
  -> RefreshResult)` helper (~line 538); «Команда»'s refresh currently points at
  `teamRepo::refreshTeams` (~line 1112). Host owns `isRefreshing`, success is silent (Room
  flow updates the list), failures surface via snackbar.
- Auto-sync call sites: `Kolco24App` Launch A (startup races + nearest-race prefetch), Launch B
  (`selectedTeam.collectLatest` → legend/teams/member-tags), `MainActivity.onRaceSelected`
  on-tap prefetch.
- `SettingsScreen.kt` cards in order: «Команда», «Внешний вид», «Запись трека»
  (`EconomyModeRow` — the Switch-row idiom to copy, ~line 434), «Администратор», debug-only
  «Отладка». The new «Данные» card goes between «Запись трека» and «Администратор».
- Prefs-store pattern to copy: `ClockAnchorStore` (pure `load`/`save` seam, one delimited value
  under a single key = atomic write, `fromSharedPreferences` adapter, synchronous read).

## Development Approach

- **testing approach**: Regular (code first, then tests in the same task) — project
  convention: pure models get JVM unit tests; Compose UI and thin Android adapters are
  untested by convention.
- complete each task fully before moving to the next
- **every task with testable logic ends with new/updated tests; all tests must pass before the
  next task** (`./gradlew testDebugUnitTest`, `./gradlew lintDebug` before merging)
- update this plan file when scope changes during implementation
- maintain backward compatibility: all existing call sites keep working via default parameters

## Testing Strategy

- **unit tests (JVM)**: `RaceLeaseTest` (renew/pin/`applySyncResponse`), `RaceLeaseStoreTest`
  (codec round-trip + malformed input), `ApiClientTest` (manifest parsing), repository tests
  for the cloud-vs-pin guard (pattern: `MemberTagsRepositoryTest`), `SyncCoordinatorTest`.
- **no instrumented tests needed**: no Room schema change.
- **untested by convention**: the Settings switch row, the PTR re-route, `MainActivity`
  wiring, Toast/snackbar plumbing.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix

## Solution Overview

1. **`ApiClient.fetchSync(raceId)`** — new GET for the sync manifest, works through either
   client instance (cloud or LAN), no ETag/304 (the endpoint has none by design).
2. **`RaceLease`** — pure lease model (`renewedLease`/`isPinned`/`applySyncResponse`);
   **`RaceLeaseStore`** — plain-prefs store, one delimited key, no Room migration. One pinned
   race at a time.
3. **`SyncSource { Cloud, Local }`** threaded through the four repos (default `Cloud`); a
   guard refuses to persist a **cloud** `200` for a currently-pinned race.
4. **`SyncCoordinator`** — `sourceFor` + `enterLocalMode`/`exitLocalMode` (the switch) +
   `refreshAll` (PTR); auto-syncs become pin-aware via `sourceFor`.
5. **UI**: «Данные» card in Settings hosting the local-mode switch; the already-existing
   pull-to-refresh re-routed through the coordinator.

Switch semantics (approved in brainstorm):

```
Switch position = derived from the lease StateFlow via isPinned(lease, raceId, now)
                  (NOT a stored preference — a handback landing from Launch B or a pull
                   flips the switch off live even while Settings is open; pure time
                   expiry shows on the next recomposition trigger / Settings open)

Turn ON  (async, Bluetooth-style: tap → busy spinner → resolve)
├ LAN fetchSync ok, data_source=local → pin (renewedLease) →
│     races+teams+legend+member_tags from LAN → ON, «Локальный режим до HH:MM»
├ LAN fetchSync ok, data_source=cloud → NO pin; a server that disclaims authority must
│     not have its rows persisted (it may be a stale mirror) — refresh from CLOUD instead
│     → bounce OFF, «Локальный режим не активен»
└ LAN unreachable → nothing written, pin untouched → bounce OFF,
      «Локальный сервер недоступен»

Turn OFF → clear pin unconditionally + full cloud refresh in background
           («Обновлено из интернета» / «Нет соединения»)

Pull-to-refresh on «Команда» → refreshAll(raceId): when pinned, probeLocalAndRenew FIRST
           (heartbeat + handback detection on every pull), then fan-out via sourceFor
           (re-read — the probe may have just unpinned); success silent, failure →
           snackbar via refreshErrorMessage (existing convention)
```

Lease rules (all in pure functions, tested):
- **heartbeat**: every successful local manifest probe rewrites the lease via `renewedLease` —
  the server value wins once the backend ships a real lease, `null` → `nowMs +
  DEFAULT_LEASE_MS` (12 h client default). The probe fires at **three points**: switch-on,
  Launch B (team change / cold start), and a pinned pull-to-refresh — there is **no periodic
  timer**; an app left open without any of these keeps its last lease until expiry (acceptable:
  the admin-set lease is sized to outlive the race);
- **release**: expiry (implicit — `isPinned` returns false), local manifest says
  `data_source == "cloud"` (immediate, detected at the same three probe points), or the switch
  turned off;
- **connectivity loss never releases** — auto-syncs fail silently, cache lives on, cloud stays
  blocked by the guard until expiry.

## Technical Details

### SyncManifestDto

`data/api/dto/SyncDtos.kt` — `@Serializable`, snake_case, `ignoreUnknownKeys = true`:

```kotlin
@Serializable
data class SyncManifestDto(
    val race: Int,
    @SerialName("data_source") val dataSource: String,   // "cloud" | "local"
    @SerialName("lease_ttl_seconds") val leaseTtlSeconds: Long? = null,   // preferred: relative TTL (backend TODO)
    @SerialName("lease_expires_at") val leaseExpiresAt: Long? = null,     // fallback: epoch seconds; stubbed null today
)
```

**Why TTL is preferred**: a race-day fresh install may have a cold `TrustedClock` (no cloud
contact yet) and `localApiClient` deliberately carries no `ServerTimeInterceptor`, so lease
math against an **absolute** server timestamp is exposed to phone wall-clock skew (a skewed
clock could instantly expire a valid pin or over-pin past handback). A relative TTL is computed
against receipt time and is immune. The absolute fallback relies on ops keeping the local
server clock correct (see Post-Completion).

`versions{...}` is deliberately **not** mapped: the client never compares manifest versions
(they are opaque hashes; per-origin ETag/304 already answers "did it change").
`ignoreUnknownKeys` drops them. `fetchSync(raceId)` reuses `conditionalGet` with `etag = null`
→ `FetchResult`.

### RaceLease (pure) + RaceLeaseStore (prefs)

`data/lease/RaceLease.kt`:

```kotlin
data class RaceLease(val raceId: Int, val expiresAtMs: Long)

const val DEFAULT_LEASE_MS = 12 * 60 * 60 * 1000L  // client default while the server stubs null

fun renewedLease(raceId: Int, serverTtlSec: Long?, serverLeaseExpiresAtSec: Long?, nowMs: Long): RaceLease
    // expiry precedence: nowMs + serverTtlSec*1000 (relative, clock-skew-immune)
    //   → serverLeaseExpiresAtSec*1000 (absolute; relies on sane clocks)
    //   → nowMs + DEFAULT_LEASE_MS

fun isPinned(lease: RaceLease?, raceId: Int, nowMs: Long): Boolean
    // lease != null && lease.raceId == raceId && nowMs < lease.expiresAtMs

sealed interface LeaseAction {
    data class Renew(val lease: RaceLease) : LeaseAction  // manifest says local
    object Clear : LeaseAction                            // manifest says cloud (handback)
    object Keep : LeaseAction   // error / unreachable / wrong race / unknown data_source
}                               // (never renew on garbage — an unknown value must not pin)

fun applySyncResponse(manifest: SyncManifestDto?, raceId: Int, nowMs: Long): LeaseAction
```

This is all that remains of the old plan's `decideSmartRefresh` — the who-to-ask branching is
gone because the user picks the source explicitly.

`data/lease/RaceLeaseStore.kt` — copies the `ClockAnchorStore` shape verbatim: pure injected
`load`/`save`, single delimited key `"$raceId|$expiresAtMs"` (atomic — whole or absent),
`read(): RaceLease?` returns `null` on malformed input, `write`/`clear`,
`fromSharedPreferences(context)` adapter (`PREFS_NAME = "kolco24.lease"`). Synchronous read at
construction; `AppContainer` holds the current lease in a `MutableStateFlow<RaceLease?>` seeded
from the store — synchronous `.value` reads for the `isRacePinned` lambda (any thread),
collectable by the UI so the Settings switch tracks handback live; every mutation writes
through to prefs.

**Time source**: `nowMs` comes from a `nowMs: () -> Long` lambda owned by `AppContainer` —
trusted time when the `TrustedClock` anchor is warm, wall clock otherwise. Worst case (clock
change with no anchor) shifts auto-expiry, which the heartbeat keeps correcting.

### SyncSource routing + persist guard

`data/SyncSource.kt`: `enum class SyncSource { Cloud, Local }`.

Each of the four repos gains a second target and a pin probe, wired as constructor params from
`AppContainer` (lambda pattern, read at call time — same trick as `tokenProvider`):

```kotlin
class TeamRepository(
    private val apiClient: ApiClient,          // cloud (unchanged)
    private val origin: String,                // cloud origin (unchanged)
    private val localApiClient: ApiClient,
    private val localOrigin: String,
    private val isRacePinned: (raceId: Int) -> Boolean,
    ... daos
)
suspend fun refreshTeams(raceId: Int, source: SyncSource = SyncSource.Cloud): RefreshResult
```

- the method resolves `(client, originKey)` by `source`; the ETag row is naturally per-origin;
- **guard**: `source == Cloud && isRacePinned(raceId)` → return `RefreshResult.Skipped`
  **both** at method entry (don't even hit the network for a pinned race) **and** re-checked in
  the `200` branch before `replaceAllForRace` (an in-flight cloud response that started before
  the pin landed must not clobber fresh local rows);
- `RaceRepository.refreshRaces(source)` gets the source param but **no pin guard** (races is a
  global list, not race-scoped; it is fetched from LAN only inside `enterLocalMode` and inside
  `refreshAll` while pinned);
- `RefreshResult` gains a `Skipped` object (treated as success-with-no-change by callers;
  `refreshErrorMessage` returns `null` for it — the compiler will point at the `when`).

### SyncCoordinator

`data/sync/SyncCoordinator.kt` — thin orchestration owned by `AppContainer`; lease branching
lives in the pure `applySyncResponse`, the coordinator just executes. Constructor dependencies
are **lambda seams** (project idiom, same as `tokenProvider`): suspend function types for the
LAN `fetchSync` and the four per-source refresh calls, plus lease read/write and `nowMs` —
`AppContainer` binds them to the real repos, `SyncCoordinatorTest` injects fakes (no
MockWebServer/DAO setup):

- `sourceFor(raceId): SyncSource` — `Local` when pinned, else `Cloud`;
- `probeLocalAndRenew(raceId)` — LAN `fetchSync` → `applySyncResponse` → `Renew`/`Clear`/`Keep`
  applied to the lease state. Used by Launch B while pinned (heartbeat + handback detection);
- `enterLocalMode(): LocalModeOutcome` — the switch-on flow: resolve the race
  (`selectedTeam?.raceId`, else `nearestRaceId` over cached races; empty cache — fresh APK in
  the forest — first `refreshRaces(Local)` and recompute), LAN `fetchSync`, then per the
  approved flow above (pin + **Local** fan-out on `local`; no pin + **Cloud** fan-out on
  `cloud` — a server that disclaims authority must not have its race-scoped rows persisted;
  nothing written on unreachable). Fan-out = races+teams+legend+member-tags concurrently in a
  `supervisorScope`;
- `exitLocalMode(): LocalModeOutcome` — clear the lease unconditionally, full cloud fan-out;
- `refreshAll(raceId): RefreshResult` — the PTR body: when pinned, `probeLocalAndRenew` first
  (heartbeat + handback on every pull), then races+teams+legend+member-tags via
  `sourceFor(raceId)` re-read after the probe; the fan-out results are folded by a pure
  `combineRefreshResults(results): RefreshResult` with an explicit severity order
  (`HttpError > Forbidden > Offline > Updated > NotModified > Skipped`) so the snackbar is
  deterministic regardless of child completion order. Note this intentionally **broadens**
  the «Команда» pull from teams-only to the full resource set.

`LocalModeOutcome` enum for the switch Toasts: `PinnedUntil(expiresAtMs)` / `LocalNoPin` /
`LocalUnreachable` / `CloudUpdated` / `Offline` / `NoRace`.

### Auto-sync wiring

- **Launch A** (`Kolco24App.onCreate`): `refreshRaces()` stays Cloud (harmlessly `Offline` at
  the start line); nearest-race prefetch uses `sourceFor(nearest)`.
- **Launch B**: inside `collectLatest`, when `isPinned(raceId)` first `probeLocalAndRenew(raceId)`
  (heartbeat + immediate handback detection), then refresh legend/teams/member-tags with
  `sourceFor(raceId)` (re-read after the probe — it may have just unpinned). Unpinned races
  keep the exact current behavior — the LAN is never touched.
- **`MainActivity.onRaceSelected`**: prefetch with `sourceFor(raceId)`.

### UI

- **Settings «Данные» card** (`ui/settings/SettingsScreen.kt`, between «Запись трека» and
  «Администратор»): a Switch row «Локальный сервер (Wi-Fi гонки)» on the `EconomyModeRow`
  idiom, plus a busy state. Hoisted params: `localMode: Boolean`, `localModeBusy: Boolean`,
  `onLocalModeChange: (Boolean) -> Unit`. Subtitle: OFF → «Обновление из интернета», busy →
  spinner, ON → «Локальный режим до HH:MM» (`SimpleDateFormat("HH:mm")`, local timezone, from
  `expiresAtMs`).
- **`MainActivity`** hosts the state: `localModeBusy` flag; switch position derived from the
  **collected** lease `StateFlow` (collected once in `MainActivity` and threaded down — same
  pattern as theme/economy) via `isPinned(lease, raceId, now)` with `raceId =
  selectedTeam?.raceId ?: nearestRaceId` (the same resolution `enterLocalMode` uses — a pin
  held for a *different* race reads OFF, acceptable under the single-pin model). A handback
  landing from Launch B or a pull flips the switch off live even while Settings is open; pure
  time expiry has no ticker and shows on the next recomposition trigger / Settings open
  (minute-level staleness is fine). `enterLocalMode`/`exitLocalMode`
  launched on `container.applicationScope` (writes outlive overlays — project rule), outcome →
  Toast:
  - `PinnedUntil` → «Локальный режим до HH:MM»
  - `LocalNoPin` → «Локальный режим не активен — данные обновлены из интернета»
  - `LocalUnreachable` → «Локальный сервер недоступен»
  - `CloudUpdated` → «Обновлено из интернета»
  - `Offline` → «Нет соединения»
  - `NoRace` → «Нет данных о гонках»
- **Pull-to-refresh re-route** (`MainActivity` only — `TeamScreen` already wraps its list in
  `RefreshableList` and hoists `isRefreshing`/`onRefresh`): point «Команда»'s existing
  `onRefresh` at `syncCoordinator.refreshAll(raceId)` instead of `teamRepo::refreshTeams` —
  `refreshAll(raceId): RefreshResult` fits the existing `pullRefresh` helper signature exactly.
  «Легенда»'s pull switches its `refreshLegend` call to `source = sourceFor(raceId)` (otherwise
  a pinned pull would hit the cloud guard and silently no-op). The races picker's pull stays
  Cloud. Success silent, failure → snackbar via the existing `refreshErrorMessage` (extended
  with the `Skipped -> null` branch). No top-bar changes anywhere.

## What Goes Where

- **Implementation Steps**: everything client-side (this repo).
- **Post-Completion**: the backend lease TODO, local-server deployment env, on-site manual
  verification.

## Implementation Steps

### Task 1: Sync manifest DTO + `ApiClient.fetchSync`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/SyncDtos.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt`

- [x] add `SyncManifestDto` (`race`, `data_source`, `lease_ttl_seconds: Long? = null`,
      `lease_expires_at: Long? = null`; `versions` deliberately unmapped — document why in
      KDoc, incl. the TTL-vs-absolute skew rationale)
- [x] add `ApiClient.fetchSync(raceId): FetchResult<SyncManifestDto>` via `conditionalGet`
      with `etag = null` (endpoint has no 304)
- [x] write parsing tests: full manifest (both lease fields), stubbed manifest (both `null`),
      unknown `versions` keys ignored
- [x] write error tests: 404 (unknown race), offline → `FetchResult.Error`
- [x] run tests — must pass before task 2

### Task 2: `RaceLease` pure logic + `RaceLeaseStore`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/lease/RaceLease.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/lease/RaceLeaseStore.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/lease/RaceLeaseTest.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/lease/RaceLeaseStoreTest.kt`

- [x] `RaceLease` data class + `renewedLease` (precedence TTL → absolute → client default) +
      `isPinned` + `LeaseAction` + `applySyncResponse`
- [x] `RaceLeaseStore` on the `ClockAnchorStore` pattern: pure `load`/`save` seam, single
      delimited key, `read`/`write`/`clear`, `fromSharedPreferences`
- [x] `RaceLeaseTest`: renew precedence (TTL beats absolute beats default); pin
      match/mismatch/expiry boundary; **past server lease → `isPinned` false** (never a
      user-visible active pin); `applySyncResponse` — `local` → `Renew`, `cloud` → `Clear`,
      `null` manifest → `Keep`, manifest for another race → `Keep`, **unknown `data_source`
      → `Keep`** (never renews)
- [x] `RaceLeaseStoreTest`: round-trip; malformed (wrong segment count, non-numeric) → `null`;
      `clear`
- [x] run tests — must pass before task 3

### Task 3: `SyncSource` routing + cloud-persist guard in repos

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/SyncSource.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/RaceRepository.kt` (`RefreshResult.Skipped` + source param)
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/TeamRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/LegendRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/MemberTagsRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/common/PullToRefresh.kt` (`refreshErrorMessage`: `Skipped -> null`)
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: repo unit tests (pattern: `MemberTagsRepositoryTest`)

- [ ] add `SyncSource { Cloud, Local }`; extend repo constructors with
      `localApiClient`/`localOrigin`/`isRacePinned` (races repo: no pin lambda); add
      `source: SyncSource = Cloud` param to the four refresh methods — existing call sites
      compile unchanged
- [ ] add `RefreshResult.Skipped`; fix every exhaustive `when` the compiler flags
      (`refreshErrorMessage` → `null`)
- [ ] guard in Team/Legend/MemberTags repos: `Cloud` + pinned → `Skipped` at entry **and**
      re-checked in the `200` branch before persist
- [ ] `AppContainer`: build the lease store + `MutableStateFlow<RaceLease?>` lease state +
      `nowMs` lambda (trusted-time-or-wall), wire repos
- [ ] tests: `Local` source hits the local client and stores the ETag under the LAN origin;
      `Cloud` + pinned → `Skipped`, nothing persisted, cloud client not called; `Cloud` +
      pin-appearing-mid-flight (pin flips between fetch and persist) → nothing persisted;
      unpinned `Cloud` behavior unchanged
- [ ] run tests — must pass before task 4

### Task 4: `SyncCoordinator` + pin-aware auto-syncs

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/sync/SyncCoordinator.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/Kolco24App.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` (`onRaceSelected` only)
- Create: `app/src/test/java/ru/kolco24/kolco24/data/sync/SyncCoordinatorTest.kt`

- [ ] `SyncCoordinator` with **lambda-seam constructor** (suspend fun types for LAN `fetchSync`
      + the four per-source refresh calls, lease read/write, `nowMs`); `sourceFor`,
      `probeLocalAndRenew` (executes `applySyncResponse`), `enterLocalMode` (race resolution
      incl. empty-cache LAN-races fallback; `data_source=cloud` → no pin + **Cloud** fan-out;
      unreachable → no writes, lease untouched), `exitLocalMode` (clear lease + Cloud fan-out),
      `refreshAll(raceId)` for PTR (pinned → probe first, then fan-out via re-read `sourceFor`);
      returns `LocalModeOutcome`/`RefreshResult` for the UI
- [ ] pure `combineRefreshResults(results): RefreshResult` — explicit severity order
      `HttpError > Forbidden > Offline > Updated > NotModified > Skipped`
- [ ] Launch B: pinned race → probe first, then refresh via `sourceFor` (re-read after probe);
      Launch A nearest-race prefetch + `onRaceSelected` prefetch via `sourceFor`
- [ ] tests (injected fake lambdas — no MockWebServer/DAO): `sourceFor` pinned/unpinned; probe
      renews on `local`, clears on `cloud`, keeps lease on error; `enterLocalMode` — empty
      cache pulls races from LAN first, `local` pins + Local fan-out, `cloud` → no pin + Cloud
      fan-out (no LAN race-scoped rows persisted), unreachable writes nothing, past server
      lease → outcome is not `PinnedUntil`; `exitLocalMode` always unpins; `refreshAll` —
      pinned probes first (renews / detects handback and falls back to Cloud fan-out),
      unpinned never touches the LAN; `combineRefreshResults` severity table
- [ ] run tests — must pass before task 5

### Task 5: Settings «Данные» card with the local-mode switch

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] «Данные» card between «Запись трека» and «Администратор»: Switch row «Локальный сервер
      (Wi-Fi гонки)» on the `EconomyModeRow` idiom; subtitle OFF/«до HH:MM»/busy spinner;
      params hoisted (`localMode`, `localModeBusy`, `onLocalModeChange`)
- [ ] `MainActivity`: collect the lease `StateFlow` once and derive the switch position
      (`isPinned` with `selectedTeam?.raceId ?: nearestRaceId`); `localModeBusy` flag; launch
      `enterLocalMode`/`exitLocalMode` on `applicationScope`; map `LocalModeOutcome` → RU
      Toast strings (see Technical Details)
- [ ] no tests (Compose UI + wiring — untested by convention)
- [ ] run full unit suite — must pass before task 6

### Task 6: Re-route the existing pull-to-refresh through the coordinator

PTR is **already implemented** on «Команда»/«Легенда»/races picker (`RefreshableList` +
`pullRefresh` helper in `MainActivity`, ~line 538) — this task only changes what the gesture
calls. `TeamScreen.kt` is untouched.

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] «Команда»: point the existing `onRefresh` (~line 1112) at
      `syncCoordinator.refreshAll(raceId)` instead of `teamRepo::refreshTeams` (fits the
      existing `pullRefresh` signature; intentionally broadens the pull to
      races+teams+legend+member-tags)
- [ ] «Легенда»: pass `source = sourceFor(raceId)` to its existing pull `refreshLegend` call
      (a pinned pull must go to LAN, not silently `Skipped` by the cloud guard); races picker
      pull stays Cloud
- [ ] no tests (wiring — untested by convention; `refreshErrorMessage`'s new `Skipped` branch
      is compiler-enforced)
- [ ] run full unit suite — must pass before task 7

### Task 7: Verify acceptance criteria

- [ ] all approved switch-flow branches implemented (pin on `local`, no-pin + Cloud fan-out on
      `cloud`, bounce on unreachable, exit = unpin + cloud, heartbeat, handback, in-flight
      cloud guard, connectivity loss never releases)
- [ ] `./gradlew testDebugUnitTest` — green
- [ ] `./gradlew lintDebug` — green (watch `NewApi` on any new API usage)
- [ ] `./gradlew assembleDebug` — builds

### Task 8: [Final] Update documentation

- [ ] update `CLAUDE.md`: lease/pin subsystem (`data/lease/`, `data/sync/`), `SyncSource`
      routing + persist guard convention, the Settings «Данные» card, the re-routed PTR
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

**Backend TODO (hand to backenders — one item):**
- Real per-race lease in `SyncView` (`src/apps/mobile/views.py`): replace the stub with
  `data_source` resolved per race and an **admin-settable** lease duration (the admin sets it
  ≈ time to drive to civilization + run the local→cloud sync; must outlive the post-race
  reconciliation window). **Prefer emitting `lease_ttl_seconds` (relative)** — immune to phone
  clock skew on race-day fresh installs; `lease_expires_at` (absolute epoch seconds) is the
  accepted fallback. The client already handles both plus its 12 h default — no app update
  needed when this ships.

**Deployment / ops:**
- Local race-day server sets `MOBILE_DATA_SOURCE=local` (env already supported).
- Keep the local server's clock correct (NTP or manual sync) — required for the absolute
  `lease_expires_at` fallback and generally sane HTTP behavior; irrelevant once the backend
  emits the relative TTL.
- If the LAN host ever differs from `192.168.1.5`, update `LOCAL_API_BASE_URL` **and**
  `res/xml/network_security_config.xml` in lockstep (documented coupling).

**Manual verification on site:**
- Fresh install with no internet: switch on → races → teams/legend/member-tags from LAN, pin
  appears (subtitle shows «до HH:MM»).
- Mid-race Wi-Fi loss + mobile data on: auto-syncs do **not** fetch the pinned race from
  cloud; PTR on «Команда» fails silently to cache (snackbar on error), pin survives.
- Admin reaches civilization, syncs, lease expires: cloud syncs resume by themselves; the
  Settings switch reads OFF on next open.
- Local server switched to `MOBILE_DATA_SOURCE=cloud`: next pinned auto-sync/probe unpins the
  phone automatically.
