# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # build release APK
./gradlew lintDebug            # run lint (must pass before merging)
./gradlew testDebugUnitTest    # run unit tests
```

## Architecture

Single-activity Jetpack Compose app (minSdk 24, targetSdk 36). No ViewModel, no Navigation Compose — screen state lives in `MainActivity.kt` via `rememberSaveable`.

**Current structure:**
- `MainActivity.kt` — entry point; owns tab selection state + `showScan: Boolean`; renders `Scaffold` + `NavigationBar` with three screens; ScanScreen overlaid as a full-screen composable when `showScan = true`
- `ui/marks/MarksScreen.kt` — Отметки tab; orange FAB triggers ScanScreen overlay via `onScanClick` callback
- `ui/legend/LegendScreen.kt` — Легенда tab; bold dark FilterChips, no trailing icon for unvisited CPs
- `ui/team/TeamScreen.kt` — Команда tab; outlined "Привязать" button, simplified avatars
- `ui/scan/ScanScreen.kt` — full-screen overlay for NFC checkpoint marking (A3 screen)
- `ui/theme/Color.kt` — full M3 light/dark token set; cool-grey light palette; `OrangeCta = #C65A2E`
- `ui/theme/Theme.kt` — `Kolco24Theme`; always uses the static brand palette (`LightColorScheme`/`DarkColorScheme`); no dynamic color wiring
- `ui/theme/Type.kt` — typography (default system font for now)

**Data layer** (races sync — signed `GET /app/races/` with ETag, Room as single source of truth):
- `Kolco24App.kt` — `Application`; builds `AppContainer`, fires `refreshRaces()` fire-and-forget on `onCreate` (logs the `RefreshResult`). Registered via `android:name=".Kolco24App"`. NB: the root composable in `MainActivity` is `Kolco24AppRoot()` (renamed to avoid clashing with this class).
- `AppContainer.kt` — manual DI (no Hilt); lazy `installId`, `OkHttpClient` + `AppSignatureInterceptor`, `Json`, `ApiClient`, `AppDatabase`, `RaceRepository`, plus `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Reads secrets from `BuildConfig`.
- `data/api/AppSignatureInterceptor.kt` — OkHttp interceptor; HMAC-SHA256 signs every request (canonical string from `docs/API.md`), adds the six `X-App-*`/`X-Install-Id` headers; `ts`/signature recomputed per call (survives retries). Pure `buildCanonical`/`sign` helpers live here.
- `data/api/ApiClient.kt` — `fetchRaces(etag): FetchResult` (`Success`/`NotModified`/`Forbidden`/`Error`); GET on `Dispatchers.IO`, `If-None-Match` sent verbatim with quotes, 10s timeouts. `IOException`/`SerializationException` → `Error` (never throws).
- `data/api/dto/RacesResponse.kt` — `@Serializable` DTOs with snake_case `@SerialName`; `Json { ignoreUnknownKeys = true }`.
- `data/db/` — Room v1 (`AppDatabase`, no migrations): `RaceEntity` (table `races`, server `id` PK — also the domain model, no third layer), `SyncMetaEntity` (table `sync_meta`, composite PK `(origin, resource)`, stores ETag per origin). `RaceDao.replaceAll` is a `@Transaction` deleteAll+insertAll. DAOs are not unit-tested (trivial queries).
- `data/RaceRepository.kt` — `races: Flow<List<RaceEntity>>` from Room; `refreshRaces(): RefreshResult` (`Updated`/`NotModified`/`Offline`/`Forbidden`/`HttpError`). On `Success`: `replaceAll(races)` **then** `upsert(etag)` as two separate transactions — order is deliberate, a crash between them keeps fresh data with a stale ETag so the next refresh re-fetches and self-heals.
- `data/InstallId.kt` — pure `getOrCreate(load, save)` for the install UUID (≤64 chars, API requirement) over an injected key-value store; thin SharedPreferences adapter.

**Config (secrets out of git):** `local.properties` keys `kolco24.apiBaseUrl` / `kolco24.appKeyId` / `kolco24.appSecret` → `BuildConfig` fields `API_BASE_URL` / `APP_KEY_ID` / `APP_SECRET` (see `app/build.gradle.kts`). Falls back to env `KOLCO24_API_BASE_URL` / `KOLCO24_APP_KEY_ID` / `KOLCO24_APP_SECRET` (so lint/test work in CI without the file); the build fails with a descriptive `error(...)` listing missing keys if neither is set. KSP + `kotlin.plugin.serialization` are applied on AGP 9's built-in Kotlin (do NOT apply `kotlin.android` on top — "plugin applied twice"); KSP version is pinned to `2.2.10-*`, and `android.disallowKotlinSourceSets=false` in `gradle.properties` lets KSP register generated sources.

**API reference:** `docs/API.md` — HMAC signing scheme (6 headers, ±300s window), ETag/304 handling, debug checklist for 403s.

**Design reference:** `assets/template_v2.html` — hi-fi HTML mockup (M3 cool-grey variant). Screens: A1b (Отметки), A2 (Легенда), A3 (Отметить КП scan dialog), A4 (Команда).

**M3 color tokens used in the design** (defined in `Color.kt`):
- `primary` #B3261E · `primaryContainer` #FFDAD5 · `onPrimaryContainer` #410002
- `OrangeCta` #C65A2E — FABs, active NavigationBar icon/text, NFC icon tint (not a theme token — used directly)
- `tertiary` #1F7A3D — success/green (КП taken state)
- `inverseSurface` #1D242D — dark hero cards (timer card in ScanScreen)
- `surfaceContainer` #FFFFFF — NavigationBar background (cool white; mapped from `SurfaceContainerDefault` in Color.kt)
- `outlineVariant` #E2E6EB — cool divider/border color

**Scan overlay pattern:**
`MainActivity` wraps `Scaffold` in a `Box(Modifier.fillMaxSize())`. The `ScanScreen` is rendered after the Scaffold inside the same Box — when `showScan = true` it covers the entire screen. Dismiss via the close button or system back (handled by `BackHandler`) sets `showScan = false`. No navigation component used.

**App assets:**
- `app/src/main/assets/adi-registration.properties` — ADI registration token (tracked, do not expose value)
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — separate monochrome icon for Android 13+ themed icons (no white fills; alpha-mask only)
