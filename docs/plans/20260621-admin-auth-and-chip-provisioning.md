# Race-Admin Login & Bulk NFC Chip Provisioning

## Overview

Two backend-doc-driven features (spec: `docs/mobile-admin-auth-and-tags.md`):

1. **Race-admin login** — email/password → 30-day opaque bearer token, token use on protected requests, logout.
2. **Bulk NFC chip provisioning** — an admin binds physical NFC chips to checkpoints via `POST /app/race/<id>/tags/` and writes the server-returned hex `code` onto each chip, so the app can later recognise the КП offline.

The provisioning UX is a swipe-through pager over the race's checkpoints (no per-chip КП picking): one КП per page, NFC armed throughout, a scanned chip binds to the currently shown КП, swipe to the next КП. This solves the "50 КП × 2 chips = 100 manual selections" problem identified during design.

Integrates on top of the existing signed `/app/*` networking layer, the manual-DI `AppContainer`, the established overlay pattern in `MainActivity`, and the existing `MifareUltralightWriter` chip-write primitives.

## Context (from discovery)

- **Files/components involved:**
  - `data/api/AppSignatureInterceptor.kt` (+ `SigningTest.kt`) — HMAC build signing; currently hardcodes empty-body hash, no bearer.
  - `data/api/ApiClient.kt` (+ `ApiClientTest.kt`) — `FetchResult` + `conditionalGet`; GET-only today.
  - `data/InstallId.kt`, `data/ThemePreference.kt` (+ `ThemePreferenceTest.kt`) — pure load/save store pattern to mirror.
  - `AppContainer.kt` — manual DI; constructs interceptor + repositories.
  - `MainActivity.kt` — overlay host (`rememberSaveable` flags, `Box`, `BackHandler`).
  - `ui/settings/SettingsScreen.kt` — stateless card/row pattern (`ChangeTeamRow`/`ThemeRow`).
  - `ui/legend/CheckpointColor.kt` + `LegendScreen.kt` — `parseCheckpointColor` + `barColor()` (color material for the rail/hero).
  - `data/nfc/MifareUltralightWriter.kt` — `writeChipCodeNdef`, `chipCodeFromHex`.
  - `data/NfcUid.kt` — `normalizeNfcUid`.
  - `data/LegendRepository.kt` / `TagDao` / `TagEntity` — cached КП tags (pre-seed bound counts).
  - `data/api/dto/*` — `@Serializable` snake_case DTO convention.
- **Related patterns found:** `FetchResult` sealed type + collapse-exceptions-to-result; reactive `StateFlow` repositories (e.g. `ThemePreference.mode`); overlay = `rememberSaveable` flag + render-in-`Box` + dedicated `BackHandler` + reset on `onScanClick`/team-switch; high-priority `onTagForWrite` raw-`Tag` hook in `MainActivity`.
- **Dependencies identified:** okio (already present via OkHttp) for `Buffer` body hashing; no new third-party deps. No Room schema change.

## Development Approach

- **Testing approach:** Regular (code first, then tests) — matches the repo's existing test placement; pure/logic units get JVM unit tests, Compose UI is left untested (consistent with `ScanScreen`/`TeamScreen` being untested while their pure models are tested).
- Complete each task fully before the next; small focused changes.
- **Every task with logic changes includes new/updated tests** (pure functions, stores, result mapping). Compose-only tasks have no unit tests by repo convention — noted explicitly per task.
- **All tests pass before starting the next task.**
- Run `./gradlew testDebugUnitTest` after logic changes; `./gradlew lintDebug` before finishing.
- Maintain backward compatibility: existing GET endpoints must keep signing identically (empty-body hash path unchanged).
- Update this plan when scope changes during implementation.

## Testing Strategy

- **Unit tests (JVM, required):**
  - `SigningTest.kt` — extend with a POST-body canonical/signature vector and an empty-body-still-matches assertion.
  - `ApiClientTest.kt` — POST success + each mapped status (`400/401/403/409/429`) + offline, using the existing MockWebServer setup.
  - `AdminTokenStoreTest.kt` — round-trip of token/email/expiry; saving `null` clears all three keys; synchronous initial read.
  - `AdminAuthRepositoryTest.kt` — login outcome mapping, `logout()` clears locally even on network failure, `onUnauthorized()` clears, lazy expiry → `LoggedOut`. (Inject a fake `ApiClient` seam or a small pure mapper — see Task 6.)
  - Pure error-message mapping: extract `adminErrorMessage(...)`/`provisionErrorMessage(...)` as testable top-level funcs (decision: **extract + test**, since the strings are user-facing and worth pinning).
