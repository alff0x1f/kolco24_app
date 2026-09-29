# Separate Cloud and LAN Admin Sessions

## Overview
- Admin login (Settings → «Администратор») authenticates only against the cloud server
  (`AdminAuthRepository(apiClient)`). The LAN race-day server has its own `POST /app/login/` and its own
  users/tokens, so a cloud token is useless there.
- Worse: one shared `AppSignatureInterceptor` (with `tokenProvider = { adminAuthRepository.token() }`) sits on
  both `apiClient` and `localApiClient`, so the cloud bearer is sent to the LAN host over cleartext HTTP.
- Fix: two independent admin sessions (cloud + LAN), one login form, per-client bearer, and `bindTag` routed by
  the race lease (pinned → LAN, else cloud). The password is sent to the cleartext LAN host **only while a race
  lease is active** (local mode on, the race server recently confirmed itself).

## Context (from discovery)
- `data/AdminAuthRepository.kt` — `AdminSession`, `LoginOutcome`, `loginOutcome`, `adminErrorMessage`, `isExpired`.
- `data/AdminTokenStore.kt` — prefs file `kolco24.admin`, 3 keys, `fromSharedPreferences(context)`.
- `AppContainer.kt` — shared `signatureInterceptor` (~l.116), `apiClient`, `localApiClient` (~l.163),
  `raceLease: MutableStateFlow<RaceLease?>` (~l.210), private `nowMs` / `isRacePinned` (~l.227-230),
  `adminAuthRepository` (~l.504).
- `data/api/AppSignatureInterceptor.kt` — `tokenProvider` adds `Authorization: Bearer` (unchanged).
- `data/api/ApiClient.kt` — `login` (~l.202) posts `{email, password}` in the body.
- `data/lease/RaceLease.kt` — pure `isPinned(lease, raceId, nowMs)`.
- `ui/admin/AdminScreen.kt` — `when (session)` gate (~l.103), login form (~l.144), `AdminHome` (~l.233),
  logout (~l.348).
- `ui/admin/ProvisioningScreen.kt` — `onTag` (~l.217), `container.apiClient.bindTag` (~l.241),
  `401` → `onUnauthorized()` + `onClose()` (~l.282).
- `ui/settings/SettingsScreen.kt` — `AdminRow` subtitle (~l.882).
- `MainActivity.kt` — `adminSession` collected (~l.724), passed to Settings (~l.2165) and `AdminScreen` (~l.2277).
- `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` — exclude `kolco24.admin.xml`.
- Tests: `AdminAuthRepositoryTest`, `AdminTokenStoreTest`, `data/api/SigningTest` (already checks bearer
  present/absent), lease tests.
- Other admin screens: CheckChip / CheckMemberChip are Room-only; JudgeScan uploads are HMAC-only. `bindTag` is
  the only bearer-protected call.

