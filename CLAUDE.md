# CLAUDE.md

## Build commands

```bash
./gradlew assembleDebug              # build debug APK
./gradlew lintDebug                  # run lint (must pass before merging)
./gradlew testDebugUnitTest          # run unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (emulator/device): DAO queries + all Room migrations
```

## Architecture

Single-activity Jetpack Compose app (minSdk 24, targetSdk 36). No ViewModel, no Navigation Compose — screen state lives
in `MainActivity.kt` via `rememberSaveable`. Data syncs via signed `GET` with ETag; Room is the single source of truth.
NFC reader-mode and GPS-track recording are the device subsystems; the Карта tab renders over an offline per-race
MBTiles basemap (MapLibre).

**Per-file design notes live in `docs/design/UI-NOTES.md` (`MainActivity` + `ui/**`, M3 tokens) and
`docs/design/DATA-NOTES.md` (`data/**`, app entry, `TrackRecordingService`, config/build/manifest/deps/assets).** Read
the relevant notes before modifying an area; update them when behavior changes.

## Conventions & gotchas

- **minSdk 24, no desugaring** — `SimpleDateFormat` (`Locale.US`, UTC where needed), never `java.time`. ISO date strings
  are fixed-width: compare lexicographically.
- **Testing** — pure models (`*Logic`, `*Model`, mappers, crypto, `TrustedClock`, `MapFileStorage`, …) are Android-free
  and JVM-unit-tested. Compose UI, Android adapters, and trivial wiring are untested by convention. Trust boundaries
  (request signing, `ServerTimeInterceptor`) are the exception — tested.
- **Duplicate, don't couple** — small repeated UI rows are copied, not extracted to a shared file.
- **Room is shipped** — a schema bump needs a real `Migration` in `.addMigrations(...)` **and** a committed
  `schemas/<n>.json` (`exportSchema = true`), or upgrade crashes.
- **Overlay pattern** — full-screen overlays are `rememberSaveable` flags rendered after `Scaffold` in one `Box`,
  dismissed via `BackHandler`. Per-team/race flows:
  `remember(selectedTeamId) { id?.let { repo.observe(it) } ?: flowOf(emptyList()) }`.
- **Writes outlive overlays** — writes triggered by a closing overlay run on `container.applicationScope`, not
  composition scope.
- **Repo refresh pattern** — on `200`, persist rows (`replaceAllForRace`) **then** upsert the ETag, as two separate
  transactions; skip the ETag upsert when the header is absent.
- **SyncSource routing** — sync repos take `source: SyncSource = SyncSource.Cloud`. A `Cloud` refresh for a pinned race
  returns `Skipped` both at entry **and** in the `200` branch. ETags are partitioned by origin, data tables are not — so
  every `200` write also deletes the other origin's ETag (`SyncMetaDao.deleteEtag`). See `data/lease/`,
  `data/sync/SyncCoordinator.kt`.
- **Dual-target upload loop** (marks, track, judge scans) — `Mutex.tryLock`-guarded, drains in `LIMIT` batches, marks
  only `accepted ∩ batch`, scoped by `(raceId, teamId)` (judge scans: `raceId` only), idempotent by client UUID, cloud
  and LAN flushed independently. `MarkRepository.confirm` is the one marks POST outside `tryLock`.
- **Check method** — scoring uses `isCounted` (`complete && (offline || confirmedAt != null)`), not `complete`;
  `confirmedAt` is set only from the open scan overlay, never by the drain.
- **Secrets** — `BuildConfig` fields from `local.properties` (env fallback for CI). `LOCAL_API_BASE_URL` host must match
  the cleartext `domain-config` in `res/xml/network_security_config.xml`.
- **Manifest** — keep `android.permission.VIBRATE`; `MainActivity` is portrait-locked (no rotation recreates).
- **Build** — AGP 9 built-in Kotlin: do **not** also apply `kotlin.android`.

## Module map

- `MainActivity.kt` — 4-tab pager (Отметки / Легенда / Карта / Команда), all overlay state, NFC dispatch, upload
  tickers.
- `ui/` — `scan`, `marks`, `legend`, `map`, `team`, `teampicker`, `photo`, `admin`, `settings`, `upload`, `track`,
  `common`, `theme`.
- `data/` — `api`, `db` (Room v8), `lease`/`sync`, sync repos, `MarkRepository`, `JudgeScanRepository`, `marks`, `map`,
  `track`, `nfc`, `crypto`, `time`, prefs/adapters.

## Docs

`docs/API.md` (HMAC signing, ETag, 403 checklist) · `docs/design/API.md` (legend crypto) · `docs/design/UPLOAD.md`
(upload contracts) · `docs/mobile-admin-auth-and-tags.md`.