- **e2e tests:** none — project has no UI e2e harness. Compose screens (`AdminScreen`, `ProvisioningScreen`) are manually verified (see Post-Completion).
- **Instrumented tests:** none added (no Room migration, no DAO change).

## Progress Tracking

- Mark completed items `[x]` immediately when done.
- New tasks: `➕` prefix. Blockers: `⚠️` prefix.
- Keep this file in sync with actual work.

## Solution Overview

Build bottom-up in four layers so each rests on a tested foundation:

1. **Networking plumbing** (shared): real body-hash signing + bearer header + a `post()` path with a `PostResult` type. Everything else depends on this.
2. **Auth layer:** a plain-SharedPreferences token store, login/logout/bindTag API methods + DTOs, and a reactive `AdminAuthRepository` (the `StateFlow<AdminSession>` source of truth whose token feeds the interceptor's `tokenProvider`).
3. **Admin overlay + login UI:** a dedicated full-screen overlay launched from a `SettingsScreen` «Администратор» card, branching on session (login form ↔ admin home).
4. **Provisioning pager:** a `HorizontalPager` over the race checkpoints with the signature "provisioning rail", binding scanned chips to the settled current КП and writing the returned `code`.

**Key design decisions & rationale:**
- *Bearer in the interceptor* (not per-call): one place, read fresh per request like `ts`, so login/logout transitions need no client rebuild; never part of the canonical string (server ignores it for signing).
- *Plain SharedPreferences* for the token: mirrors `InstallId`/`ThemePreference`, no new dep; the token is a revocable 30-day bearer, not a password.
- *No Room change*: the `tags` POST response isn't persisted directly — the next legend refresh delivers the new tag via the existing `tags[]` array; provisioning only *reads* cached `TagEntity` counts to pre-seed coverage.
- *Pager over picker*: position is the selection; binds only to the settled `currentPage` (`!isScrollInProgress`) so a mid-swipe scan can't bind to the wrong КП.

## Technical Details

- **Canonical string** (unchanged shape): `METHOD\nfull_path\nts\nsha256_hex(body)`; only the 4th part becomes dynamic. Empty/GET body → `EMPTY_BODY_SHA256` constant (byte-identical to hashing empty bytes, so existing tests still pass).
- **Body hashing:** `okio.Buffer().also { request.body?.writeTo(it) }.readByteArray()` → SHA-256 → lower-hex. **Invariant:** all POST bodies are exact in-memory `ByteArray` `RequestBody`s (built in Task 2), which are repeatable — OkHttp re-invokes `writeTo` when sending and gets identical bytes. The interceptor is shared infra, so it must **guard against one-shot bodies** (`request.body?.isOneShot() == true` → skip buffering, fall back to `EMPTY_BODY_SHA256`) and **wrap `writeTo` in try/catch** so an `IOException` can never crash `intercept`. Null body → `EMPTY_BODY_SHA256`.
- **`PostResult<T>`** sealed: `Success(data)`, `BadRequest`, `Unauthorized`, `Forbidden`, `Conflict`, `RateLimited`, `Offline`, `Error(code)`. The `parse` callback is invoked **only** on the `200/201` branch (never on error bodies), so an empty `logout` body never reaches a JSON decoder.
- **`AdminSession`** sealed: `LoggedOut` | `LoggedIn(email, token, expiresAt)`. Expiry stored as the raw ISO string from the server (`2026-07-21T14:03:00Z`, UTC, `Z` suffix). The lazy check does a **string lexicographic compare** against a `now` formatted in the **exact same shape** — `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)` with `timeZone = TimeZone.getTimeZone("UTC")` (NOT `todayIso()`, which is date-only in the device zone and would compare wrong). Lexicographic compare is valid because the format is fixed-width UTC. Stays `java.time`-free. This pure compare is a named unit test (Task 6).
- **Provisioning state** per active scan: `WaitingForChip → Binding(uid) → Writing(code) → Success(number) → Failed(reason)`.
- **Bind→write per chip:** `bindTag(raceId, checkpoint.id, normalizeNfcUid(tag.id))` → on `Success` `chipCodeFromHex(code)` → `writeChipCodeNdef(tag, codeBytes, packageName)` off the main thread.
- **NFC hook:** provisioning needs the raw `Tag` (to write), exactly like the existing debug `WriteChipDialog` flow, which already owns the top-priority `onTagForWrite` hook. To avoid coupling/collision, add a **distinct `@Volatile var onTagForProvision: ((Tag) -> Unit)? = null`** hook to `MainActivity`, inserted into the `onTagDiscovered` priority chain **immediately after `onTagForWrite`** (new order: `onTagForWrite → onTagForProvision → onTagForMark → onTagScanned → idle`). Provisioning (under `showAdmin`) and the debug dialog (under `showSettings`) are never armed simultaneously — and opening admin resets `showSettings` (Task 9) — but a separate hook removes any ordering risk and keeps each `DisposableEffect` owning exactly one hook.
- **Rail tick state** per КП: `empty` (0 chips), `filled` (≥1 chip), `current` (enlarged). Bound count = cached `TagEntity` rows for `checkpoint.id` + this session's freshly-written tokens.

## What Goes Where

- **Implementation Steps** (`[ ]`): all code, tests, CLAUDE.md update — achievable in this repo.
- **Post-Completion** (no checkboxes): on-device manual verification with a real backend admin account + physical NFC chips (login, bind 201/200/409, chip-write, offline/401 behavior) — requires hardware and server credentials.

## Implementation Steps

### Task 1: Body-hash + bearer in the signing interceptor

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/AppSignatureInterceptor.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/SigningTest.kt`

- [x] Change `buildCanonical(method, fullPath, ts)` → `buildCanonical(method, fullPath, ts, bodyHash)` (keep it a pure top-level fn); update the one existing GET caller to pass `EMPTY_BODY_SHA256`.
- [x] Add a pure top-level `sha256Hex(bytes: ByteArray): String` helper (lower-hex).
- [x] In `intercept`, compute the body hash from the built request: `okio.Buffer` + `request.body?.writeTo(buffer)`; null body **or `request.body?.isOneShot() == true`** → `EMPTY_BODY_SHA256`. Wrap `writeTo` in try/catch (any `IOException` → `EMPTY_BODY_SHA256` fallback) so it can never crash the interceptor. Sign canonical with the result.
- [x] Add `tokenProvider: () -> String? = { null }` constructor param; when it returns non-null add `Authorization: Bearer <token>` (read fresh per call; not in canonical).
- [x] Write test: POST-body canonical + signature vector (known body → expected hash) and assert bearer header added when provider non-null / absent when null.
- [x] Write test: empty/GET body still produces `EMPTY_BODY_SHA256` (no regression).
- [x] Run tests — must pass before Task 2.

### Task 2: `PostResult` + POST path in `ApiClient`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt`

- [x] Add `sealed interface PostResult<out T>` with `Success(data)`, `BadRequest`, `Unauthorized`, `Forbidden`, `Conflict`, `RateLimited`, `Offline`, `Error(code)`.
- [x] Add a private `post(url, bodyBytes: ByteArray, parse: (String) -> T): PostResult<T>` on `Dispatchers.IO`, mapping `200/201→Success`, `400→BadRequest`, `401→Unauthorized`, `403→Forbidden`, `409→Conflict`, `429→RateLimited`, else `Error(code)`; `IOException→Offline`, `SerializationException→Error(null)`. Build the `RequestBody` from the exact `bodyBytes` with `application/json`. **`parse` is invoked only on the `200/201` branch** (error bodies are never parsed), so a `{ Unit }` parser for an empty `logout` body is safe. (Made `internal` rather than strictly `private` so Task 2's tests can exercise it directly before the typed Task 4 methods exist.)
- [x] Write tests for POST success (200 + 201) and each status mapping + offline, reusing the MockWebServer harness.
- [x] Run tests — must pass before Task 3.

### Task 3: Admin DTOs

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/AuthDtos.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/data/api/dto/TagDtos.kt`

- [ ] `LoginRequest(email, password)`, `LoginResponse(token, @SerialName("expires_at") expiresAt)` — `@Serializable`.
- [ ] `TagBindRequest(@SerialName("checkpoint_id") checkpointId, @SerialName("nfc_uid") nfcUid)`, `TagBindResponse(bid, @SerialName("checkpoint_id") checkpointId, number, @SerialName("nfc_uid") nfcUid, code)` — `@Serializable`.
- [ ] (No unit test — declarative DTOs; serialization exercised via Task 4/Task 6 tests.) Note in commit.
- [ ] Run build (`assembleDebug` compile) — must pass before Task 4.

### Task 4: ApiClient login/logout/bindTag methods

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/data/api/ApiClient.kt`
- Modify: `app/src/test/java/ru/kolco24/kolco24/data/api/ApiClientTest.kt`

- [ ] `login(email, password): PostResult<LoginResponse>` — serialize `LoginRequest` to bytes once, POST `/app/login/`.
- [ ] `logout(): PostResult<Unit>` — POST `/app/logout/` with an empty `ByteArray` body (still hashes the empty string → `EMPTY_BODY_SHA256`); parser is `{ Unit }` and never touches the body.
- [ ] `bindTag(raceId, checkpointId, nfcUid): PostResult<TagBindResponse>` — POST `/app/race/<raceId>/tags/`.
- [ ] Write tests: `login` happy path + 401/429; `bindTag` 201 + 200 (**assert the `200` body parses into `TagBindResponse` carrying `code`** — the write/rewrite path depends on it) + 409 + 404(→`Error(404)`); `logout` 200 with an **empty body** → `Success(Unit)` (not `Error` — guards the empty-body-parse pitfall).
- [ ] Run tests — must pass before Task 5.

### Task 5: `AdminTokenStore`

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/AdminTokenStore.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/AdminTokenStoreTest.kt`

- [ ] Follow the `ThemePreference` *pattern* (pure injected store + `fromSharedPreferences` adapter, synchronous read at construction, no `java.time`) — but with a **multi-key seam** `load: (String) -> String?` + `save: (String, String?) -> Unit` because three keys are persisted (the shape deliberately differs from `ThemePreference`'s single-value seam).
- [ ] Expose `read(): StoredSession?` (token+email+expiresAt) and `write(token, email, expiresAt)` / `clear()`; `clear()`/null writes remove all three keys (`admin_token`, `admin_token_expires_at`, `admin_email`).
- [ ] Add `companion object fun fromSharedPreferences(context)` → prefs file `"kolco24.settings"`, `MODE_PRIVATE`, `apply()`.
- [ ] Write tests: round-trip; null/clear removes all three keys; initial read reflects pre-seeded store.
- [ ] Run tests — must pass before Task 6.

### Task 6: `AdminAuthRepository` (reactive session)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/AdminAuthRepository.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/AdminAuthRepositoryTest.kt`

- [ ] `AdminSession` sealed: `LoggedOut` | `LoggedIn(email, token, expiresAt)`. Constructor takes `apiClient` + `adminTokenStore` (constructor injection, mirroring how repos take DAOs) so tests can inject a fake `ApiClient` — required to test `logout()`-clears-on-`Offline`.
- [ ] `_session: MutableStateFlow<AdminSession>` seeded synchronously from `AdminTokenStore` (past-expiry → `LoggedOut`); expose `session: StateFlow<AdminSession>` and `token(): String?` (reads `_session.value` synchronously — no suspend, no I/O — so it is safe to call from the OkHttp interceptor thread).
- [ ] Extract a pure top-level `isExpired(expiresAt: String, nowUtcIso: String): Boolean` (lexicographic compare; `now` formatted UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` per Technical Details).
- [ ] `login(email, password): LoginOutcome` (`Success`/`InvalidCredentials`/`RateLimited`/`Offline`/`Error`) — on success persist + update flow; map via a pure `loginOutcome(result)` helper.
- [ ] `logout()` — fire `apiClient.logout()` best-effort, then **always** `store.clear()` + set `LoggedOut`.
- [ ] `onUnauthorized()` — `store.clear()` + `LoggedOut`.
- [ ] Extract pure top-level `adminErrorMessage(outcome): String` (RU strings, ambiguous credential message).
- [ ] Write tests: outcome mapping (each branch), `logout()` clears even when API returns `Offline`, `onUnauthorized()` clears, `isExpired` (past → true, future → false, exact-format boundary), seed treats past-expiry as `LoggedOut`, `adminErrorMessage` strings.
- [ ] Run tests — must pass before Task 7.

### Task 7: Wire auth into `AppContainer`

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`

- [ ] Add lazy `adminTokenStore = AdminTokenStore.fromSharedPreferences(context)` and lazy `adminAuthRepository`.
- [ ] Resolve the construction-order cycle (interceptor needs a token provider; repo needs `ApiClient` which needs the interceptor): pass `tokenProvider = { adminAuthRepository.token() }` as a lambda into `AppSignatureInterceptor`. The lambda is only invoked at request time, after both `by lazy` blocks have initialized — `apiClient`'s lazy init must not touch `adminAuthRepository` (it doesn't), and `token()` is a synchronous `StateFlow.value` read, so no init-time recursion and no blocking on the interceptor thread.
- [ ] Compile (`assembleDebug`) — must pass before Task 8. (DI wiring; no unit test, per repo convention.)

### Task 8: Admin entry point in Settings

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`

- [ ] Add `session: AdminSession` + `onOpenAdmin: () -> Unit` params (non-null; present in release builds).
- [ ] Add an «Администратор» `surfaceContainerLow` card with one `AdminRow` mirroring `ChangeTeamRow`/`ThemeRow` styling; subtitle = «Войти» when `LoggedOut` else the admin email; tap → `onOpenAdmin`.
- [ ] Compile — must pass before Task 9. (Stateless Compose; no unit test.)

### Task 9: Admin overlay host wiring in MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] Collect `val adminSession by container.adminAuthRepository.session.collectAsState()` near the existing theme `collectAsState`.
- [ ] Add `var showAdmin by rememberSaveable { mutableStateOf(false) }`; pass `session`/`onOpenAdmin = { showAdmin = true }` into `SettingsScreen`.
- [ ] Render `AdminScreen` overlay in the `Box` **before** the CompPicker/TeamPicker blocks; add `BackHandler(enabled = showAdmin && !showScan && teamFlowStep == None && confirmTeamId == null)` → `showAdmin = false`.
- [ ] Reset `showAdmin = false` in `onScanClick` and on team switch (`LaunchedEffect(selectedTeamId)`), alongside the other overlay resets.
- [ ] Compile — must pass before Task 10. (Host wiring; no unit test.)

### Task 10: `AdminScreen` (login form ↔ admin home)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/AdminScreen.kt`

- [ ] Stateful overlay branching on `session`. `LoggedOut` → login form (email + password fields, «Войти» button), `AdminLoginState { Idle / Submitting / Error(message) }` driving spinner + inline error; on submit call `adminAuthRepository.login(...)` in `applicationScope`, map outcome via `adminErrorMessage`.
- [ ] `LoggedIn` → admin home: show email, «Привязать чип к КП» row → opens provisioning (host flag, Task 12), «Выйти» row → `logout()` in `applicationScope`.
- [ ] `TopAppBar` with back arrow → close overlay.
- [ ] Compile — must pass before Task 11. (Compose UI; logic covered by `adminErrorMessage` test in Task 6.)

### Task 11: Provisioning pure model + rail/hero/rack composables

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningModel.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/admin/ProvisioningModelTest.kt`
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt` (composables; pager wired in Task 12)

- [ ] `ProvisioningModel.kt` (pure, Android-free): `ProvisionState` sealed (`WaitingForChip`/`Binding(uid)`/`Writing`/`Success(number)`/`Failed(reason)`); `RailTick(color?, filled, current)`; pure `railTicks(checkpoints, boundCounts, currentIndex): List<RailTick>`; pure `provisionErrorMessage(result): String` (RU: 409→«Этот тег уже привязан к другому КП» — **the 409 body carries no checkpoint number, so the message is generic**, matching the server's `{"detail":...}`; 404→«КП не найдено»; **403→«Нет прав администратора этой гонки или ошибка подписи/часов»** — the spec returns `403` both for build-HMAC failure AND for an authenticated non-admin user, so this branch must exist; offline/400/429 strings); pure `chipTokenLabel(uid): String` (uid tail).
- [ ] `ProvisioningScreen.kt` composables: the **provisioning rail** (segmented ticks tinted by `CheckpointColor.barColor()`, filled/hollow/enlarged-current), the **hero КП card** (huge `RobotoMono` number ~96sp + color band + cost), the **chip rack** (pre-seeded "уже привязано" tokens + fresh tokens with uid tail + check), and the **scan zone** (pulsing «Приложите чип к телефону» / state text). Stamp scale-in + haptic on success; respect reduced motion (instant when disabled).
- [ ] Write tests for `railTicks` (empty/filled/current), `provisionErrorMessage` (each status), `chipTokenLabel`.
- [ ] Run tests — must pass before Task 12.

### Task 12: Provisioning pager wiring + chip bind/write

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/ProvisioningScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/admin/AdminScreen.kt` (or MainActivity) — open provisioning
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — `onTagForWrite` arming + bind/write side-effects

- [ ] `HorizontalPager` over `legendRepository.checkpointsForRace(raceId).collectAsState()` for the selected team's race; if no team/race selected, show a hint (default to selected race — keep tight). State which checkpoint types are pageable — bind accepts only real КП (server `404`s on `hidden`), so either filter the pager to bindable rows or accept the inline `404`/`400` as the guard; note the choice.
- [ ] Pre-seed per-КП bound counts from cached `TagEntity` rows (count per `checkpoint.id`) via a Room flow / one-shot read.
- [ ] Add the `@Volatile var onTagForProvision: ((Tag) -> Unit)?` hook to `MainActivity` and splice it into the `onTagDiscovered` priority chain right after `onTagForWrite` (per Technical Details). `DisposableEffect` arms `(context as MainActivity).onTagForProvision = { tag -> ... }`; clears to null on dispose. Bind only when `!pagerState.isScrollInProgress`, to `checkpoints[pagerState.currentPage]`.
- [ ] On tag: in `applicationScope`, `apiClient.bindTag(raceId, cp.id, normalizeNfcUid(tag.id))`; `Success` → `chipCodeFromHex(code)` → `writeChipCodeNdef(tag, bytes, packageName)` off-main; update `ProvisionState` + rack + rail.
- [ ] Error handling: `409` → token flashes red with the generic «уже привязан к другому КП» message (the body has no КП number), not added; `403/404/400/429/Offline` → inline scan-zone message (`403` = non-admin user **or** signature/clock failure); `401` → `adminAuthRepository.onUnauthorized()` + drop to login; write failure → «Не удалось записать, приложите снова» (re-scan re-binds idempotently `200` + same `code` + rewrite).
- [ ] Add a `BackHandler` for the provisioning sub-state if it's a nested flag; reset on overlay close.
- [ ] Compile + run unit suite — must pass before Task 13. (Pager UI untested; logic via Task 11 tests.)

### Task 13: Verify acceptance criteria (repo-verifiable gates)

- [ ] Confirm existing GET signing unchanged (regression): old `SigningTest`/`ApiClientTest` still green.
- [ ] Confirm no Room schema bump (no new `schemas/*.json`, no migration added).
- [ ] Run full `./gradlew testDebugUnitTest` — must pass.
- [ ] Run `./gradlew lintDebug` — must pass.
- [ ] Run `./gradlew assembleDebug` — must compile (release params for the admin card present too).

*(On-device behavioral verification — login/bearer/401/chip-write/409/offline — requires hardware + a real admin account; see Post-Completion.)*

### Task 14: Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] Document: interceptor body-hash signing + bearer `tokenProvider`; `ApiClient` `PostResult` + login/logout/bindTag; `AdminTokenStore` + `AdminAuthRepository` (`AdminSession`, expiry, `onUnauthorized`); the admin overlay pattern (`showAdmin`); `AdminScreen` + `ProvisioningScreen` pager/rail + `ProvisioningModel`; the new DTOs.
- [ ] Move this plan to `docs/plans/completed/`.

## Post-Completion

*Manual / external — no checkboxes.*

**Manual verification (requires real backend admin account + physical NTAG/Ultralight chips):**
- Login with valid creds → token stored; wrong creds → ambiguous «Неверный email или пароль»; >5/min → 429 message.
- Bind a fresh chip to a КП → `201`, `code` written, chip auto-opens the app when scanned closed (NDEF+AAR). Re-tap same chip/same КП → `200`, no duplicate. Tap a chip already on another КП → `409` red flash.
- Pull NFC chip away mid-write → «приложите снова», re-tap rewrites.
- Force-expire/revoke token server-side → next protected call `401` → app returns to login and clears token.
- Logout while offline → local session still cleared.
- Clock skew >±300s → all `/app/*` calls `403` (device-clock diagnostic per `docs/API.md`).

**External:**
- None — no consuming projects; server endpoints already deployed (commit `027e91a`).