## Development Approach
- **testing approach**: Regular (code first, then tests)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task (pure logic and trust
  boundaries; Compose UI and `AppContainer` wiring are untested by project convention)
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./gradlew testDebugUnitTest` after each change
- maintain backward compatibility: the existing cloud session in `kolco24.admin` must survive the upgrade

## Testing Strategy
- **unit tests** (JVM): `combinedLoginOutcome`, `adminRowSubtitle`, `isLeaseActive`.
- no e2e UI tests in this project; UI and wiring verified manually (see Post-Completion).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
- **Two `AdminAuthRepository` instances**: `cloudAdminAuth` (on `apiClient`, prefs `kolco24.admin` — the existing
  file, so current sessions survive) and `localAdminAuth` (on `localApiClient`, prefs `kolco24.admin.local`).
  The class itself does not change.
- **Two signing interceptors** built by one private factory `signingInterceptor(tokenProvider)` in
  `AppContainer` (same key/secret/clock), each bound to its own repository. The cloud token can no longer reach
  the LAN host.
- **LAN login gate**: LAN login is attempted only when `container.isLanActive()` (a non-expired race lease, using
  the same trusted-or-wall `nowMs` as all other lease math). Otherwise the LAN row says «Включите локальный
  режим гонки» and no password goes to the cleartext host.
- **One login form** logs into every targeted repo in parallel. Any success → admin home. All fail → one combined
  error where a real answer (`InvalidCredentials`, `RateLimited`) beats `Error`, which beats `Offline`.
- **`bindTag` routed per tap** via public `container.isRacePinned(raceId)` (trusted-or-wall time, same as sync and
  the Settings switch): pinned → `localApiClient` + `localAdminAuth`, else `apiClient` + `cloudAdminAuth`.
  `401` clears only the auth that was used.
- **Admin screens gated by any active session** (judge scans / chip checks need no bearer).
- Out of scope: `JudgeScanScreen`'s `apiClient.fetchSync` stays on cloud (it exists to anchor trusted time via
  `ServerTimeInterceptor`, which the LAN client deliberately lacks; no bearer involved).

## Technical Details
- `AdminTokenStore.fromSharedPreferences(context, prefsName: String = PREFS_NAME)`; add
  `const val LOCAL_PREFS_NAME = "kolco24.admin.local"`.
- `RaceLease.kt`: pure `fun isLeaseActive(lease: RaceLease?, nowMs: Long): Boolean` (non-null, not expired).
- `AppContainer`: make `isRacePinned` public; add `fun isLanActive(): Boolean = isLeaseActive(raceLease.value, nowMs())`.
- `AdminAuthRepository.kt`, new pure functions:
  ```kotlin
  fun combinedLoginOutcome(outcomes: List<LoginOutcome>): LoginOutcome
  fun adminRowSubtitle(cloud: AdminSession, local: AdminSession): String
  ```
  `combinedLoginOutcome`: rank `Success` > `InvalidCredentials` > `RateLimited` > `Error` > `Offline`, highest
  wins; empty list → `Error`.
  `adminRowSubtitle`: both logged in → cloud email; only one → `"$email · только Cloud"` / `"$email · только LAN"`;
  none → existing logged-out text.
- **AdminScreen state**: `rememberSaveable` `loginTargets: Set<String>?` (`"cloud"`, `"lan"`; `null` = home).
  - Both sessions `LoggedOut` → form, targets = `{cloud}` + `{lan}` if `isLanActive()`.
  - Otherwise home. «Войти на Cloud» / «Войти на LAN» (LAN button only when `isLanActive()`) sets
    `loginTargets` → form with email prefilled; success clears `loginTargets`; `BackHandler` cancels it.
- **Parallel login**: `async` per target on `applicationScope`, `awaitAll`, then error via
  `adminErrorMessage(combinedLoginOutcome(...))`. Accepted: the error waits for the slowest server (cloud
  10 s timeout in the forest). The session flow switches to home as soon as one repo succeeds. A failed second
  server shows only in its status row.
- **AdminHome**: one row per server with its own email — «Cloud — email» / «Cloud — нет входа»;
  «LAN — email» / «LAN — нет входа» / «LAN — включите локальный режим гонки».
- **Logout**: one `applicationScope.launch` per `LoggedIn` repo (independent, so the LAN logout never waits on a
  cloud timeout; no pointless `/app/logout/` for a logged-out repo).
- **Provisioning, per tap** inside `onTag`: pick `(client, auth)` from `isRacePinned(raceId)`; if `auth` is
  `LoggedOut` → `ProvisionState.Failed("Нет входа на LAN-сервер" / "Нет входа на cloud-сервер")` + failure
  beep, no POST (make sure the `isBusy` lock is released). Capture `(client, auth)` in the launched coroutine so
  the `401` branch clears the same auth. After a `401`, the overlay closes as today; if the other session is
  still active the user lands on AdminHome with that server marked «нет входа» (intended).
- LAN server requirement: `expires_at` must be fixed-width `yyyy-MM-dd'T'HH:mm:ss'Z'` UTC (lexicographic
  `isExpired`).

## What Goes Where
- **Implementation Steps**: code in this repo, JVM tests, docs.
- **Post-Completion**: manual checks on device against real cloud + LAN servers; LAN server contract.

## Implementation Steps

### Task 1: Parameterize admin token store and add LAN backup exclusion

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/AdminTokenStore.kt`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

- [x] add `prefsName` param (default = existing `kolco24.admin`) to `fromSharedPreferences`; add `LOCAL_PREFS_NAME`
- [x] exclude `kolco24.admin.local.xml` in `backup_rules.xml` and in both `cloud-backup` and `device-transfer`
      blocks of `data_extraction_rules.xml`
- [x] confirm `AdminTokenStoreTest` still passes unchanged (pure store; adapter untested by convention)
- [x] run tests - must pass before next task

### Task 2: Add pure helpers: `combinedLoginOutcome`, `adminRowSubtitle`, `isLeaseActive`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/AdminAuthRepository.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/lease/RaceLease.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/AdminAuthRepositoryTest.kt`
- Modify: lease unit test file (next to existing `isPinned` tests)

- [x] implement `combinedLoginOutcome` with the rank from Technical Details (empty list → `Error`)
- [x] implement `adminRowSubtitle(cloud, local)` (move the current logged-out text into it)
- [x] implement `isLeaseActive(lease, nowMs)`
- [x] write tests for `combinedLoginOutcome`: empty, single, any `Success` wins, `Offline + InvalidCredentials`
      → `InvalidCredentials`, `Offline + RateLimited` → `RateLimited`, `Offline + Error` → `Error`, both `Offline`
- [x] write tests for `adminRowSubtitle`: both / cloud only / LAN only / none
- [x] write tests for `isLeaseActive`: null, active, exact expiry boundary, expired
- [x] run tests - must pass before next task

### Task 3: Split the admin session and signing interceptor per server in `AppContainer`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [x] add private `signingInterceptor(tokenProvider: () -> String?)` factory; build `cloudSignatureInterceptor`
      and `localSignatureInterceptor` from it, each bound by lambda to its own repo (keeps the lazy-init cycle broken)
- [x] replace `adminAuthRepository` with `cloudAdminAuth` (store `kolco24.admin`) and `localAdminAuth`
      (store `LOCAL_PREFS_NAME`, `localApiClient`)
- [x] make `isRacePinned` public; add `isLanActive()`
- [x] update all `adminAuthRepository` references so the project compiles (temporarily point UI at `cloudAdminAuth`;
      real UI changes in Tasks 4-6)
- [x] update KDocs that describe a shared interceptor / single repo: `AppContainer.kt` ~l.108-113, 161, 498, 503
- [x] no new tests (wiring untested by convention; `tokenProvider` absent/present already covered by `SigningTest`);
      run tests

### Task 4: Login form, admin home and logout for both servers

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/AdminScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] `MainActivity`: collect `cloudAdminSession` and `localAdminSession`; pass both to `AdminScreen`
- [x] `AdminScreen`: add `loginTargets` `rememberSaveable` state and gating per Technical Details; `BackHandler`
      cancels a re-login form back to home
- [x] login: parallel `async` per target on `applicationScope` (LAN only when `isLanActive()`); error via
      `adminErrorMessage(combinedLoginOutcome(...))`; success clears `loginTargets`
- [x] `AdminHome`: per-server status rows with own email; «Войти на Cloud» / «Войти на LAN» buttons
- [x] logout: one `applicationScope.launch` per `LoggedIn` repo
- [x] update KDocs `AdminScreen.kt` ~l.63-70, 124
- [x] no new tests (Compose UI untested by convention; logic covered in Task 2); run tests

### Task 5: Settings row subtitle

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] `SettingsScreen` / `AdminRow` take both sessions; subtitle from `adminRowSubtitle`
- [x] no new tests (helper tested in Task 2); run tests

### Task 6: Route `bindTag` by race lease in provisioning

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt`

- [x] in `onTag`, per tap: pick `(client, auth)` via `container.isRacePinned(raceId)`
- [x] chosen `auth` is `LoggedOut` → `ProvisionState.Failed(...)` + failure beep, skip POST, release `isBusy`
- [x] capture `(client, auth)` in the launched coroutine; `401` → `auth.onUnauthorized()` only
- [x] update KDoc `ProvisioningScreen.kt` ~l.103
- [x] no new tests (`isPinned` already tested; UI untested by convention); run tests

### Task 7: Verify acceptance criteria
- [x] cloud bearer is never attached to `localApiClient` requests (and vice versa)
- [x] no LAN login request without an active lease
- [x] existing cloud session survives upgrade (same prefs file)
- [x] run full test suite: `./gradlew testDebugUnitTest`
- [x] run lint: `./gradlew lintDebug`
- [x] build: `./gradlew assembleDebug`

### Task 8: [Final] Update documentation
- [x] `docs/design/DATA-NOTES.md`: two admin sessions, two interceptors, two prefs files, backup exclusion,
      LAN login gated by lease (fix the line quoting `tokenProvider = { adminAuthRepository.token() }`)
- [x] `docs/design/UI-NOTES.md`: admin home status rows, `loginTargets` re-login flow, Settings subtitle,
      provisioning routing and per-tap no-session failure
- [x] `docs/mobile-admin-auth-and-tags.md`: LAN section — own `/app/login/`, same `expires_at` format, tokens not
      interchangeable, password sent only in local mode, `bindTag` goes to LAN while race is pinned
- [x] `CLAUDE.md` if a new convention emerged
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**
- upgrade from a build with a cloud admin session → still logged in to cloud, LAN shows «включите локальный режим»
- home Wi-Fi, no lease: login sends nothing to `192.168.1.5` (check with proxy/sniffer)
- event Wi-Fi, lease active, no internet: login → LAN ✓, cloud «нет входа»; provisioning on the pinned race binds via LAN
- wrong password against LAN while cloud is offline → «Неверный email или пароль»
- LAN server restarted with new token DB → bind returns 401, only LAN session is cleared
- sniff LAN traffic: no cloud token in `Authorization`

**External system updates:**
- LAN server must implement `POST /app/login/` / `POST /app/logout/` and bearer check on
  `POST /app/race/<id>/tags/`, with `expires_at` in `yyyy-MM-dd'T'HH:mm:ss'Z'`
- tags bound on LAN while pinned are not mirrored to cloud by the app; the server side must sync them back
  after handback
